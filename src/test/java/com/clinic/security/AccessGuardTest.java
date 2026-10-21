package com.clinic.security;

import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.DocumentStatus;
import com.clinic.domain.Patient;
import com.clinic.domain.PatientDocument;
import com.clinic.domain.Provider;
import com.clinic.domain.Role;
import com.clinic.domain.VisitSummary;
import com.clinic.domain.VisitSummaryStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance criteria that matter most: a patient can never reach another
 * patient's chart or an unreleased visit summary, and the front desk is kept
 * out of clinical content — all enforced server-side, not by hiding buttons.
 *
 * <p>Hand-written test doubles are used rather than a mocking framework so the
 * suite runs on any JDK the project may be built with.</p>
 */
class AccessGuardTest {

    /** Result the fake count queries return; drives the "is on my panel" checks. */
    private long countResult;

    private FakeCurrentUser currentUser;
    private FakeAuditService auditService;
    private AccessGuard guard;

    private Patient own;
    private Patient other;

    @BeforeEach
    void setUp() throws Exception {
        countResult = 0L;
        own = patient(1L, "NG-100001");
        other = patient(2L, "NG-100002");
        currentUser = new FakeCurrentUser();
        auditService = new FakeAuditService();

        guard = new AccessGuard();
        inject(guard, "em", fakeEntityManager());
        inject(guard, "currentUser", currentUser);
        inject(guard, "auditService", auditService);
    }

    // -------------------------------------------------------------- helpers

