package com.clinic.security;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.MessageThread;
import com.clinic.domain.Patient;
import com.clinic.domain.PatientDocument;
import com.clinic.domain.Prescription;
import com.clinic.domain.Provider;
import com.clinic.domain.RefillRequest;
import com.clinic.domain.Role;
import com.clinic.domain.VisitSummary;

import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Central ownership and scope checks.
 *
 * <p><strong>Precision-baseline primitive #1.</strong> The ownership/scope
 * decision deliberately lives in this shared service, one call frame above the
 * controller or resource method that needs it, rather than being repeated
 * inline at each call site. Controllers call
 * {@link #requirePatientAccess(Long)} (and its siblings) and act on the
 * returned {@link AccessDecision}; the actual comparison of the acting user
 * against the record owner happens here.</p>
 *
 * <p>Container role checks ({@code web.xml} constraints and
 * {@code @RolesAllowed}) run first and are necessary, but they only establish
 * <em>which</em> role is calling. They cannot express "this patient, not that
 * one", so every read, edit, download, export and admin action additionally
 * passes through this guard.</p>
 */
@Stateless
@PermitAll
public class AccessGuard {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    // ---------------------------------------------------------------- roles

    public void requireAuthenticated() {
        if (!currentUser.isAuthenticated()) {
            deny("Not authenticated.", "session");
        }
    }

    public void requireStaff() {
        requireAuthenticated();
        if (!currentUser.isStaff()) {
            deny("Staff access is required for this action.", "staff");
        }
    }

    public void requireRole(Role... roles) {
        requireAuthenticated();
        Role actual = currentUser.getRole();
        for (Role role : roles) {
            if (role == actual) {
                return;
            }
        }
        deny("Your role is not permitted to perform this action.", "role");
    }

    /** Clinical authoring is restricted to clinicians; front desk is excluded. */
    public Provider requireClinician() {
        requireAuthenticated();
        if (currentUser.getRole() != Role.CLINICIAN) {
            deny("Only a clinician may edit clinical content.", "clinical_authoring");
        }
        Provider provider = currentUser.getProvider();
        if (provider == null || !provider.isActive()) {
            deny("Your clinician account is not linked to an active provider record.",
                    "clinical_authoring");
        }
        return provider;
    }

    public void requireClinicAdmin() {
        requireRole(Role.CLINIC_ADMIN);
    }

    /** Scheduling may be performed by front desk, clinicians and admins. */
    public void requireSchedulingStaff() {
        requireRole(Role.FRONT_DESK, Role.CLINICIAN, Role.CLINIC_ADMIN);
    }

    // ------------------------------------------------------------- patients

    /**
     * The single ownership/scope rule for patient-scoped data.
     *
     * <ul>
     *   <li>a patient may only ever reach their own chart;</li>
     *   <li>a clinician may reach a patient they are assigned to, are the
     *       primary provider for, or have an appointment with;</li>
     *   <li>front desk may reach demographic and scheduling scope for any
     *       active patient of the practice;</li>
     *   <li>a clinic admin may reach any patient administratively.</li>
     * </ul>
     */
    public AccessDecision requirePatientAccess(Long patientId) {
        requireAuthenticated();
        if (patientId == null) {
            deny("No patient specified.", "patient");
        }
        Role role = currentUser.getRole();
        switch (role) {
            case PATIENT -> {
                Long own = currentUser.getPatientId();
                if (own == null || !own.equals(patientId)) {
                    deny("You may only access your own records.", "patient:" + patientId);
                }
                return AccessDecision.self();
            }
            case CLINICIAN -> {
                Provider provider = currentUser.getProvider();
                if (provider == null) {
                    deny("Your account is not linked to a provider record.", "patient:" + patientId);
                }
                if (isAssignedToProvider(patientId, provider.getId())) {
                    return AccessDecision.assignedClinician(provider.getId());
                }
                if (hasAppointmentWithProvider(patientId, provider.getId())) {
                    return AccessDecision.treatingClinician(provider.getId());
                }
                deny("This patient is not on your panel.", "patient:" + patientId);
                return null;
            }
            case FRONT_DESK -> {
                requireExistingPatient(patientId);
                return AccessDecision.frontDesk();
            }
            case CLINIC_ADMIN -> {
                requireExistingPatient(patientId);
                return AccessDecision.clinicAdmin();
            }
            default -> {
                deny("Unsupported role.", "patient:" + patientId);
                return null;
            }
        }
    }

    /**
     * Patient access for clinical <em>content</em> (visit summaries, notes,
     * prescriptions). Front desk is deliberately excluded here even though it
     * passes {@link #requirePatientAccess(Long)} for demographics.
     */
    public AccessDecision requireClinicalPatientAccess(Long patientId) {
        AccessDecision decision = requirePatientAccess(patientId);
        if (currentUser.getRole() == Role.FRONT_DESK) {
            deny("Front desk may not access clinical content.", "clinical:" + patientId);
        }
        return decision;
    }

    private void requireExistingPatient(Long patientId) {
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
    }

    private boolean isAssignedToProvider(Long patientId, Long providerId) {
        Long count = em.createQuery(
                        "SELECT COUNT(a) FROM PatientProviderAssignment a "
                                + "WHERE a.patient.id = :patientId AND a.provider.id = :providerId",
                        Long.class)
                .setParameter("patientId", patientId)
                .setParameter("providerId", providerId)
                .getSingleResult();
        if (count > 0) {
            return true;
        }
        Long primary = em.createQuery(
                        "SELECT COUNT(p) FROM Patient p "
                                + "WHERE p.id = :patientId AND p.primaryProvider.id = :providerId",
                        Long.class)
                .setParameter("patientId", patientId)
                .setParameter("providerId", providerId)
                .getSingleResult();
        return primary > 0;
    }

    private boolean hasAppointmentWithProvider(Long patientId, Long providerId) {
        Long count = em.createQuery(
                        "SELECT COUNT(a) FROM Appointment a "
                                + "WHERE a.patient.id = :patientId AND a.provider.id = :providerId",
                        Long.class)
                .setParameter("patientId", patientId)
                .setParameter("providerId", providerId)
                .getSingleResult();
        return count > 0;
    }

    // ------------------------------------------------------- record helpers

    public AccessDecision requireVisitSummaryAccess(VisitSummary summary) {
        if (summary == null) {
            throw new RecordNotFoundException("Visit summary not found.");
        }
        AccessDecision decision = requireClinicalPatientAccess(summary.getPatient().getId());
        if (currentUser.getRole() == Role.PATIENT && !summary.getStatus().isPatientVisible()) {
            // A draft summary is clinician-only, even for the patient it is about.
            deny("This visit summary has not been released yet.",
                    "visit_summary:" + summary.getId());
        }
        return decision;
    }

    public AccessDecision requireDocumentAccess(PatientDocument document) {
        if (document == null) {
            throw new RecordNotFoundException("Document not found.");
        }
        AccessDecision decision = requirePatientAccess(document.getPatient().getId());
        if (currentUser.getRole() == Role.PATIENT && document.isInternalOnly()) {
            deny("This document is not available to you.", "document:" + document.getId());
        }
        return decision;
    }

    public AccessDecision requirePrescriptionAccess(Prescription prescription) {
        if (prescription == null) {
            throw new RecordNotFoundException("Prescription not found.");
        }
        return requireClinicalPatientAccess(prescription.getPatient().getId());
    }

    public AccessDecision requireRefillAccess(RefillRequest request) {
        if (request == null) {
            throw new RecordNotFoundException("Refill request not found.");
        }
        return requireClinicalPatientAccess(request.getPatient().getId());
    }

    public AccessDecision requireThreadAccess(MessageThread thread) {
        if (thread == null) {
            throw new RecordNotFoundException("Message thread not found.");
        }
        return requirePatientAccess(thread.getPatient().getId());
    }

    /** True when the caller is entitled to read internal-only staff notes. */
    public boolean canSeeInternalNotes() {
        return currentUser.isStaff();
    }

    // ----------------------------------------------------------------- deny

    private void deny(String message, String resource) {
        auditService.recordIndependently(
                AuditEntry.of(AuditAction.ACCESS_DENIED, "authorization")
                        .detail(message)
                        .basis("denied:" + resource)
                        .anonymousActor("anonymous"));
        throw new AccessDeniedException(message, resource);
    }
}
