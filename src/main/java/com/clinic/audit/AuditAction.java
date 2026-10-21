package com.clinic.audit;

/** Canonical audit action names. Kept as constants so screens can filter on them. */
public final class AuditAction {

    public static final String LOGIN_SUCCEEDED = "login.succeeded";
    public static final String LOGIN_FAILED = "login.failed";
    public static final String LOGOUT = "logout";
    public static final String PASSWORD_RESET_REQUESTED = "password.reset_requested";
    public static final String PASSWORD_RESET_COMPLETED = "password.reset_completed";
    public static final String PASSWORD_CHANGED = "password.changed";
    public static final String PROFILE_UPDATED = "profile.updated";

    public static final String USER_CREATED = "user.created";
    public static final String USER_UPDATED = "user.updated";
    public static final String USER_ROLE_CHANGED = "user.role_changed";
    public static final String USER_ENABLED = "user.enabled";
    public static final String USER_DISABLED = "user.disabled";

    public static final String PATIENT_CHART_VIEWED = "patient.chart_viewed";
    public static final String PATIENT_LIST_VIEWED = "patient.list_viewed";
    public static final String PATIENT_CREATED = "patient.created";
    public static final String PATIENT_UPDATED = "patient.updated";
    public static final String PATIENT_IMPORTED = "patient.imported";

    public static final String APPOINTMENT_CREATED = "appointment.created";
    public static final String APPOINTMENT_STATUS_CHANGED = "appointment.status_changed";
    public static final String APPOINTMENT_RESCHEDULED = "appointment.rescheduled";
    public static final String APPOINTMENT_NOTES_UPDATED = "appointment.notes_updated";

    public static final String VISIT_SUMMARY_CREATED = "visit_summary.created";
    public static final String VISIT_SUMMARY_UPDATED = "visit_summary.updated";
    public static final String VISIT_SUMMARY_RELEASED = "visit_summary.released";
    public static final String VISIT_SUMMARY_AMENDED = "visit_summary.amended";
    public static final String VISIT_SUMMARY_VIEWED = "visit_summary.viewed";

    public static final String PRESCRIPTION_CREATED = "prescription.created";
    public static final String PRESCRIPTION_UPDATED = "prescription.updated";
    public static final String PRESCRIPTION_STATUS_CHANGED = "prescription.status_changed";
    public static final String REFILL_REQUESTED = "refill.requested";
    public static final String REFILL_DECIDED = "refill.decided";

    public static final String DOCUMENT_UPLOADED = "document.uploaded";
    public static final String DOCUMENT_DOWNLOADED = "document.downloaded";
    public static final String DOCUMENT_STATUS_CHANGED = "document.status_changed";

    public static final String MESSAGE_THREAD_CREATED = "message.thread_created";
    public static final String MESSAGE_THREAD_VIEWED = "message.thread_viewed";
    public static final String MESSAGE_SENT = "message.sent";
    public static final String MESSAGE_THREAD_STATUS_CHANGED = "message.thread_status_changed";

    public static final String EXPORT_GENERATED = "export.generated";
    public static final String IMPORT_EXECUTED = "import.executed";
    public static final String SETTING_UPDATED = "setting.updated";
    public static final String API_KEY_CREATED = "api_key.created";
    public static final String API_KEY_REVOKED = "api_key.revoked";
    public static final String API_AVAILABILITY_QUERIED = "api.availability_queried";
    public static final String AUDIT_LOG_VIEWED = "audit.log_viewed";
    public static final String JOB_EXECUTED = "job.executed";
    public static final String ACCESS_DENIED = "access.denied";

    private AuditAction() {
    }
}