    private static Patient patient(long id, String mrn) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.setMrn(mrn);
        return patient;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** An EntityManager that answers only what AccessGuard actually asks for. */
    private EntityManager fakeEntityManager() {
        TypedQuery<Long> countQuery = (TypedQuery<Long>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{TypedQuery.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setParameter", "setMaxResults", "setFirstResult" -> proxy;
                    case "getSingleResult" -> countResult;
                    case "toString" -> "fake-count-query";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });

        return (EntityManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createQuery" -> countQuery;
                    // Every lookup in these tests resolves to an existing patient.
                    case "find" -> other;
                    case "toString" -> "fake-entity-manager";
                    case "hashCode" -> 2;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private void actingAsPatient(Long linkedPatientId) {
        currentUser.role = Role.PATIENT;
        currentUser.patientId = linkedPatientId;
        currentUser.provider = null;
    }

    private void actingAsClinician(long providerId) {
        Provider provider = new Provider();
        provider.setId(providerId);
        provider.setActive(true);
        currentUser.role = Role.CLINICIAN;
        currentUser.provider = provider;
        currentUser.patientId = null;
    }

    private void actingAs(Role role) {
        currentUser.role = role;
        currentUser.patientId = null;
        currentUser.provider = null;
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("a patient may reach their own chart")
    void patientReachesOwnChart() {
        actingAsPatient(1L);
        assertEquals("patient:self", guard.requirePatientAccess(1L).basis());
    }

    @Test
    @DisplayName("a patient may NOT reach another patient's chart")
    void patientCannotReachAnotherChart() {
        actingAsPatient(1L);
        AccessDeniedException denied =
                assertThrows(AccessDeniedException.class, () -> guard.requirePatientAccess(2L));
        assertEquals("patient:2", denied.getResource());
        assertTrue(auditService.denials > 0, "an access denial must be audited");
    }

    @Test
    void anUnlinkedPatientAccountReachesNothing() {
        actingAsPatient(null);
        assertThrows(AccessDeniedException.class, () -> guard.requirePatientAccess(1L));
    }

    @Test
    void aMissingPatientIdIsRejected() {
        actingAsPatient(1L);
        assertThrows(AccessDeniedException.class, () -> guard.requirePatientAccess(null));
    }

    @Test
    @DisplayName("a clinician is limited to their own panel")
    void clinicianLimitedToPanel() {
        actingAsClinician(7L);

        countResult = 0L;
        assertThrows(AccessDeniedException.class, () -> guard.requirePatientAccess(2L));

        countResult = 1L;
        assertEquals("clinician:assigned:provider=7", guard.requirePatientAccess(2L).basis());
    }

    @Test
    @DisplayName("a patient cannot open an unreleased visit summary")
    void patientCannotOpenADraftSummary() {
        actingAsPatient(1L);
        VisitSummary summary = new VisitSummary();
        summary.setId(10L);
        summary.setPatient(own);
        summary.setStatus(VisitSummaryStatus.DRAFT);

        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                () -> guard.requireVisitSummaryAccess(summary));
        assertEquals("visit_summary:10", denied.getResource());

        summary.setStatus(VisitSummaryStatus.RELEASED);
        assertEquals("patient:self", guard.requireVisitSummaryAccess(summary).basis());
    }

    @Test
    @DisplayName("front desk is blocked from clinical content in the business tier")
    void frontDeskBlockedFromClinicalContent() {
        actingAs(Role.FRONT_DESK);

        // Demographic and scheduling scope is fine...
        assertEquals("front_desk:scheduling_scope", guard.requirePatientAccess(2L).basis());
        // ...clinical scope is not.
        assertThrows(AccessDeniedException.class, () -> guard.requireClinicalPatientAccess(2L));
        assertThrows(AccessDeniedException.class, () -> guard.requireClinician());
    }

    @Test
    void clinicAdminHasAdministrativeScope() {
        actingAs(Role.CLINIC_ADMIN);
        assertEquals("clinic_admin:administrative_scope",
                guard.requirePatientAccess(2L).basis());
    }

    @Test
    @DisplayName("a patient cannot reach an internal-only document")
    void patientCannotReachInternalDocument() {
        actingAsPatient(1L);
        PatientDocument document = new PatientDocument();
        document.setId(55L);
        document.setPatient(own);
        document.setInternalOnly(true);
        document.setStatus(DocumentStatus.VERIFIED);

        assertThrows(AccessDeniedException.class, () -> guard.requireDocumentAccess(document));

        document.setInternalOnly(false);
        assertEquals("patient:self", guard.requireDocumentAccess(document).basis());
    }

    @Test
    void missingRecordsAreNotFoundRatherThanForbidden() {
        actingAsPatient(1L);
        assertThrows(RecordNotFoundException.class, () -> guard.requireDocumentAccess(null));
        assertThrows(RecordNotFoundException.class, () -> guard.requireVisitSummaryAccess(null));
        assertThrows(RecordNotFoundException.class, () -> guard.requirePrescriptionAccess(null));
        assertThrows(RecordNotFoundException.class, () -> guard.requireRefillAccess(null));
        assertThrows(RecordNotFoundException.class, () -> guard.requireThreadAccess(null));
    }

    @Test
    void roleGatesRejectTheWrongRole() {
        actingAsPatient(1L);
        assertThrows(AccessDeniedException.class, () -> guard.requireStaff());
        assertThrows(AccessDeniedException.class, () -> guard.requireClinicAdmin());
        assertThrows(AccessDeniedException.class, () -> guard.requireSchedulingStaff());

        actingAs(Role.CLINIC_ADMIN);
        guard.requireClinicAdmin();
        guard.requireStaff();
        guard.requireSchedulingStaff();
    }

    @Test
    void anonymousCallersAreRejected() {
        currentUser.role = null;
        assertThrows(AccessDeniedException.class, () -> guard.requireAuthenticated());
        assertThrows(AccessDeniedException.class, () -> guard.requirePatientAccess(1L));
    }

    @Test
    void internalNotesAreStaffOnly() {
        actingAsPatient(1L);
        assertFalse(guard.canSeeInternalNotes());
        actingAs(Role.CLINICIAN);
        assertTrue(guard.canSeeInternalNotes());
    }

    // -------------------------------------------------------- test doubles

    /** A CurrentUser whose identity is set directly rather than resolved. */
    private static final class FakeCurrentUser extends CurrentUser {

        private static final long serialVersionUID = 1L;

        private Role role;
        private Long patientId;
        private Provider provider;

        @Override
        public boolean isAuthenticated() {
            return role != null;
        }

        @Override
        public Role getRole() {
            return role;
        }

        @Override
        public boolean isStaff() {
            return role != null && role.isStaff();
        }

        @Override
        public Long getPatientId() {
            return patientId;
        }

        @Override
        public Provider getProvider() {
            return provider;
        }

        @Override
        public String getClientIp() {
            return "127.0.0.1";
        }
    }

    /** Counts denial events instead of writing them. */
    private static final class FakeAuditService extends AuditService {

        private int denials;

        @Override
        public void recordIndependently(AuditEntry entry) {
            denials++;
        }

        @Override
        public void record(AuditEntry entry) {
            // no-op
        }
    }
}
