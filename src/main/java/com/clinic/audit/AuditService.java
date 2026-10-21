package com.clinic.audit;

import com.clinic.domain.AppUser;
import com.clinic.domain.AuditEvent;
import com.clinic.security.CurrentUser;

import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;

/**
 * Append-only audit writer.
 *
 * <p>Every state transition and every privileged action records the actor, the
 * action, the before/after value, the timestamp and the authorisation basis on
 * which the action was permitted. The table is protected by database triggers
 * that reject any UPDATE or DELETE, and no service, screen, endpoint or export
 * in this application offers a mutation path for an audit row.</p>
 *
 * <p>Read auditing is one event per request naming the resource class and the
 * number of records returned — never one event per record.</p>
 */
@Stateless
@PermitAll
public class AuditService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private CurrentUser currentUser;

    /**
     * Records an event in the caller's transaction, so an audited state change
     * and its audit row commit or roll back together.
     */
    public void record(AuditEntry entry) {
        persist(entry);
    }

    /**
     * Records an event in its own transaction. Used for access denials and read
     * auditing, which must survive a rollback of the attempted operation.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void recordIndependently(AuditEntry entry) {
        persist(entry);
    }

    /** Convenience for a read of a collection: one event, with the row count. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void recordRead(String action, String entityType, Long patientId,
                           int recordCount, String authBasis) {
        persist(AuditEntry.of(action, entityType)
                .patient(patientId)
                .detail("returned=" + recordCount)
                .basis(authBasis));
    }

    private void persist(AuditEntry entry) {
        AuditEvent event = new AuditEvent();
        event.setOccurredAt(LocalDateTime.now());
        AppUser actor = currentUser.getUser();
        if (actor != null) {
            event.setActorUserId(actor.getId());
            event.setActorEmail(actor.getEmail());
            event.setActorRole(actor.getRole() == null ? null : actor.getRole().getCode());
        } else {
            event.setActorEmail(entry.getAnonymousActor());
            event.setActorRole("anonymous");
        }
        event.setAction(entry.getAction());
        event.setEntityType(entry.getEntityType());
        event.setEntityId(entry.getEntityId());
        event.setPatientId(entry.getPatientId());
        event.setBeforeValue(truncate(entry.getBeforeValue(), 4000));
        event.setAfterValue(truncate(entry.getAfterValue(), 4000));
        event.setAuthBasis(truncate(entry.getAuthBasis(), 200));
        event.setDetail(truncate(entry.getDetail(), 600));
        event.setIpAddress(currentUser.getClientIp());
        em.persist(event);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
