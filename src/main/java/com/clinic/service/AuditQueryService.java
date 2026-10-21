package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditService;
import com.clinic.domain.AuditEvent;
import com.clinic.domain.Role;
import com.clinic.security.AccessDecision;
import com.clinic.security.AccessGuard;
import com.clinic.security.CurrentUser;
import com.clinic.util.SortWhitelist;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to the audit trail.
 *
 * <p>There is intentionally no update or delete path here, nor anywhere else in
 * the application: {@code audit_event} is append-only and the database rejects
 * mutation with a trigger.</p>
 */
@Stateless
public class AuditQueryService {

    private static final SortWhitelist AUDIT_SORT = SortWhitelist.builder("newest")
            .allow("newest", "a.occurredAt DESC")
            .allow("oldest", "a.occurredAt ASC")
            .allow("action", "a.action ASC, a.occurredAt DESC")
            .allow("actor", "a.actorEmail ASC, a.occurredAt DESC")
            .build();

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    /** The clinic-wide audit log viewer. */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<AuditEvent> search(String action, String actorEmail, Long patientId,
                                   LocalDate from, LocalDate to, String sortKey, int limit) {
        accessGuard.requireClinicAdmin();
        StringBuilder jpql = new StringBuilder("SELECT a FROM AuditEvent a WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (action != null && !action.isBlank()) {
            jpql.append(" AND a.action = :action");
            params.put("action", action.trim());
        }
        if (actorEmail != null && !actorEmail.isBlank()) {
            jpql.append(" AND lower(a.actorEmail) LIKE :actor");
            params.put("actor", "%" + actorEmail.trim().toLowerCase() + "%");
        }
        if (patientId != null) {
            jpql.append(" AND a.patientId = :patientId");
            params.put("patientId", patientId);
        }
        if (from != null) {
            jpql.append(" AND a.occurredAt >= :from");
            params.put("from", from.atStartOfDay());
        }
        if (to != null) {
            jpql.append(" AND a.occurredAt < :to");
            params.put("to", to.plusDays(1).atStartOfDay());
        }
        // Whitelisted constant, never caller-supplied text.
        jpql.append(" ORDER BY ").append(AUDIT_SORT.orderByFragment(sortKey));

        TypedQuery<AuditEvent> query = em.createQuery(jpql.toString(), AuditEvent.class);
        params.forEach(query::setParameter);
        query.setMaxResults(Math.min(Math.max(limit, 1), 1000));
        List<AuditEvent> results = query.getResultList();

        auditService.recordRead(AuditAction.AUDIT_LOG_VIEWED, "audit_event", patientId,
                results.size(), "clinic_admin:administrative_scope");
        return results;
    }

    /**
     * The access history shown on a patient chart. Scoped to that patient and
     * available to anyone already entitled to the chart.
     */
    @PermitAll
    public List<AuditEvent> forPatient(Long patientId, int limit) {
        AccessDecision decision = accessGuard.requirePatientAccess(patientId);
        List<AuditEvent> results = em.createNamedQuery("AuditEvent.byPatient", AuditEvent.class)
                .setParameter("patientId", patientId)
                .setMaxResults(Math.min(Math.max(limit, 1), 500))
                .getResultList();
        auditService.recordRead(AuditAction.AUDIT_LOG_VIEWED, "audit_event", patientId,
                results.size(), decision.basis());
        return results;
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<String> distinctActions() {
        accessGuard.requireClinicAdmin();
        return em.createQuery(
                        "SELECT DISTINCT a.action FROM AuditEvent a ORDER BY a.action", String.class)
                .setMaxResults(200)
                .getResultList();
    }

    public List<String> sortKeys() {
        return List.copyOf(AUDIT_SORT.keys());
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<AuditEvent> recent(int limit) {
        accessGuard.requireClinicAdmin();
        return em.createNamedQuery("AuditEvent.recent", AuditEvent.class)
                .setMaxResults(Math.min(Math.max(limit, 1), 200))
                .getResultList();
    }

    public long countToday() {
        if (currentUser.getRole() != Role.CLINIC_ADMIN) {
            return 0;
        }
        return em.createQuery(
                        "SELECT COUNT(a) FROM AuditEvent a WHERE a.occurredAt >= :from", Long.class)
                .setParameter("from", LocalDate.now().atStartOfDay())
                .getSingleResult();
    }
}
