package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.Appointment;
import com.clinic.domain.AppointmentStatus;
import com.clinic.domain.AppointmentType;
import com.clinic.domain.Patient;
import com.clinic.domain.Provider;
import com.clinic.domain.ProviderSchedule;
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
import jakarta.persistence.TypedQuery;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduling: availability, booking, rescheduling and the appointment state
 * machine. Every transition is validated against
 * {@link AppointmentStatus#canTransitionTo} and audited with before/after.
 */
@Stateless
public class AppointmentService {

    private static final int DEFAULT_HORIZON_DAYS = 21;

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

    @Inject
    private SettingsService settingsService;

    // ---------------------------------------------------------- availability

    /**
     * Computes open slots from the provider's weekly template minus anything
     * already booked. Used by both the booking screen and the JSON API.
     */
    @PermitAll
    public List<AvailabilitySlot> availability(Long providerId, Long appointmentTypeId,
                                               LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start.plusDays(DEFAULT_HORIZON_DAYS) : to;
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The end date must not be before the start date.");
        }
        if (Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() > 90) {
            throw new BusinessRuleException("Availability may be requested for at most 90 days.");
        }

        int duration = 30;
        if (appointmentTypeId != null) {
            AppointmentType type = em.find(AppointmentType.class, appointmentTypeId);
            if (type == null || !type.isActive()) {
                throw new RecordNotFoundException("Appointment type not found.");
            }
            duration = type.getDurationMinutes();
        }

        List<Provider> providers = new ArrayList<>();
        if (providerId != null) {
            Provider provider = em.find(Provider.class, providerId);
            if (provider == null || !provider.isActive()) {
                throw new RecordNotFoundException("Provider not found.");
            }
            providers.add(provider);
        } else {
            providers.addAll(em.createNamedQuery("Provider.active", Provider.class).getResultList());
        }

        LocalDateTime windowStart = start.atStartOfDay();
        LocalDateTime windowEnd = end.plusDays(1).atStartOfDay();
        LocalDateTime notBefore = LocalDateTime.now()
                .plusHours(settingsService.intValue("booking.min_hours_notice", 12));

        List<AvailabilitySlot> slots = new ArrayList<>();
        for (Provider provider : providers) {
            List<ProviderSchedule> template =
                    em.createNamedQuery("ProviderSchedule.byProvider", ProviderSchedule.class)
                            .setParameter("providerId", provider.getId())
                            .getResultList();
            if (template.isEmpty()) {
                continue;
            }
            List<Appointment> booked = em.createQuery(
                            "SELECT a FROM Appointment a WHERE a.provider.id = :providerId "
                                    + "AND a.startAt < :windowEnd AND a.endAt > :windowStart "
                                    + "AND a.status IN (:active) ORDER BY a.startAt", Appointment.class)
                    .setParameter("providerId", provider.getId())
                    .setParameter("windowStart", windowStart)
                    .setParameter("windowEnd", windowEnd)
                    .setParameter("active", List.of(AppointmentStatus.REQUESTED,
                            AppointmentStatus.SCHEDULED, AppointmentStatus.CHECKED_IN))
                    .getResultList();

            for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
                int dow = day.getDayOfWeek().getValue();
                for (ProviderSchedule block : template) {
                    if (block.getDayOfWeek() != dow) {
                        continue;
                    }
                    LocalDateTime cursor = day.atTime(block.getStartTime());
                    LocalDateTime blockEnd = day.atTime(block.getEndTime());
                    while (!cursor.plusMinutes(duration).isAfter(blockEnd)) {
                        LocalDateTime slotEnd = cursor.plusMinutes(duration);
                        if (!cursor.isBefore(notBefore) && !overlaps(booked, cursor, slotEnd)) {
                            slots.add(new AvailabilitySlot(provider.getId(),
                                    provider.getDisplayName(), cursor, slotEnd));
                        }
                        cursor = cursor.plusMinutes(block.getSlotMinutes());
                    }
                }
            }
        }
        slots.sort((a, b) -> a.start().compareTo(b.start()));
        return slots.size() > 500 ? slots.subList(0, 500) : slots;
    }

    private static boolean overlaps(List<Appointment> booked, LocalDateTime start, LocalDateTime end) {
        for (Appointment a : booked) {
            if (a.getStartAt().isBefore(end) && a.getEndAt().isAfter(start)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------- reading

    @PermitAll
    public Appointment require(Long appointmentId) {
        Appointment appointment = em.find(Appointment.class, appointmentId);
        if (appointment == null) {
            throw new RecordNotFoundException("Appointment not found.");
        }
        // Ownership/scope resolved by the shared guard, one frame above.
        accessGuard.requirePatientAccess(appointment.getPatient().getId());
        return appointment;
    }

    @PermitAll
    public List<Appointment> forPatient(Long patientId) {
        accessGuard.requirePatientAccess(patientId);
        return em.createNamedQuery("Appointment.byPatient", Appointment.class)
                .setParameter("patientId", patientId)
                .getResultList();
    }

    @PermitAll
    public List<Appointment> upcomingForPatient(Long patientId, int limit) {
        accessGuard.requirePatientAccess(patientId);
        return em.createQuery(
                        "SELECT a FROM Appointment a WHERE a.patient.id = :patientId "
                                + "AND a.startAt >= :now AND a.status IN (:active) "
                                + "ORDER BY a.startAt", Appointment.class)
                .setParameter("patientId", patientId)
                .setParameter("now", LocalDateTime.now().minusHours(2))
                .setParameter("active", List.of(AppointmentStatus.REQUESTED,
                        AppointmentStatus.SCHEDULED, AppointmentStatus.CHECKED_IN))
                .setMaxResults(Math.min(Math.max(limit, 1), 50))
                .getResultList();
    }

    /** The staff schedule board. Clinicians see their own calendar by default. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public List<Appointment> board(LocalDate day, Long providerId, AppointmentStatus status,
                                   String text) {
        accessGuard.requireStaff();
        LocalDate target = day == null ? LocalDate.now() : day;

        StringBuilder jpql = new StringBuilder(
                "SELECT a FROM Appointment a WHERE a.startAt >= :from AND a.startAt < :to");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", target.atStartOfDay());
        params.put("to", target.plusDays(1).atStartOfDay());

        Long effectiveProvider = providerId;
        if (currentUser.getRole() == Role.CLINICIAN) {
            Provider provider = currentUser.getProvider();
            if (provider == null) {
                return List.of();
            }
            effectiveProvider = provider.getId();
        }
        if (effectiveProvider != null) {
            jpql.append(" AND a.provider.id = :providerId");
            params.put("providerId", effectiveProvider);
        }
        if (status != null) {
            jpql.append(" AND a.status = :status");
            params.put("status", status);
        }
        if (text != null && !text.isBlank()) {
            jpql.append(" AND (lower(a.patient.lastName) LIKE :text")
                    .append(" OR lower(a.patient.firstName) LIKE :text")
                    .append(" OR lower(a.patient.mrn) LIKE :text)");
            params.put("text", "%" + text.trim().toLowerCase() + "%");
        }
        jpql.append(" ORDER BY a.startAt, a.provider.lastName");

        TypedQuery<Appointment> query = em.createQuery(jpql.toString(), Appointment.class);
        params.forEach(query::setParameter);
        query.setMaxResults(300);
        List<Appointment> results = query.getResultList();
        auditService.recordRead(AuditAction.PATIENT_LIST_VIEWED, "appointment", null,
                results.size(), "role:" + currentUser.getRole().getCode());
        return results;
    }

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public List<Appointment> range(LocalDate from, LocalDate to, Long providerId) {
        accessGuard.requireStaff();
        StringBuilder jpql = new StringBuilder(
                "SELECT a FROM Appointment a WHERE a.startAt >= :from AND a.startAt < :to");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", from.atStartOfDay());
        params.put("to", to.plusDays(1).atStartOfDay());
        if (providerId != null) {
            jpql.append(" AND a.provider.id = :providerId");
            params.put("providerId", providerId);
        }
        jpql.append(" ORDER BY a.startAt");
        TypedQuery<Appointment> query = em.createQuery(jpql.toString(), Appointment.class);
        params.forEach(query::setParameter);
        query.setMaxResults(2000);
        return query.getResultList();
    }

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public List<Appointment> queueAwaitingConfirmation() {
        accessGuard.requireStaff();
        return em.createQuery(
                        "SELECT a FROM Appointment a WHERE a.status = :status "
                                + "ORDER BY a.startAt", Appointment.class)
                .setParameter("status", AppointmentStatus.REQUESTED)
                .setMaxResults(100)
                .getResultList();
    }

    // --------------------------------------------------------------- booking

    /**
     * Patient self-booking. The appointment lands in {@code requested} and must
     * be confirmed by staff before it becomes {@code scheduled}.
     */
    @RolesAllowed(Role.PATIENT_ROLE)
    public Appointment requestAppointment(Long providerId, Long typeId,
                                          LocalDateTime start, String reason) {
        accessGuard.requireRole(Role.PATIENT);
        Long patientId = currentUser.getPatientId();
        if (patientId == null) {
            throw new BusinessRuleException("This account is not linked to a patient record.");
        }
        AppointmentType type = em.find(AppointmentType.class, typeId);
        if (type == null || !type.isActive()) {
            throw new RecordNotFoundException("Appointment type not found.");
        }
        if (!type.isPatientBookable()) {
            throw new BusinessRuleException(
                    "That appointment type must be arranged by the clinic. Please send a message instead.");
        }
        int maxDays = settingsService.intValue("booking.max_days_ahead", 60);
        if (start == null || start.isAfter(LocalDateTime.now().plusDays(maxDays))) {
            throw new BusinessRuleException(
                    "Choose a time within the next " + maxDays + " days.");
        }
        return createInternal(patientId, providerId, type, start,
                Validations.requireText(reason, "Reason for visit", 500),
                AppointmentStatus.REQUESTED, "patient:self");
    }

    /** Staff-created booking goes straight to {@code scheduled}. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public Appointment scheduleForPatient(Long patientId, Long providerId, Long typeId,
                                          LocalDateTime start, String reason) {
        accessGuard.requireSchedulingStaff();
        AccessDecision decision = accessGuard.requirePatientAccess(patientId);
        AppointmentType type = em.find(AppointmentType.class, typeId);
        if (type == null || !type.isActive()) {
            throw new RecordNotFoundException("Appointment type not found.");
        }
        return createInternal(patientId, providerId, type, start,
                Validations.requireText(reason, "Reason for visit", 500),
                AppointmentStatus.SCHEDULED, decision.basis());
    }

    private Appointment createInternal(Long patientId, Long providerId, AppointmentType type,
                                       LocalDateTime start, String reason,
                                       AppointmentStatus initialStatus, String basis) {
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
        Provider provider = em.find(Provider.class, providerId);
        if (provider == null || !provider.isActive()) {
            throw new RecordNotFoundException("Provider not found.");
        }
        if (start == null) {
            throw new BusinessRuleException("Choose an appointment time.");
        }
        if (start.isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("Appointments cannot be booked in the past.");
        }
        LocalDateTime end = start.plusMinutes(type.getDurationMinutes());
        requireSlotFree(provider.getId(), start, end, null);

        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setProvider(provider);
        appointment.setAppointmentType(type);
        appointment.setStartAt(start);
        appointment.setEndAt(end);
        appointment.setStatus(initialStatus);
        appointment.setReason(reason);
        appointment.setCreatedBy(currentUser.getUser());
        em.persist(appointment);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.APPOINTMENT_CREATED, "appointment")
                .entity(appointment.getId())
                .patient(patient.getId())
                .after(describe(appointment))
                .basis(basis));

        if (initialStatus == AppointmentStatus.REQUESTED) {
            notificationService.notifyRole(Role.FRONT_DESK, "appointment.requested",
                    "New appointment request",
                    patient.getFullName() + " requested " + type.getName() + " with "
                            + provider.getDisplayName(),
                    "/staff/schedule.xhtml");
        } else if (patient.getUser() != null) {
            notificationService.notifyUser(patient.getUser(), "appointment.scheduled",
                    "Appointment scheduled",
                    type.getName() + " with " + provider.getDisplayName() + " on "
                            + start.toLocalDate(),
                    "/patient/appointments.xhtml");
        }
        integrationService.queueWebhook("appointment.status_changed", appointment);
        return appointment;
    }

    private void requireSlotFree(Long providerId, LocalDateTime start, LocalDateTime end,
                                 Long excludeAppointmentId) {
        StringBuilder jpql = new StringBuilder(
                "SELECT COUNT(a) FROM Appointment a WHERE a.provider.id = :providerId "
                        + "AND a.startAt < :end AND a.endAt > :start AND a.status IN (:active)");
        if (excludeAppointmentId != null) {
            jpql.append(" AND a.id <> :excludeId");
        }
        var query = em.createQuery(jpql.toString(), Long.class)
                .setParameter("providerId", providerId)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("active", List.of(AppointmentStatus.REQUESTED,
                        AppointmentStatus.SCHEDULED, AppointmentStatus.CHECKED_IN));
        if (excludeAppointmentId != null) {
            query.setParameter("excludeId", excludeAppointmentId);
        }
        if (query.getSingleResult() > 0) {
            throw new BusinessRuleException(
                    "That time is no longer available. Please choose another slot.");
        }
    }

    // ------------------------------------------------------- state machine

    /**
     * Applies a status transition, enforcing the edge list server-side. The
     * caller's role also constrains which edges it may request.
     */
    @PermitAll
    public Appointment transition(Long appointmentId, AppointmentStatus target, String note) {
        Appointment appointment = em.find(Appointment.class, appointmentId);
        if (appointment == null) {
            throw new RecordNotFoundException("Appointment not found.");
        }
        AccessDecision decision = accessGuard.requirePatientAccess(appointment.getPatient().getId());
        AppointmentStatus current = appointment.getStatus();

        if (!current.canTransitionTo(target)) {
            throw new BusinessRuleException("An appointment that is " + current.getLabel().toLowerCase()
                    + " cannot become " + target.getLabel().toLowerCase() + ".");
        }
        requireTransitionAllowedForRole(current, target);

        appointment.setStatus(target);
        appointment.setUpdatedAt(LocalDateTime.now());
        switch (target) {
            case CHECKED_IN -> appointment.setCheckedInAt(LocalDateTime.now());
            case COMPLETED -> appointment.setCompletedAt(LocalDateTime.now());
            case CANCELLED -> appointment.setCancelReason(
                    Validations.requireText(note, "Cancellation reason", 400));
            case NO_SHOW -> appointment.setCancelReason(
                    Validations.optionalText(note, "Note", 400));
            default -> { /* no side effect */ }
        }

        auditService.record(AuditEntry.of(AuditAction.APPOINTMENT_STATUS_CHANGED, "appointment")
                .entity(appointment.getId())
                .patient(appointment.getPatient().getId())
                .before("status=" + current.getCode())
                .after("status=" + target.getCode())
                .detail(note)
                .basis(decision.basis()));

        if (appointment.getPatient().getUser() != null) {
            notificationService.notifyUser(appointment.getPatient().getUser(),
                    "appointment.status", "Appointment " + target.getLabel().toLowerCase(),
                    appointment.getAppointmentType().getName() + " on "
                            + appointment.getStartAt().toLocalDate(),
                    "/patient/appointments.xhtml");
        }
        integrationService.queueWebhook("appointment.status_changed", appointment);
        return appointment;
    }

    private void requireTransitionAllowedForRole(AppointmentStatus from, AppointmentStatus to) {
        Role role = currentUser.getRole();
        if (role == Role.PATIENT) {
            // A patient may only withdraw their own booking, and only in time.
            if (to != AppointmentStatus.CANCELLED) {
                throw new BusinessRuleException(
                        "Please contact the clinic to change this appointment.");
            }
            if (from == AppointmentStatus.CHECKED_IN) {
                throw new BusinessRuleException(
                        "You are already checked in. Please speak to the front desk.");
            }
        } else if (role == Role.FRONT_DESK && to == AppointmentStatus.COMPLETED) {
            throw new BusinessRuleException(
                    "Only the treating clinician may mark a visit complete.");
        }
    }

    /**
     * Patient-initiated cancellation, subject to the notice window from clinic
     * settings.
     */
    @RolesAllowed(Role.PATIENT_ROLE)
    public Appointment cancelAsPatient(Long appointmentId, String reason) {
        accessGuard.requireRole(Role.PATIENT);
        Appointment appointment = em.find(Appointment.class, appointmentId);
        if (appointment == null) {
            throw new RecordNotFoundException("Appointment not found.");
        }
        accessGuard.requirePatientAccess(appointment.getPatient().getId());
        int minHours = settingsService.intValue("booking.cancel_min_hours", 4);
        if (appointment.getStartAt().isBefore(LocalDateTime.now().plusHours(minHours))) {
            throw new BusinessRuleException("Appointments starting within " + minHours
                    + " hours must be cancelled by phone.");
        }
        return transition(appointmentId, AppointmentStatus.CANCELLED, reason);
    }

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public Appointment reschedule(Long appointmentId, Long providerId, LocalDateTime newStart) {
        accessGuard.requireSchedulingStaff();
        Appointment appointment = em.find(Appointment.class, appointmentId);
        if (appointment == null) {
            throw new RecordNotFoundException("Appointment not found.");
        }
        AccessDecision decision = accessGuard.requirePatientAccess(appointment.getPatient().getId());
        if (appointment.getStatus().isTerminal()) {
            throw new BusinessRuleException("A "
                    + appointment.getStatus().getLabel().toLowerCase()
                    + " appointment cannot be rescheduled.");
        }
        if (newStart == null || newStart.isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("Choose a future time.");
        }
        Provider provider = providerId == null
                ? appointment.getProvider() : em.find(Provider.class, providerId);
        if (provider == null || !provider.isActive()) {
            throw new RecordNotFoundException("Provider not found.");
        }
        String before = describe(appointment);
        LocalDateTime newEnd =
                newStart.plusMinutes(appointment.getAppointmentType().getDurationMinutes());
        requireSlotFree(provider.getId(), newStart, newEnd, appointment.getId());

        appointment.setProvider(provider);
        appointment.setStartAt(newStart);
        appointment.setEndAt(newEnd);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setUpdatedAt(LocalDateTime.now());

        auditService.record(AuditEntry.of(AuditAction.APPOINTMENT_RESCHEDULED, "appointment")
                .entity(appointment.getId())
                .patient(appointment.getPatient().getId())
                .before(before)
                .after(describe(appointment))
                .basis(decision.basis()));

        if (appointment.getPatient().getUser() != null) {
            notificationService.notifyUser(appointment.getPatient().getUser(),
                    "appointment.rescheduled", "Appointment rescheduled",
                    "Now " + newStart.toLocalDate() + " at " + newStart.toLocalTime()
                            + " with " + provider.getDisplayName(),
                    "/patient/appointments.xhtml");
        }
        integrationService.queueWebhook("appointment.status_changed", appointment);
        return appointment;
    }

    /** Internal scheduling notes. Visible to staff only, never to the patient. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public void updateStaffNotes(Long appointmentId, String notes) {
        accessGuard.requireStaff();
        Appointment appointment = em.find(Appointment.class, appointmentId);
        if (appointment == null) {
            throw new RecordNotFoundException("Appointment not found.");
        }
        AccessDecision decision = accessGuard.requirePatientAccess(appointment.getPatient().getId());
        String before = appointment.getStaffNotes();
        appointment.setStaffNotes(Validations.optionalText(notes, "Notes", 4000));
        appointment.setUpdatedAt(LocalDateTime.now());
        auditService.record(AuditEntry.of(AuditAction.APPOINTMENT_NOTES_UPDATED, "appointment")
                .entity(appointment.getId())
                .patient(appointment.getPatient().getId())
                .before(before)
                .after(appointment.getStaffNotes())
                .basis(decision.basis()));
    }

    // ------------------------------------------------------------- reporting

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public Map<String, Long> statusCountsForDay(LocalDate day) {
        accessGuard.requireStaff();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AppointmentStatus status : AppointmentStatus.values()) {
            Long count = em.createQuery(
                            "SELECT COUNT(a) FROM Appointment a WHERE a.status = :status "
                                    + "AND a.startAt >= :from AND a.startAt < :to", Long.class)
                    .setParameter("status", status)
                    .setParameter("from", day.atStartOfDay())
                    .setParameter("to", day.plusDays(1).atStartOfDay())
                    .getSingleResult();
            counts.put(status.getLabel(), count);
        }
        return counts;
    }

    static String describe(Appointment a) {
        return "patient=" + a.getPatient().getMrn()
                + "; provider=" + a.getProvider().getFullName()
                + "; type=" + a.getAppointmentType().getCode()
                + "; start=" + a.getStartAt()
                + "; status=" + a.getStatus().getCode();
    }
}
