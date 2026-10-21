package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.AppointmentType;
import com.clinic.domain.Provider;
import com.clinic.domain.ProviderSchedule;
import com.clinic.domain.Role;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.RecordNotFoundException;
import com.clinic.util.Validations;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalTime;
import java.util.List;

/** Providers, their weekly templates, and the clinic's appointment types. */
@Stateless
public class ProviderService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private AuditService auditService;

    // ------------------------------------------------------------- providers

    /** The provider directory is reference data available to any signed-in user. */
    @PermitAll
    public List<Provider> listActiveProviders() {
        accessGuard.requireAuthenticated();
        return em.createNamedQuery("Provider.active", Provider.class).getResultList();
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<Provider> listAllProviders() {
        accessGuard.requireClinicAdmin();
        return em.createNamedQuery("Provider.all", Provider.class).getResultList();
    }

    @PermitAll
    public Provider requireProvider(Long providerId) {
        accessGuard.requireAuthenticated();
        Provider provider = em.find(Provider.class, providerId);
        if (provider == null) {
            throw new RecordNotFoundException("Provider not found.");
        }
        return provider;
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public Provider saveProvider(Provider form) {
        accessGuard.requireClinicAdmin();
        Provider provider = form.getId() == null ? new Provider() : em.find(Provider.class, form.getId());
        if (provider == null) {
            throw new RecordNotFoundException("Provider not found.");
        }
        String before = provider.getId() == null ? null : describe(provider);

        provider.setFirstName(Validations.requireText(form.getFirstName(), "First name", 80));
        provider.setLastName(Validations.requireText(form.getLastName(), "Last name", 80));
        provider.setSpecialty(Validations.requireText(form.getSpecialty(), "Specialty", 120));
        provider.setCredentials(Validations.optionalText(form.getCredentials(), "Credentials", 40));
        provider.setRoom(Validations.optionalText(form.getRoom(), "Room", 20));
        provider.setNpi(Validations.requireText(form.getNpi(), "NPI", 20));
        provider.setActive(form.isActive());

        if (provider.getId() == null) {
            em.persist(provider);
            em.flush();
        }
        auditService.record(AuditEntry.of(
                        before == null ? AuditAction.USER_CREATED : AuditAction.USER_UPDATED, "provider")
                .entity(provider.getId())
                .before(before)
                .after(describe(provider))
                .basis("clinic_admin:administrative_scope"));
        return provider;
    }

    // ------------------------------------------------------------- schedules

    @PermitAll
    public List<ProviderSchedule> schedulesFor(Long providerId) {
        accessGuard.requireAuthenticated();
        return em.createNamedQuery("ProviderSchedule.byProvider", ProviderSchedule.class)
                .setParameter("providerId", providerId)
                .getResultList();
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public ProviderSchedule addSchedule(Long providerId, int dayOfWeek, LocalTime start,
                                        LocalTime end, int slotMinutes) {
        accessGuard.requireClinicAdmin();
        Provider provider = requireProvider(providerId);
        Validations.require(dayOfWeek >= 1 && dayOfWeek <= 7, "Choose a day of the week.");
        Validations.require(start != null && end != null, "Start and end time are required.");
        Validations.require(end.isAfter(start), "End time must be after the start time.");
        Validations.require(slotMinutes >= 5 && slotMinutes <= 240,
                "Slot length must be between 5 and 240 minutes.");

        ProviderSchedule schedule = new ProviderSchedule();
        schedule.setProvider(provider);
        schedule.setDayOfWeek(dayOfWeek);
        schedule.setStartTime(start);
        schedule.setEndTime(end);
        schedule.setSlotMinutes(slotMinutes);
        schedule.setActive(true);
        em.persist(schedule);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.SETTING_UPDATED, "provider_schedule")
                .entity(schedule.getId())
                .after(provider.getDisplayName() + " " + schedule.getDayName()
                        + " " + start + "-" + end)
                .basis("clinic_admin:administrative_scope"));
        return schedule;
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public void deactivateSchedule(Long scheduleId) {
        accessGuard.requireClinicAdmin();
        ProviderSchedule schedule = em.find(ProviderSchedule.class, scheduleId);
        if (schedule == null) {
            throw new RecordNotFoundException("Schedule block not found.");
        }
        schedule.setActive(false);
        auditService.record(AuditEntry.of(AuditAction.SETTING_UPDATED, "provider_schedule")
                .entity(scheduleId)
                .before("active=true")
                .after("active=false")
                .basis("clinic_admin:administrative_scope"));
    }

    // ------------------------------------------------------ appointment types

    @PermitAll
    public List<AppointmentType> listActiveTypes() {
        accessGuard.requireAuthenticated();
        return em.createNamedQuery("AppointmentType.active", AppointmentType.class).getResultList();
    }

    /** Only the types a patient is allowed to self-book. */
    @PermitAll
    public List<AppointmentType> listBookableTypes() {
        accessGuard.requireAuthenticated();
        return em.createNamedQuery("AppointmentType.bookable", AppointmentType.class).getResultList();
    }

    @PermitAll
    public AppointmentType requireType(Long typeId) {
        accessGuard.requireAuthenticated();
        AppointmentType type = em.find(AppointmentType.class, typeId);
        if (type == null) {
            throw new RecordNotFoundException("Appointment type not found.");
        }
        return type;
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public AppointmentType saveType(AppointmentType form) {
        accessGuard.requireClinicAdmin();
        AppointmentType type = form.getId() == null
                ? new AppointmentType() : em.find(AppointmentType.class, form.getId());
        if (type == null) {
            throw new RecordNotFoundException("Appointment type not found.");
        }
        String before = type.getId() == null ? null : describe(type);

        String code = Validations.requireText(form.getCode(), "Code", 40).toUpperCase();
        if (!code.matches("[A-Z0-9_]{2,40}")) {
            throw new BusinessRuleException("Code may contain A-Z, 0-9 and underscore only.");
        }
        type.setCode(code);
        type.setName(Validations.requireText(form.getName(), "Name", 120));
        Validations.require(form.getDurationMinutes() >= 5 && form.getDurationMinutes() <= 240,
                "Duration must be between 5 and 240 minutes.");
        type.setDurationMinutes(form.getDurationMinutes());
        String colour = Validations.requireText(form.getColor(), "Colour", 16);
        if (!colour.matches("#[0-9a-fA-F]{6}")) {
            throw new BusinessRuleException("Colour must be a hex value such as #2563eb.");
        }
        type.setColor(colour);
        type.setPatientBookable(form.isPatientBookable());
        type.setActive(form.isActive());

        if (type.getId() == null) {
            em.persist(type);
            em.flush();
        }
        auditService.record(AuditEntry.of(AuditAction.SETTING_UPDATED, "appointment_type")
                .entity(type.getId())
                .before(before)
                .after(describe(type))
                .basis("clinic_admin:administrative_scope"));
        return type;
    }

    private static String describe(Provider p) {
        return "name=" + p.getFullName() + "; specialty=" + p.getSpecialty()
                + "; npi=" + p.getNpi() + "; active=" + p.isActive();
    }

    private static String describe(AppointmentType t) {
        return "code=" + t.getCode() + "; name=" + t.getName()
                + "; duration=" + t.getDurationMinutes()
                + "; bookable=" + t.isPatientBookable() + "; active=" + t.isActive();
    }
}
