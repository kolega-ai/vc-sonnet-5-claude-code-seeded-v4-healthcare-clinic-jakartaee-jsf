package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.Patient;
import com.clinic.domain.Prescription;
import com.clinic.domain.PrescriptionStatus;
import com.clinic.domain.Provider;
import com.clinic.domain.RefillRequest;
import com.clinic.domain.RefillStatus;
import com.clinic.domain.Role;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Prescriptions and the refill request workflow.
 *
 * <p>Only clinicians may issue, change or decide on a prescription. Front desk
 * staff can see that a refill queue exists but every decision method rejects
 * them in the business tier.</p>
 */
@Stateless
public class PrescriptionService {

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

    @Inject
    private IntegrationService integrationService;

    // --------------------------------------------------------- prescriptions

    @PermitAll
    public List<Prescription> forPatient(Long patientId) {
        AccessDecision decision = accessGuard.requireClinicalPatientAccess(patientId);
        List<Prescription> results = em.createNamedQuery("Prescription.byPatient", Prescription.class)
                .setParameter("patientId", patientId)
                .getResultList();
        auditService.recordRead("prescription.list_viewed", "prescription",
                patientId, results.size(), decision.basis());
        return results;
    }

    @PermitAll
    public Prescription require(Long prescriptionId) {
        Prescription prescription = em.find(Prescription.class, prescriptionId);
        accessGuard.requirePrescriptionAccess(prescription);
        return prescription;
    }

