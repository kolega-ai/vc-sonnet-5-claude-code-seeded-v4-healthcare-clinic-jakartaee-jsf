-- Clinic Patient Portal :: baseline schema
-- Postgres 15+

CREATE TABLE app_user (
    id               BIGSERIAL PRIMARY KEY,
    email            VARCHAR(190) NOT NULL UNIQUE,
    password_hash    VARCHAR(255) NOT NULL,
    full_name        VARCHAR(160) NOT NULL,
    role             VARCHAR(32)  NOT NULL,
    phone            VARCHAR(40),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_logins    INTEGER      NOT NULL DEFAULT 0,
    locked_until     TIMESTAMP,
    last_login_at    TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_user_role CHECK (role IN ('clinic_admin','clinician','front_desk','patient'))
);
CREATE INDEX ix_app_user_role ON app_user(role);

CREATE TABLE password_reset_token (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash   VARCHAR(128) NOT NULL UNIQUE,
    expires_at   TIMESTAMP    NOT NULL,
    used_at      TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX ix_prt_user ON password_reset_token(user_id);

CREATE TABLE provider (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT UNIQUE REFERENCES app_user(id),
    npi          VARCHAR(20)  NOT NULL UNIQUE,
    first_name   VARCHAR(80)  NOT NULL,
    last_name    VARCHAR(80)  NOT NULL,
    specialty    VARCHAR(120) NOT NULL,
    credentials  VARCHAR(40),
    room         VARCHAR(20),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE patient (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  BIGINT UNIQUE REFERENCES app_user(id),
    mrn                      VARCHAR(24)  NOT NULL UNIQUE,
    first_name               VARCHAR(80)  NOT NULL,
    last_name                VARCHAR(80)  NOT NULL,
    date_of_birth            DATE         NOT NULL,
    gender                   VARCHAR(24),
    phone                    VARCHAR(40),
    email                    VARCHAR(190),
    address_line1            VARCHAR(160),
    address_line2            VARCHAR(160),
    city                     VARCHAR(80),
    state_region             VARCHAR(60),
    postal_code              VARCHAR(20),
    emergency_contact_name   VARCHAR(120),
    emergency_contact_phone  VARCHAR(40),
    emergency_contact_rel    VARCHAR(60),
    insurance_provider       VARCHAR(120),
    insurance_member_id      VARCHAR(60),
    insurance_group          VARCHAR(60),
    primary_provider_id      BIGINT REFERENCES provider(id),
    allergies                TEXT,
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX ix_patient_last_name ON patient(last_name);
CREATE INDEX ix_patient_primary_provider ON patient(primary_provider_id);

CREATE TABLE patient_provider_assignment (
    id            BIGSERIAL PRIMARY KEY,
    patient_id    BIGINT NOT NULL REFERENCES patient(id) ON DELETE CASCADE,
    provider_id   BIGINT NOT NULL REFERENCES provider(id) ON DELETE CASCADE,
    relationship  VARCHAR(40) NOT NULL DEFAULT 'care_team',
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_ppa UNIQUE (patient_id, provider_id)
);

CREATE TABLE provider_schedule (
    id            BIGSERIAL PRIMARY KEY,
    provider_id   BIGINT NOT NULL REFERENCES provider(id) ON DELETE CASCADE,
    day_of_week   INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time    TIME    NOT NULL,
    end_time      TIME    NOT NULL,
    slot_minutes  INTEGER NOT NULL DEFAULT 30,
    active        BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX ix_sched_provider ON provider_schedule(provider_id);

CREATE TABLE appointment_type (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(40)  NOT NULL UNIQUE,
    name              VARCHAR(120) NOT NULL,
    duration_minutes  INTEGER      NOT NULL DEFAULT 30,
    color             VARCHAR(16)  NOT NULL DEFAULT '#2563eb',
    patient_bookable  BOOLEAN      NOT NULL DEFAULT TRUE,
    active            BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE appointment (
    id                    BIGSERIAL PRIMARY KEY,
    patient_id            BIGINT NOT NULL REFERENCES patient(id),
    provider_id           BIGINT NOT NULL REFERENCES provider(id),
    appointment_type_id   BIGINT NOT NULL REFERENCES appointment_type(id),
    start_at              TIMESTAMP NOT NULL,
    end_at                TIMESTAMP NOT NULL,
    status                VARCHAR(24) NOT NULL,
    reason                VARCHAR(500),
    staff_notes           TEXT,
    checked_in_at         TIMESTAMP,
    completed_at          TIMESTAMP,
    cancel_reason         VARCHAR(400),
    created_by_user_id    BIGINT REFERENCES app_user(id),
    created_at            TIMESTAMP NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_appt_status CHECK (status IN ('requested','scheduled','checked_in','completed','cancelled','no_show'))
);
CREATE INDEX ix_appt_patient ON appointment(patient_id);
CREATE INDEX ix_appt_provider_start ON appointment(provider_id, start_at);
CREATE INDEX ix_appt_status ON appointment(status);

CREATE TABLE visit_summary (
    id                  BIGSERIAL PRIMARY KEY,
    appointment_id      BIGINT UNIQUE REFERENCES appointment(id),
    patient_id          BIGINT NOT NULL REFERENCES patient(id),
    provider_id         BIGINT NOT NULL REFERENCES provider(id),
    status              VARCHAR(24) NOT NULL,
    visit_date          DATE NOT NULL,
    chief_complaint     VARCHAR(300),
    diagnosis           TEXT,
    care_instructions   TEXT,
    clinician_notes     TEXT,
    follow_up_advice    VARCHAR(400),
    released_at         TIMESTAMP,
    released_by_user_id BIGINT REFERENCES app_user(id),
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_vs_status CHECK (status IN ('draft','released','amended'))
);
CREATE INDEX ix_vs_patient ON visit_summary(patient_id);
CREATE INDEX ix_vs_status ON visit_summary(status);

CREATE TABLE prescription (
    id                BIGSERIAL PRIMARY KEY,
    patient_id        BIGINT NOT NULL REFERENCES patient(id),
    provider_id       BIGINT NOT NULL REFERENCES provider(id),
    medication_name   VARCHAR(180) NOT NULL,
    dosage            VARCHAR(120) NOT NULL,
    instructions      VARCHAR(500) NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    refills_remaining INTEGER      NOT NULL DEFAULT 0,
    quantity          INTEGER      NOT NULL DEFAULT 30,
    issued_at         DATE         NOT NULL,
    expires_at        DATE,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_rx_status CHECK (status IN ('active','discontinued','expired'))
);
CREATE INDEX ix_rx_patient ON prescription(patient_id);

CREATE TABLE refill_request (
    id                 BIGSERIAL PRIMARY KEY,
    prescription_id    BIGINT NOT NULL REFERENCES prescription(id) ON DELETE CASCADE,
    patient_id         BIGINT NOT NULL REFERENCES patient(id),
    status             VARCHAR(24) NOT NULL,
    patient_note       VARCHAR(1000),
    staff_note         VARCHAR(1000),
    requested_at       TIMESTAMP NOT NULL DEFAULT now(),
    decided_at         TIMESTAMP,
    decided_by_user_id BIGINT REFERENCES app_user(id),
    CONSTRAINT ck_refill_status CHECK (status IN ('requested','needs_info','approved','denied','cancelled'))
);
CREATE INDEX ix_refill_status ON refill_request(status);
CREATE INDEX ix_refill_patient ON refill_request(patient_id);

CREATE TABLE patient_document (
    id                 BIGSERIAL PRIMARY KEY,
    patient_id         BIGINT NOT NULL REFERENCES patient(id),
    category           VARCHAR(40)  NOT NULL,
    title              VARCHAR(200) NOT NULL,
    original_filename  VARCHAR(255) NOT NULL,
    stored_filename    VARCHAR(255) NOT NULL UNIQUE,
    content_type       VARCHAR(120) NOT NULL,
    size_bytes         BIGINT       NOT NULL,
    sha256             VARCHAR(64)  NOT NULL,
    status             VARCHAR(24)  NOT NULL,
    internal_only      BOOLEAN      NOT NULL DEFAULT FALSE,
    review_note        VARCHAR(600),
    uploaded_by_user_id BIGINT REFERENCES app_user(id),
    reviewed_by_user_id BIGINT REFERENCES app_user(id),
    reviewed_at        TIMESTAMP,
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_doc_status CHECK (status IN ('submitted','verified','rejected','archived')),
    CONSTRAINT ck_doc_category CHECK (category IN ('pre_visit_form','insurance_card','lab_result','referral','clinical_note','consent'))
);
CREATE INDEX ix_doc_patient ON patient_document(patient_id);
CREATE INDEX ix_doc_status ON patient_document(status);

CREATE TABLE message_thread (
    id              BIGSERIAL PRIMARY KEY,
    patient_id      BIGINT NOT NULL REFERENCES patient(id),
    subject         VARCHAR(200) NOT NULL,
    category        VARCHAR(40)  NOT NULL DEFAULT 'general',
    status          VARCHAR(24)  NOT NULL DEFAULT 'open',
    assigned_provider_id BIGINT REFERENCES provider(id),
    created_by_user_id BIGINT REFERENCES app_user(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    last_message_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_thread_status CHECK (status IN ('open','awaiting_patient','resolved','closed'))
);
CREATE INDEX ix_thread_patient ON message_thread(patient_id);

CREATE TABLE message (
    id             BIGSERIAL PRIMARY KEY,
    thread_id      BIGINT NOT NULL REFERENCES message_thread(id) ON DELETE CASCADE,
    sender_user_id BIGINT NOT NULL REFERENCES app_user(id),
    body           TEXT   NOT NULL,
    internal_only  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_message_thread ON message(thread_id);

CREATE TABLE message_receipt (
    id         BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES message(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    read_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_receipt UNIQUE (message_id, user_id)
);

CREATE TABLE notification (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    type       VARCHAR(48)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(600),
    link       VARCHAR(300),
    read_at    TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_notif_user ON notification(user_id, read_at);

CREATE TABLE audit_event (
    id              BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMP NOT NULL DEFAULT now(),
    actor_user_id   BIGINT,
    actor_email     VARCHAR(190),
    actor_role      VARCHAR(32),
    action          VARCHAR(64)  NOT NULL,
    entity_type     VARCHAR(64)  NOT NULL,
    entity_id       BIGINT,
    patient_id      BIGINT,
    before_value    TEXT,
    after_value     TEXT,
    auth_basis      VARCHAR(200),
    detail          VARCHAR(600),
    ip_address      VARCHAR(64)
);
CREATE INDEX ix_audit_occurred ON audit_event(occurred_at DESC);
CREATE INDEX ix_audit_patient ON audit_event(patient_id);
CREATE INDEX ix_audit_actor ON audit_event(actor_user_id);
CREATE INDEX ix_audit_action ON audit_event(action);

-- Append-only enforcement: no role may update or delete an audit row.
CREATE OR REPLACE FUNCTION audit_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_no_update BEFORE UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();
CREATE TRIGGER trg_audit_event_no_delete BEFORE DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

CREATE TABLE outbox_email (
    id           BIGSERIAL PRIMARY KEY,
    to_address   VARCHAR(190) NOT NULL,
    subject      VARCHAR(240) NOT NULL,
    body         TEXT NOT NULL,
    status       VARCHAR(24) NOT NULL DEFAULT 'queued',
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    sent_at      TIMESTAMP
);

CREATE TABLE webhook_endpoint (
    id          BIGSERIAL PRIMARY KEY,
    label       VARCHAR(120) NOT NULL,
    url         VARCHAR(400) NOT NULL,
    event_type  VARCHAR(64)  NOT NULL,
    signing_key VARCHAR(120),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE webhook_delivery (
    id           BIGSERIAL PRIMARY KEY,
    endpoint_id  BIGINT REFERENCES webhook_endpoint(id) ON DELETE CASCADE,
    event_type   VARCHAR(64) NOT NULL,
    payload      TEXT NOT NULL,
    status       VARCHAR(24) NOT NULL DEFAULT 'pending',
    response_code INTEGER,
    attempts     INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    delivered_at TIMESTAMP
);

CREATE TABLE api_key (
    id           BIGSERIAL PRIMARY KEY,
    label        VARCHAR(120) NOT NULL,
    key_prefix   VARCHAR(16)  NOT NULL,
    key_hash     VARCHAR(128) NOT NULL,
    scope        VARCHAR(64)  NOT NULL DEFAULT 'availability:read',
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id BIGINT REFERENCES app_user(id),
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    last_used_at TIMESTAMP
);

CREATE TABLE clinic_setting (
    setting_key   VARCHAR(80) PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL,
    description   VARCHAR(300),
    updated_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_by_user_id BIGINT REFERENCES app_user(id)
);

CREATE TABLE job_run (
    id          BIGSERIAL PRIMARY KEY,
    job_name    VARCHAR(80) NOT NULL,
    started_at  TIMESTAMP NOT NULL DEFAULT now(),
    finished_at TIMESTAMP,
    status      VARCHAR(24) NOT NULL DEFAULT 'running',
    detail      VARCHAR(600)
);
