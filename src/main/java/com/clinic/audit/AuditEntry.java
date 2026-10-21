package com.clinic.audit;

/** Fluent description of one audit event, filled in by the calling service. */
public final class AuditEntry {

    private final String action;
    private final String entityType;
    private Long entityId;
    private Long patientId;
    private String beforeValue;
    private String afterValue;
    private String authBasis;
    private String detail;
    private String anonymousActor;

    private AuditEntry(String action, String entityType) {
        this.action = action;
        this.entityType = entityType;
    }

    public static AuditEntry of(String action, String entityType) {
        return new AuditEntry(action, entityType);
    }

    public AuditEntry entity(Long id) {
        this.entityId = id;
        return this;
    }

    public AuditEntry patient(Long id) {
        this.patientId = id;
        return this;
    }

    public AuditEntry before(String value) {
        this.beforeValue = value;
        return this;
    }

    public AuditEntry after(String value) {
        this.afterValue = value;
        return this;
    }

    /** The authorisation rule that permitted the action. */
    public AuditEntry basis(String value) {
        this.authBasis = value;
        return this;
    }

    public AuditEntry detail(String value) {
        this.detail = value;
        return this;
    }

    /** Identifies the subject when no session exists (failed login, reset request). */
    public AuditEntry anonymousActor(String value) {
        this.anonymousActor = value;
        return this;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public Long getPatientId() {
        return patientId;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public String getAuthBasis() {
        return authBasis;
    }

    public String getDetail() {
        return detail;
    }

    public String getAnonymousActor() {
        return anonymousActor;
    }
}