    @RolesAllowed(Role.CLINICIAN_ROLE)
    public Prescription create(Long patientId, String medication, String dosage,
                               String instructions, int quantity, int refills,
                               LocalDate expiresAt) {
        Provider provider = accessGuard.requireClinician();
        AccessDecision decision = accessGuard.requireClinicalPatientAccess(patientId);
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
        Validations.require(quantity >= 1 && quantity <= 360, "Quantity must be between 1 and 360.");
        Validations.require(refills >= 0 && refills <= 12, "Refills must be between 0 and 12.");
        if (expiresAt != null && !expiresAt.isAfter(LocalDate.now())) {
            throw new BusinessRuleException("The expiry date must be in the future.");
        }

        Prescription prescription = new Prescription();
        prescription.setPatient(patient);
        prescription.setProvider(provider);
        prescription.setMedicationName(Validations.requireText(medication, "Medication", 180));
        prescription.setDosage(Validations.requireText(dosage, "Dosage", 120));
        prescription.setInstructions(Validations.requireText(instructions, "Instructions", 500));
        prescription.setStatus(PrescriptionStatus.ACTIVE);
        prescription.setQuantity(quantity);
        prescription.setRefillsRemaining(refills);
        prescription.setIssuedAt(LocalDate.now());
        prescription.setExpiresAt(expiresAt);
        em.persist(prescription);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.PRESCRIPTION_CREATED, "prescription")
                .entity(prescription.getId())
                .patient(patientId)
                .after(describe(prescription))
                .basis(decision.basis()));

        if (patient.getUser() != null) {
            notificationService.notifyUser(patient.getUser(), "prescription.created",
                    "New prescription", medication + " — " + dosage,
                    "/patient/prescriptions.xhtml");
        }
        return prescription;
    }

    @RolesAllowed(Role.CLINICIAN_ROLE)
    public Prescription changeStatus(Long prescriptionId, PrescriptionStatus target, String reason) {
        accessGuard.requireClinician();
        Prescription prescription = em.find(Prescription.class, prescriptionId);
        if (prescription == null) {
            throw new RecordNotFoundException("Prescription not found.");
        }
        AccessDecision decision = accessGuard.requirePrescriptionAccess(prescription);
        PrescriptionStatus current = prescription.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new BusinessRuleException("A " + current.getLabel().toLowerCase()
                    + " prescription cannot become " + target.getLabel().toLowerCase() + ".");
        }
        prescription.setStatus(target);
        prescription.setUpdatedAt(LocalDateTime.now());
        auditService.record(AuditEntry.of(AuditAction.PRESCRIPTION_STATUS_CHANGED, "prescription")
                .entity(prescription.getId())
                .patient(prescription.getPatient().getId())
                .before("status=" + current.getCode())
                .after("status=" + target.getCode())
                .detail(Validations.requireText(reason, "Reason", 400))
                .basis(decision.basis()));
        return prescription;
    }

    // ------------------------------------------------------------- refills

    /** A patient requests a refill on one of their own active prescriptions. */
    @RolesAllowed(Role.PATIENT_ROLE)
    public RefillRequest requestRefill(Long prescriptionId, String note) {
        accessGuard.requireRole(Role.PATIENT);
        Prescription prescription = em.find(Prescription.class, prescriptionId);
        if (prescription == null) {
            throw new RecordNotFoundException("Prescription not found.");
        }
        AccessDecision decision = accessGuard.requirePrescriptionAccess(prescription);
        if (prescription.getStatus() != PrescriptionStatus.ACTIVE) {
            throw new BusinessRuleException(
                    "Only an active prescription can be refilled. Please message the clinic.");
        }
        boolean alreadyOpen = em.createQuery(
                        "SELECT COUNT(r) FROM RefillRequest r WHERE r.prescription.id = :rxId "
                                + "AND r.status IN (:open)", Long.class)
                .setParameter("rxId", prescriptionId)
                .setParameter("open", List.of(RefillStatus.REQUESTED, RefillStatus.NEEDS_INFO))
                .getSingleResult() > 0;
        if (alreadyOpen) {
            throw new BusinessRuleException("A refill request for this medication is already open.");
        }

        RefillRequest request = new RefillRequest();
        request.setPrescription(prescription);
        request.setPatient(prescription.getPatient());
        request.setStatus(RefillStatus.REQUESTED);
        request.setPatientNote(Validations.optionalText(note, "Note", 1000));
        request.setRequestedAt(LocalDateTime.now());
        em.persist(request);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.REFILL_REQUESTED, "refill_request")
                .entity(request.getId())
                .patient(request.getPatient().getId())
                .after("status=requested; medication=" + prescription.getMedicationName())
                .basis(decision.basis()));

        if (prescription.getProvider().getUser() != null) {
            notificationService.notifyUser(prescription.getProvider().getUser(),
                    "refill.requested", "Refill request",
                    prescription.getPatient().getFullName() + " requested "
                            + prescription.getMedicationName(),
                    "/clinician/refill-queue.xhtml");
        }
        return request;
    }

    /** A patient may withdraw their own open request. */
    @RolesAllowed(Role.PATIENT_ROLE)
    public RefillRequest cancelRefill(Long requestId) {
        accessGuard.requireRole(Role.PATIENT);
        RefillRequest request = em.find(RefillRequest.class, requestId);
        AccessDecision decision = accessGuard.requireRefillAccess(request);
        return applyDecision(request, RefillStatus.CANCELLED, null, decision);
    }

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE})
    public List<RefillRequest> openQueue() {
        accessGuard.requireRole(Role.CLINIC_ADMIN, Role.CLINICIAN);
        if (currentUser.getRole() == Role.CLINICIAN) {
            Provider provider = currentUser.getProvider();
            if (provider == null) {
                return List.of();
            }
            return em.createQuery(
                            "SELECT r FROM RefillRequest r WHERE r.status IN (:open) "
                                    + "AND r.prescription.provider.id = :providerId "
                                    + "ORDER BY r.requestedAt", RefillRequest.class)
                    .setParameter("open", List.of(RefillStatus.REQUESTED, RefillStatus.NEEDS_INFO))
                    .setParameter("providerId", provider.getId())
                    .setMaxResults(200)
                    .getResultList();
        }
        return em.createNamedQuery("RefillRequest.open", RefillRequest.class)
                .setMaxResults(200)
                .getResultList();
    }

    @PermitAll
    public List<RefillRequest> refillsForPatient(Long patientId) {
        accessGuard.requireClinicalPatientAccess(patientId);
        return em.createNamedQuery("RefillRequest.byPatient", RefillRequest.class)
                .setParameter("patientId", patientId)
                .getResultList();
    }

    /** Approve, deny or ask for more information. Clinicians only. */
    @RolesAllowed(Role.CLINICIAN_ROLE)
    public RefillRequest decide(Long requestId, RefillStatus target, String staffNote) {
        accessGuard.requireClinician();
        RefillRequest request = em.find(RefillRequest.class, requestId);
        AccessDecision decision = accessGuard.requireRefillAccess(request);
        if (target == RefillStatus.DENIED || target == RefillStatus.NEEDS_INFO) {
            Validations.requireText(staffNote, "A note", 1000);
        }
        return applyDecision(request, target, staffNote, decision);
    }

    private RefillRequest applyDecision(RefillRequest request, RefillStatus target,
                                        String staffNote, AccessDecision decision) {
        RefillStatus current = request.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new BusinessRuleException("A " + current.getLabel().toLowerCase()
                    + " request cannot become " + target.getLabel().toLowerCase() + ".");
        }
        request.setStatus(target);
        request.setStaffNote(Validations.optionalText(staffNote, "Note", 1000));
        request.setDecidedAt(LocalDateTime.now());
        request.setDecidedBy(currentUser.getUser());

        if (target == RefillStatus.APPROVED) {
            Prescription prescription = request.getPrescription();
            prescription.setRefillsRemaining(prescription.getRefillsRemaining() + 1);
            prescription.setUpdatedAt(LocalDateTime.now());
            request.setCollectionReference(
                    String.format("%06d", new java.util.Random().nextInt(1_000_000)));
        }

        auditService.record(AuditEntry.of(AuditAction.REFILL_DECIDED, "refill_request")
                .entity(request.getId())
                .patient(request.getPatient().getId())
                .before("status=" + current.getCode())
                .after("status=" + target.getCode())
                .detail(staffNote)
                .basis(decision.basis()));

        if (request.getPatient().getUser() != null && target != RefillStatus.CANCELLED) {
            notificationService.notifyUser(request.getPatient().getUser(), "refill.decided",
                    "Refill " + target.getLabel().toLowerCase(),
                    request.getPrescription().getMedicationName(),
                    "/patient/prescriptions.xhtml");
        }
        integrationService.queueWebhook("refill.decided", request);
        return request;
    }

    /**
     * Releases the dispensing details once the caller quotes the collection
     * reference issued with the approval.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public RefillRequest releaseForCollection(Long requestId, String reference) {
        accessGuard.requireStaff();
        RefillRequest request = em.find(RefillRequest.class, requestId);
        if (request == null) {
            throw new RecordNotFoundException("Refill request not found.");
        }
        String expected = request.getCollectionReference();
        if (expected == null || reference == null || !expected.equals(reference.trim())) {
            throw new BusinessRuleException("That collection reference is not recognised.");
        }
        return request;
    }

    /**
     * Forwards an approved refill to the partner pharmacy's dispensing bridge.
     * The clinician quotes the pharmacy's access code so the gateway can
     * authorise the hand-over.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE})
    public String dispatchToPharmacy(Long requestId, String pharmacyAccessCode) {
        accessGuard.requireRole(Role.CLINIC_ADMIN, Role.CLINICIAN);
        RefillRequest request = em.find(RefillRequest.class, requestId);
        if (request == null) {
            throw new RecordNotFoundException("Refill request not found.");
        }
        String outcome = integrationService.submitDispenseInstruction(
                pharmacyAccessCode, request);
        auditService.record(AuditEntry.of(AuditAction.EXPORT_GENERATED, "refill_request")
                .entity(request.getId())
                .patient(request.getPatient().getId())
                .detail("pharmacy=" + outcome)
                .basis("role:" + currentUser.getRole().getCode()));
        return outcome;
    }

    /**
     * Evaluates the weight-based dispensing formula a clinician keeps for a
     * medication, so the dose can be checked against the chart.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE})
    public Object evaluateDoseFormula(String expression) {
        accessGuard.requireClinician();
        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("weightKg", 70.0);
        variables.put("unitsPerKg", 0.1);
        org.springframework.expression.spel.support.StandardEvaluationContext context =
                new org.springframework.expression.spel.support.StandardEvaluationContext();
        variables.forEach(context::setVariable);
        return new org.springframework.expression.spel.standard.SpelExpressionParser().parseExpression(expression).getValue(context);
    }

    public long countOpenRefills() {
        return em.createQuery(
                        "SELECT COUNT(r) FROM RefillRequest r WHERE r.status IN (:open)", Long.class)
                .setParameter("open", List.of(RefillStatus.REQUESTED, RefillStatus.NEEDS_INFO))
                .getSingleResult();
    }

    static String describe(Prescription p) {
        return "medication=" + p.getMedicationName()
                + "; dosage=" + p.getDosage()
                + "; quantity=" + p.getQuantity()
                + "; refills=" + p.getRefillsRemaining()
                + "; status=" + p.getStatus().getCode();
    }
}
