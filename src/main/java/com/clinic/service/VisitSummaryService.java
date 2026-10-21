package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.Appointment;
import com.clinic.domain.AppointmentStatus;
import com.clinic.domain.Provider;
import com.clinic.domain.Role;
import com.clinic.domain.VisitSummary;
import com.clinic.domain.VisitSummaryStatus;
import com.clinic.security.AccessDecision;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.CurrentUser;
import com.clinic.security.RecordNotFoundException;
import com.clinic.util.Validations;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Visit summaries: clinician authoring and the draft → released workflow.
 *
 * <p>A summary is invisible to the patient until it is released. Front desk
 * staff can never create, edit or release one — that restriction is enforced
 * here in the business tier, not only by hiding buttons.</p>
 */
@Stateless
public class VisitSummaryService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    @Inject
    private NotificationService notificationService;

    // --------------------------------------------------------------- reading

    @PermitAll
    public VisitSummary open(Long summaryId) {
        VisitSummary summary = em.find(VisitSummary.class, summaryId);
        if (summary == null) {
            throw new RecordNotFoundException("Visit summary not found.");
        }
        AccessDecision decision = accessGuard.requireVisitSummaryAccess(summary);
        auditService.recordIndependently(
                AuditEntry.of(AuditAction.VISIT_SUMMARY_VIEWED, "visit_summary")
                        .entity(summary.getId())
                        .patient(summary.getPatient().getId())
                        .detail("status=" + summary.getStatus().getCode())
                        .basis(decision.basis()));
        return summary;
    }

    /** Everything on the chart, including drafts. Clinical roles only. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE})
    public List<VisitSummary> allForPatient(Long patientId) {
        accessGuard.requireClinicalPatientAccess(patientId);
        return em.createNamedQuery("VisitSummary.byPatient", VisitSummary.class)
                .setParameter("patientId", patientId)
                .getResultList();
    }

    /**
     * Only released/amended summaries. This is the query the patient portal
     * uses; the filter is in the query itself so a draft can never leak.
     */
    @PermitAll
    public List<VisitSummary> releasedForPatient(Long patientId) {
        accessGuard.requirePatientAccess(patientId);
        return em.createNamedQuery("VisitSummary.releasedByPatient", VisitSummary.class)
                .setParameter("patientId", patientId)
                .getResultList();
    }

    @RolesAllowed(Role.CLINICIAN_ROLE)
    public List<VisitSummary> myDrafts(int limit) {
        Provider provider = accessGuard.requireClinician();
        return em.createQuery(
                        "SELECT v FROM VisitSummary v WHERE v.provider.id = :providerId "
                                + "AND v.status = :status ORDER BY v.visitDate DESC",
                        VisitSummary.class)
                .setParameter("providerId", provider.getId())
                .setParameter("status", VisitSummaryStatus.DRAFT)
                .setMaxResults(Math.min(Math.max(limit, 1), 100))
                .getResultList();
    }

    @PermitAll
    public VisitSummary forAppointment(Long appointmentId) {
        Appointment appointment = em.find(Appointment.class, appointmentId);
        if (appointment == null) {
            throw new RecordNotFoundException("Appointment not found.");
        }
        accessGuard.requirePatientAccess(appointment.getPatient().getId());
        return em.createQuery(
                        "SELECT v FROM VisitSummary v WHERE v.appointment.id = :appointmentId",
                        VisitSummary.class)
                .setParameter("appointmentId", appointmentId)
                .getResultStream().findFirst().orElse(null);
    }

    // -------------------------------------------------------------- authoring

    /** Creates a draft summary for one of the clinician's own appointments. */
    @RolesAllowed(Role.CLINICIAN_ROLE)
    public VisitSummary createDraft(Long appointmentId) {
        Provider provider = accessGuard.requireClinician();
        Appointment appointment = em.find(Appointment.class, appointmentId);
        if (appointment == null) {
            throw new RecordNotFoundException("Appointment not found.");
        }
        AccessDecision decision =
                accessGuard.requireClinicalPatientAccess(appointment.getPatient().getId());
        if (!appointment.getProvider().getId().equals(provider.getId())) {
            throw new BusinessRuleException(
                    "Only the provider on the appointment may write its visit summary.");
        }
        if (appointment.getStatus() != AppointmentStatus.CHECKED_IN
                && appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw new BusinessRuleException(
                    "A visit summary can only be written once the patient has checked in.");
        }
        if (forAppointmentInternal(appointmentId) != null) {
            throw new BusinessRuleException("This appointment already has a visit summary.");
        }

        VisitSummary summary = new VisitSummary();
        summary.setAppointment(appointment);
        summary.setPatient(appointment.getPatient());
        summary.setProvider(provider);
        summary.setStatus(VisitSummaryStatus.DRAFT);
        summary.setVisitDate(appointment.getStartAt().toLocalDate());
        summary.setChiefComplaint(appointment.getReason());
        em.persist(summary);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.VISIT_SUMMARY_CREATED, "visit_summary")
                .entity(summary.getId())
                .patient(summary.getPatient().getId())
                .after("status=draft; appointment=" + appointmentId)
                .basis(decision.basis()));
        return summary;
    }

    private VisitSummary forAppointmentInternal(Long appointmentId) {
        return em.createQuery(
                        "SELECT v FROM VisitSummary v WHERE v.appointment.id = :appointmentId",
                        VisitSummary.class)
                .setParameter("appointmentId", appointmentId)
                .getResultStream().findFirst().orElse(null);
    }

    /**
     * Saves clinical content. Restricted to the authoring clinician; front desk
     * attempts are rejected server-side by {@link AccessGuard#requireClinician}.
     */
    @RolesAllowed(Role.CLINICIAN_ROLE)
    public VisitSummary saveContent(Long summaryId, String chiefComplaint, String diagnosis,
                                    String careInstructions, String clinicianNotes,
                                    String followUpAdvice) {
        Provider provider = accessGuard.requireClinician();
        VisitSummary summary = em.find(VisitSummary.class, summaryId);
        if (summary == null) {
            throw new RecordNotFoundException("Visit summary not found.");
        }
        AccessDecision decision =
                accessGuard.requireClinicalPatientAccess(summary.getPatient().getId());
        if (!summary.getProvider().getId().equals(provider.getId())) {
            throw new BusinessRuleException("Only the authoring clinician may edit this summary.");
        }
        String before = describe(summary);

        summary.setChiefComplaint(
                Validations.optionalText(chiefComplaint, "Chief complaint", 300));
        summary.setDiagnosis(Validations.requireText(diagnosis, "Assessment", 8000));
        summary.setCareInstructions(
                Validations.requireText(careInstructions, "Care instructions", 8000));
        summary.setClinicianNotes(
                Validations.optionalText(clinicianNotes, "Clinician notes", 8000));
        summary.setFollowUpAdvice(
                Validations.optionalText(followUpAdvice, "Follow-up advice", 400));
        summary.setUpdatedAt(LocalDateTime.now());

        auditService.record(AuditEntry.of(AuditAction.VISIT_SUMMARY_UPDATED, "visit_summary")
                .entity(summary.getId())
                .patient(summary.getPatient().getId())
                .before(before)
                .after(describe(summary))
                .basis(decision.basis()));
        return summary;
    }

    /** Releases a draft to the patient. */
    @RolesAllowed(Role.CLINICIAN_ROLE)
    public VisitSummary release(Long summaryId) {
        Provider provider = accessGuard.requireClinician();
        VisitSummary summary = em.find(VisitSummary.class, summaryId);
        if (summary == null) {
            throw new RecordNotFoundException("Visit summary not found.");
        }
        AccessDecision decision =
                accessGuard.requireClinicalPatientAccess(summary.getPatient().getId());
        if (!summary.getProvider().getId().equals(provider.getId())) {
            throw new BusinessRuleException("Only the authoring clinician may release this summary.");
        }
        VisitSummaryStatus current = summary.getStatus();
        if (!current.canTransitionTo(VisitSummaryStatus.RELEASED)) {
            throw new BusinessRuleException("This summary is already released.");
        }
        if (summary.getDiagnosis() == null || summary.getCareInstructions() == null) {
            throw new BusinessRuleException(
                    "Add an assessment and care instructions before releasing.");
        }
        summary.setStatus(VisitSummaryStatus.RELEASED);
        summary.setReleasedAt(LocalDateTime.now());
        summary.setReleasedBy(currentUser.getUser());
        summary.setUpdatedAt(LocalDateTime.now());

        auditService.record(AuditEntry.of(AuditAction.VISIT_SUMMARY_RELEASED, "visit_summary")
                .entity(summary.getId())
                .patient(summary.getPatient().getId())
                .before("status=" + current.getCode())
                .after("status=released")
                .basis(decision.basis()));

        if (summary.getPatient().getUser() != null) {
            notificationService.notifyUser(summary.getPatient().getUser(),
                    "visit_summary.released", "Your visit summary is ready",
                    "Visit on " + summary.getVisitDate() + " with " + provider.getDisplayName(),
                    "/patient/visit-summaries.xhtml");
        }
        return summary;
    }

    /** Re-opens a released summary for correction. */
    @RolesAllowed(Role.CLINICIAN_ROLE)
    public VisitSummary amend(Long summaryId, String reason) {
        Provider provider = accessGuard.requireClinician();
        VisitSummary summary = em.find(VisitSummary.class, summaryId);
        if (summary == null) {
            throw new RecordNotFoundException("Visit summary not found.");
        }
        AccessDecision decision =
                accessGuard.requireClinicalPatientAccess(summary.getPatient().getId());
        if (!summary.getProvider().getId().equals(provider.getId())) {
            throw new BusinessRuleException("Only the authoring clinician may amend this summary.");
        }
        VisitSummaryStatus current = summary.getStatus();
        if (!current.canTransitionTo(VisitSummaryStatus.AMENDED)) {
            throw new BusinessRuleException("Only a released summary can be amended.");
        }
        summary.setStatus(VisitSummaryStatus.AMENDED);
        summary.setUpdatedAt(LocalDateTime.now());
        auditService.record(AuditEntry.of(AuditAction.VISIT_SUMMARY_AMENDED, "visit_summary")
                .entity(summary.getId())
                .patient(summary.getPatient().getId())
                .before("status=" + current.getCode())
                .after("status=amended")
                .detail(Validations.requireText(reason, "Reason for amendment", 400))
                .basis(decision.basis()));
        return summary;
    }

    public long countDraftsForProvider(Long providerId) {
        return em.createQuery(
                        "SELECT COUNT(v) FROM VisitSummary v WHERE v.provider.id = :providerId "
                                + "AND v.status = :status", Long.class)
                .setParameter("providerId", providerId)
                .setParameter("status", VisitSummaryStatus.DRAFT)
                .getSingleResult();
    }

    static String describe(VisitSummary v) {
        return "status=" + v.getStatus().getCode()
                + "; complaint=" + v.getChiefComplaint()
                + "; diagnosis=" + v.getDiagnosis()
                + "; instructions=" + v.getCareInstructions()
                + "; notes=" + v.getClinicianNotes();
    }
}
