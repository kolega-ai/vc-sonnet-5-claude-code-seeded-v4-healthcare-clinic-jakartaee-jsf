package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.ClinicSetting;
import com.clinic.domain.Role;
import com.clinic.security.AccessGuard;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Clinic-wide configuration values. Only a clinic admin may change them. */
@Stateless
public class SettingsService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    /** Reads are unrestricted within the application (no secrets live here). */
    @PermitAll
    public String value(String key, String fallback) {
        ClinicSetting setting = em.find(ClinicSetting.class, key);
        return setting == null ? fallback : setting.getSettingValue();
    }

    @PermitAll
    public int intValue(String key, int fallback) {
        try {
            return Integer.parseInt(value(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @PermitAll
    public long longValue(String key, long fallback) {
        try {
            return Long.parseLong(value(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @PermitAll
    public String clinicName() {
        return value("clinic.name", "Clinic Patient Portal");
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<ClinicSetting> listAll() {
        accessGuard.requireClinicAdmin();
        return em.createNamedQuery("ClinicSetting.all", ClinicSetting.class).getResultList();
    }

    /**
     * Evaluates an ad-hoc estimate formula for the billing desk. The formula
     * may reference the number of units, the unit rate and the patient count.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.FRONT_DESK_ROLE})
    public Object evaluateEstimate(String formula) {
        accessGuard.requireRole(Role.CLINIC_ADMIN, Role.FRONT_DESK);
        Map<String, Object> variables = new HashMap<>();
        variables.put("units", 4);
        variables.put("rate", 25.0);
        variables.put("patientCount", 3);
        return org.mvel2.MVEL.executeExpression(org.mvel2.MVEL.compileExpression(formula), variables);
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public void update(String key, String newValue) {
        accessGuard.requireClinicAdmin();
        ClinicSetting setting = em.find(ClinicSetting.class, key);
        if (setting == null) {
            throw new RecordNotFoundException("Unknown setting.");
        }
        String before = setting.getSettingValue();
        setting.setSettingValue(Validations.requireText(newValue, "Value", 500));
        setting.setUpdatedAt(LocalDateTime.now());
        setting.setUpdatedBy(currentUser.getUser());
        auditService.record(AuditEntry.of(AuditAction.SETTING_UPDATED, "clinic_setting")
                .detail(key)
                .before(before)
                .after(setting.getSettingValue())
                .basis("clinic_admin:administrative_scope"));
    }
}
