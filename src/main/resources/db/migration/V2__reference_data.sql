-- Reference / configuration data. Operational demo data is loaded by the
-- application seeder (com.clinic.bootstrap.DemoDataSeeder) so that password
-- hashes are generated with the application's own KDF.

INSERT INTO appointment_type (code, name, duration_minutes, color, patient_bookable, active) VALUES
  ('NEW_PATIENT',   'New patient consultation', 45, '#2563eb', TRUE,  TRUE),
  ('FOLLOW_UP',     'Follow-up visit',          30, '#0f766e', TRUE,  TRUE),
  ('ANNUAL_PHYSICAL','Annual physical',         60, '#7c3aed', TRUE,  TRUE),
  ('TELEHEALTH',    'Telehealth visit',         20, '#b45309', TRUE,  TRUE),
  ('LAB_DRAW',      'Lab draw',                 15, '#be123c', TRUE,  TRUE),
  ('PROCEDURE',     'In-clinic procedure',      60, '#334155', FALSE, TRUE);

INSERT INTO clinic_setting (setting_key, setting_value, description) VALUES
  ('clinic.name',                   'Northgate Family Medicine', 'Display name of the practice'),
  ('clinic.timezone',               'UTC',                       'Scheduling timezone'),
  ('clinic.phone',                  '+1-206-555-0142',           'Front desk phone number'),
  ('clinic.address',                '2200 Northgate Way, Seattle, WA 98125', 'Clinic street address'),
  ('booking.max_days_ahead',        '60',                        'How far ahead patients may self-book'),
  ('booking.min_hours_notice',      '12',                        'Minimum notice for patient self-booking'),
  ('booking.cancel_min_hours',      '4',                         'Minimum notice for a patient cancellation'),
  ('documents.max_upload_bytes',    '10485760',                  'Maximum accepted upload size in bytes'),
  ('password.reset_ttl_minutes',    '30',                        'Lifetime of a password reset token'),
  ('session.idle_timeout_minutes',  '30',                        'HTTP session idle timeout');

INSERT INTO webhook_endpoint (label, url, event_type, signing_key) VALUES
  ('Scheduling mirror (local mock)', 'http://localhost:9099/hooks/appointments', 'appointment.status_changed', 'whsec_8f3c1d9a5b6e47f0a2d4c7b19e35'),
  ('Pharmacy bridge (local mock)',   'http://localhost:9099/hooks/refills',      'refill.decided',             'whsec_2a7e04bd63c1495fa8e0d5b73c92');
