package com.clinic.web;

import com.clinic.domain.AuditEvent;
import com.clinic.service.AuditQueryService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * The audit log viewer. The screen is deliberately read-only: there is no edit
 * or delete action anywhere, and the table itself rejects mutation.
 */
@Named("adminAudit")
@ViewScoped
public class AdminAuditBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private AuditQueryService auditQueryService;

    private List<AuditEvent> events = List.of();
    private List<String> actions = List.of();

    private String action;
    private String actorEmail;
    private Long patientId;
    private LocalDate from = LocalDate.now().minusDays(30);
    private LocalDate to = LocalDate.now();
    private String sortKey = "newest";
    private int limit = 200;

    @PostConstruct
    public void load() {
        try {
            actions = auditQueryService.distinctActions();
            events = auditQueryService.search(action, actorEmail, patientId, from, to,
                    sortKey, limit);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String search() {
        load();
        return null;
    }

    public String reset() {
        action = null;
        actorEmail = null;
        patientId = null;
        from = LocalDate.now().minusDays(30);
        to = LocalDate.now();
        sortKey = "newest";
        limit = 200;
        load();
        return null;
    }

    public String exportUrl() {
        return Faces.contextPath() + "/api/secure/exports/audit-log.csv?from=" + from
                + "&to=" + to;
    }

    public List<String> getSortKeys() {
        return auditQueryService.sortKeys();
    }

    // ------------------------------------------------------------ accessors

    public List<AuditEvent> getEvents() {
        return events;
    }

    public boolean isEmptyResult() {
        return events.isEmpty();
    }

    public List<String> getActions() {
        return actions;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public void setActorEmail(String actorEmail) {
        this.actorEmail = actorEmail;
    }

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }

    public LocalDate getFrom() {
        return from;
    }

    public void setFrom(LocalDate from) {
        this.from = from;
    }

    public LocalDate getTo() {
        return to;
    }

    public void setTo(LocalDate to) {
        this.to = to;
    }

    public String getSortKey() {
        return sortKey;
    }

    public void setSortKey(String sortKey) {
        this.sortKey = sortKey;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
