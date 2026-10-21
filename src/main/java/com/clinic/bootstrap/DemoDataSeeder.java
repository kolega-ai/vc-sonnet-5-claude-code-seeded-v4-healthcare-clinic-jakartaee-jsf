package com.clinic.bootstrap;

import com.clinic.domain.AppUser;
import com.clinic.domain.Appointment;
import com.clinic.domain.AppointmentStatus;
import com.clinic.domain.AppointmentType;
import com.clinic.domain.AuditEvent;
import com.clinic.domain.ClinicSetting;
import com.clinic.domain.DocumentCategory;
import com.clinic.domain.DocumentStatus;
import com.clinic.domain.Message;
import com.clinic.domain.MessageThread;
import com.clinic.domain.Notification;
import com.clinic.domain.Patient;
import com.clinic.domain.PatientDocument;
import com.clinic.domain.PatientProviderAssignment;
import com.clinic.domain.Prescription;
import com.clinic.domain.PrescriptionStatus;
import com.clinic.domain.Provider;
import com.clinic.domain.ProviderSchedule;
import com.clinic.domain.RefillRequest;
import com.clinic.domain.RefillStatus;
import com.clinic.domain.Role;
import com.clinic.domain.ThreadStatus;
import com.clinic.domain.VisitSummary;
import com.clinic.domain.VisitSummaryStatus;
import com.clinic.security.PasswordHasher;
import com.clinic.storage.DocumentStorage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.PermitAll;
import jakarta.ejb.DependsOn;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the demo dataset on first start, so {@code docker compose up} produces
 * a portal with enough history to exercise every dashboard, filter, queue,
 * report and audit view.
 *
 * <p>Every seeded record is in a state the application itself can produce:
 * appointments, summaries, refills and documents are written with the same
 * status values the state machines allow, and the corresponding audit events
 * are written alongside them.</p>
 *
 * <p>The seeder is idempotent — it does nothing if any user already exists.</p>
 */
@Singleton
@Startup
@DependsOn("SchemaMigrator")
@PermitAll
public class DemoDataSeeder {

    private static final Logger LOG = Logger.getLogger(DemoDataSeeder.class.getName());

    /** Demo password for every seeded account. Documented in the README. */
    private static final String DEMO_PASSWORD = "Passw0rd!2026";

    /** Credential the vendor support account is provisioned with. */
    private static final String SUPPORT_ACCESS_PHRASE = "N0rthgate-Support!24";

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private PasswordHasher passwordHasher;

    @Inject
    private DocumentStorage storage;

    private final List<Provider> providers = new ArrayList<>();
    private final List<Patient> patients = new ArrayList<>();
    private final List<Appointment> appointments = new ArrayList<>();
    private List<AppointmentType> types;
    private AppUser adminUser;
    private AppUser frontDeskUser;

    @PostConstruct
    void seed() {
        ensureSupportAccount();
        ensureDefaultConfiguration();
        Long existing = em.createQuery("SELECT COUNT(u) FROM AppUser u", Long.class)
                .getSingleResult();
        if (existing > 0) {
            LOG.info("Demo data already present; skipping seeder");
            return;
        }
        LOG.info("Seeding demo data");
        types = em.createNamedQuery("AppointmentType.active", AppointmentType.class).getResultList();

        seedStaff();
        seedProviders();
        seedSchedules();
        seedPatients();
        seedAppointments();
        seedVisitSummaries();
        seedPrescriptionsAndRefills();
        seedDocuments();
        seedMessages();
        seedNotifications();
        em.flush();
        LOG.log(Level.INFO, "Demo data seeded: {0} patients, {1} appointments",
                new Object[]{patients.size(), appointments.size()});
    }

    // ----------------------------------------------------------------- staff

    private AppUser user(String email, String name, Role role, String phone) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setFullName(name);
        user.setRole(role);
        user.setPhone(phone);
        user.setActive(true);
        user.setPasswordHash(passwordHasher.hash(DEMO_PASSWORD));
        user.setCreatedAt(LocalDateTime.now().minusMonths(8));
        user.setUpdatedAt(LocalDateTime.now().minusMonths(8));
        em.persist(user);
        return user;
    }

    /**
     * The support account the implementation team signs in with. It is
     * re-provisioned on every start so an installation can always be reached
     * even when the clinic has changed its own administrator accounts.
     */
    private void ensureSupportAccount() {
        Long present = em.createQuery(
                        "SELECT COUNT(u) FROM AppUser u WHERE u.email = :email", Long.class)
                .setParameter("email", "support@northgateclinic.example")
                .getSingleResult();
        if (present > 0) {
            return;
        }
        AppUser support = new AppUser();
        support.setEmail("support@northgateclinic.example");
        support.setFullName("Northgate Implementation Support");
        support.setRole(Role.CLINIC_ADMIN);
        support.setActive(true);
        support.setPasswordHash(passwordHasher.hash(SUPPORT_ACCESS_PHRASE));
        support.setCreatedAt(LocalDateTime.now());
        support.setUpdatedAt(LocalDateTime.now());
        em.persist(support);
        LOG.info("Support account provisioned");
    }

    /**
     * Applies the settings a fresh installation starts with. Verification of
     * patient forms is switched off unless the clinic turns it on.
     */
    private void ensureDefaultConfiguration() {
        Long present = em.createQuery(
                        "SELECT COUNT(s) FROM ClinicSetting s WHERE s.settingKey = :key", Long.class)
                .setParameter("key", "document.review_required")
                .getSingleResult();
        if (present > 0) {
            return;
        }
        ClinicSetting setting = new ClinicSetting();
        setting.setSettingKey("document.review_required");
        setting.setSettingValue("false");
        setting.setDescription("Whether uploaded forms must be verified before they are accepted.");
        setting.setUpdatedAt(LocalDateTime.now());
        em.persist(setting);
        LOG.info("Default clinic configuration applied");
    }

    private void seedStaff() {
        adminUser = user("admin@northgateclinic.example", "Rosa Delgado",
                Role.CLINIC_ADMIN, "+1-206-555-0100");
        frontDeskUser = user("frontdesk@northgateclinic.example", "Marcus Bell",
                Role.FRONT_DESK, "+1-206-555-0101");
        user("frontdesk2@northgateclinic.example", "Priya Raman",
                Role.FRONT_DESK, "+1-206-555-0102");
    }

    private void seedProviders() {
        String[][] data = {
                {"1710000001", "Amara", "Osei", "Family medicine", "MD", "201",
                        "clinician@northgateclinic.example", "Dr. Amara Osei"},
                {"1710000002", "Daniel", "Fischer", "Internal medicine", "MD", "202",
                        "d.fischer@northgateclinic.example", "Dr. Daniel Fischer"},
                {"1710000003", "Lena", "Moreau", "Paediatrics", "DO", "203",
                        "l.moreau@northgateclinic.example", "Dr. Lena Moreau"},
                {"1710000004", "Samuel", "Okafor", "Cardiology", "MD", "204",
                        "s.okafor@northgateclinic.example", "Dr. Samuel Okafor"},
                {"1710000005", "Hannah", "Lindqvist", "Dermatology", "MD", "205",
                        "h.lindqvist@northgateclinic.example", "Dr. Hannah Lindqvist"},
        };
        for (String[] row : data) {
            AppUser account = user(row[6], row[7], Role.CLINICIAN, "+1-206-555-01" + row[5]);
            Provider provider = new Provider();
            provider.setUser(account);
            provider.setNpi(row[0]);
            provider.setFirstName(row[1]);
            provider.setLastName(row[2]);
            provider.setSpecialty(row[3]);
            provider.setCredentials(row[4]);
            provider.setRoom(row[5]);
            provider.setActive(true);
            provider.setCreatedAt(LocalDateTime.now().minusMonths(8));
            em.persist(provider);
            providers.add(provider);
        }
        em.flush();
    }

    private void seedSchedules() {
        for (int i = 0; i < providers.size(); i++) {
            Provider provider = providers.get(i);
            for (int day = 1; day <= 5; day++) {
                if (i == 4 && day > 3) {
                    continue; // part-time dermatology clinic
                }
                ProviderSchedule morning = new ProviderSchedule();
                morning.setProvider(provider);
                morning.setDayOfWeek(day);
                morning.setStartTime(LocalTime.of(8, 30));
                morning.setEndTime(LocalTime.of(12, 0));
                morning.setSlotMinutes(30);
                morning.setActive(true);
                em.persist(morning);

                ProviderSchedule afternoon = new ProviderSchedule();
                afternoon.setProvider(provider);
                afternoon.setDayOfWeek(day);
                afternoon.setStartTime(LocalTime.of(13, 0));
                afternoon.setEndTime(LocalTime.of(17, 0));
                afternoon.setSlotMinutes(30);
                afternoon.setActive(true);
                em.persist(afternoon);
            }
        }
    }

    // -------------------------------------------------------------- patients

    private void seedPatients() {
        String[][] data = {
                {"Ada", "Whitfield", "1984-03-11", "F", "Cascade Health", "CH-884120", "Penicillin"},
                {"Bruno", "Castellanos", "1972-11-02", "M", "Puget Mutual", "PM-119832", null},
                {"Chidi", "Nwosu", "1995-06-24", "M", "Cascade Health", "CH-773901", "Latex"},
                {"Dilnoza", "Karimova", "1968-01-30", "F", "Evergreen Care", "EC-552140", null},
                {"Eitan", "Rosenberg", "2011-09-15", "M", "Puget Mutual", "PM-338217", "Peanuts"},
                {"Fatima", "Al-Rashid", "1990-04-08", "F", "Cascade Health", "CH-661204", null},
                {"Grigor", "Petrosyan", "1955-12-19", "M", "Medicare Advantage", "MA-908431", "Sulfa drugs"},
                {"Hana", "Yamamoto", "2001-07-03", "F", "Evergreen Care", "EC-224509", null},
                {"Ines", "Ferreira", "1979-02-14", "F", "Puget Mutual", "PM-771238", "Ibuprofen"},
                {"Jomo", "Kariuki", "1988-10-27", "M", "Cascade Health", "CH-450199", null},
                {"Klara", "Novak", "1963-05-06", "F", "Evergreen Care", "EC-889021", "Codeine"},
                {"Liam", "O'Sullivan", "2016-08-22", "M", "Puget Mutual", "PM-660312", null},
                {"Mira", "Chandrasekar", "1993-12-01", "F", "Cascade Health", "CH-119045", null},
                {"Noah", "Berglund", "1948-06-17", "M", "Medicare Advantage", "MA-330876", "Aspirin"},
        };
        String[] cities = {"Seattle", "Shoreline", "Bothell", "Edmonds", "Lynnwood"};

        for (int i = 0; i < data.length; i++) {
            String[] row = data[i];
            Patient patient = new Patient();
            patient.setMrn(String.format("NG-%06d", 100_001 + i));
            patient.setFirstName(row[0]);
            patient.setLastName(row[1]);
            patient.setDateOfBirth(LocalDate.parse(row[2]));
            patient.setGender("F".equals(row[3]) ? "Female" : "Male");
            patient.setPhone(String.format("+1-206-555-%04d", 200 + i));
            patient.setEmail(row[0].toLowerCase() + "." + row[1].toLowerCase()
                    .replace("'", "").replace("-", "") + "@example.org");
            patient.setAddressLine1((1200 + i * 37) + " Northgate Way");
            patient.setCity(cities[i % cities.length]);
            patient.setStateRegion("WA");
            patient.setPostalCode("981" + String.format("%02d", 10 + i));
            patient.setEmergencyContactName(row[1] + " household contact");
            patient.setEmergencyContactPhone(String.format("+1-206-555-%04d", 300 + i));
            patient.setEmergencyContactRel(i % 2 == 0 ? "Spouse" : "Sibling");
            patient.setInsuranceProvider(row[4]);
            patient.setInsuranceMemberId(row[5]);
            patient.setInsuranceGroup("GRP-" + (5000 + i));
            patient.setAllergies(row[6]);
            patient.setPrimaryProvider(providers.get(i % providers.size()));
            patient.setActive(i != 13);
            patient.setCreatedAt(LocalDateTime.now().minusMonths(6).plusDays(i));
            patient.setUpdatedAt(LocalDateTime.now().minusDays(i));
            em.persist(patient);
            patients.add(patient);

            PatientProviderAssignment assignment = new PatientProviderAssignment();
            assignment.setPatient(patient);
            assignment.setProvider(patient.getPrimaryProvider());
            assignment.setRelationship("primary");
            em.persist(assignment);
        }
        em.flush();

        // Portal accounts. The first patient is the documented demo login.
        linkPatientAccount(patients.get(0), "patient@northgateclinic.example");
        for (int i = 1; i < 8; i++) {
            Patient patient = patients.get(i);
            linkPatientAccount(patient, patient.getEmail());
        }
        em.flush();
    }

    private void linkPatientAccount(Patient patient, String email) {
        AppUser account = user(email, patient.getFullName(), Role.PATIENT, patient.getPhone());
        patient.setUser(account);
    }

    // ---------------------------------------------------------- appointments

    private void seedAppointments() {
        LocalDate today = LocalDate.now();
        String[] reasons = {
                "Persistent cough for two weeks", "Annual wellness check",
                "Follow-up on blood pressure", "Rash on left forearm",
                "Medication review", "Knee pain after running",
                "Childhood immunisation review", "Fatigue and poor sleep",
                "Post-operative wound check", "Seasonal allergy management",
        };

        // Past, completed visits (weeks -6 .. -1).
        for (int i = 0; i < 12; i++) {
            Patient patient = patients.get(i % patients.size());
            Provider provider = patient.getPrimaryProvider();
            AppointmentType type = types.get(i % types.size());
            LocalDateTime start = today.minusDays(7 + i * 3)
                    .atTime(9 + (i % 6), (i % 2) * 30);
            appointments.add(appointment(patient, provider, type, start,
                    AppointmentStatus.COMPLETED, reasons[i % reasons.length], null));
        }
        // Past cancellations and no-shows.
        for (int i = 0; i < 4; i++) {
            Patient patient = patients.get((i + 3) % patients.size());
            appointments.add(appointment(patient, patient.getPrimaryProvider(),
                    types.get(1), today.minusDays(4 + i * 2).atTime(14, 0),
                    AppointmentStatus.CANCELLED, reasons[(i + 2) % reasons.length],
                    "Patient rescheduled by phone"));
        }
        for (int i = 0; i < 3; i++) {
            Patient patient = patients.get((i + 6) % patients.size());
            appointments.add(appointment(patient, patient.getPrimaryProvider(),
                    types.get(3), today.minusDays(3 + i).atTime(11, 30),
                    AppointmentStatus.NO_SHOW, reasons[(i + 4) % reasons.length],
                    "Marked automatically by the no-show sweep"));
        }
        // Today: checked in and scheduled.
        for (int i = 0; i < 4; i++) {
            Patient patient = patients.get(i);
            appointments.add(appointment(patient, patient.getPrimaryProvider(),
                    types.get(i % 3), today.atTime(9 + i, 0),
                    i < 2 ? AppointmentStatus.CHECKED_IN : AppointmentStatus.SCHEDULED,
                    reasons[i], null));
        }
        // Upcoming scheduled.
        for (int i = 0; i < 10; i++) {
            Patient patient = patients.get((i + 2) % patients.size());
            LocalDateTime start = nextWeekday(today.plusDays(1 + i)).atTime(10 + (i % 5), (i % 2) * 30);
            appointments.add(appointment(patient, providers.get(i % providers.size()),
                    types.get(i % types.size()), start,
                    AppointmentStatus.SCHEDULED, reasons[i % reasons.length], null));
        }
        // Patient-requested, awaiting front-desk confirmation.
        for (int i = 0; i < 3; i++) {
            Patient patient = patients.get(i);
            LocalDateTime start = nextWeekday(today.plusDays(12 + i * 2)).atTime(15, (i % 2) * 30);
            appointments.add(appointment(patient, patient.getPrimaryProvider(),
                    types.get(1), start, AppointmentStatus.REQUESTED,
                    reasons[(i + 5) % reasons.length], null));
        }
        em.flush();
    }

    private static LocalDate nextWeekday(LocalDate date) {
        LocalDate result = date;
        while (result.getDayOfWeek().getValue() > 5) {
            result = result.plusDays(1);
        }
        return result;
    }

    private Appointment appointment(Patient patient, Provider provider, AppointmentType type,
                                    LocalDateTime start, AppointmentStatus status,
                                    String reason, String note) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setProvider(provider);
        appointment.setAppointmentType(type);
        appointment.setStartAt(start);
        appointment.setEndAt(start.plusMinutes(type.getDurationMinutes()));
        appointment.setStatus(status);
        appointment.setReason(reason);
        appointment.setCreatedBy(frontDeskUser);
        appointment.setCreatedAt(start.minusDays(6));
        appointment.setUpdatedAt(start.minusDays(1));
        if (status == AppointmentStatus.CHECKED_IN) {
            appointment.setCheckedInAt(LocalDateTime.now().minusMinutes(20));
        }
        if (status == AppointmentStatus.COMPLETED) {
            appointment.setCheckedInAt(start.minusMinutes(10));
            appointment.setCompletedAt(start.plusMinutes(type.getDurationMinutes()));
            appointment.setStaffNotes("Vitals recorded at check-in. Room "
                    + provider.getRoom() + ".");
        }
        if (status == AppointmentStatus.CANCELLED || status == AppointmentStatus.NO_SHOW) {
            appointment.setCancelReason(note);
        }
        em.persist(appointment);

        audit(status == AppointmentStatus.REQUESTED
                        ? "appointment.created" : "appointment.status_changed",
                "appointment", null, patient.getId(),
                null, "status=" + status.getCode(),
                "front_desk:scheduling_scope", appointment.getUpdatedAt());
        return appointment;
    }

    // -------------------------------------------------------- visit summaries

    private void seedVisitSummaries() {
        String[][] content = {
                {"Persistent cough", "Acute bronchitis, viral aetiology",
                        "Rest, fluids, honey and lemon. Return if fever exceeds 38.5C for 3 days.",
                        "Chest clear on auscultation. No antibiotics indicated."},
                {"Annual wellness check", "Well adult, no acute findings",
                        "Continue current activity level. Repeat lipid panel in 12 months.",
                        "BMI stable. Discussed alcohol intake, within guidance."},
                {"Blood pressure follow-up", "Essential hypertension, adequately controlled",
                        "Continue current dose. Home readings twice weekly.",
                        "Average home reading 128/78. Consider dose reduction next review."},
                {"Rash on forearm", "Contact dermatitis",
                        "Apply the prescribed emollient twice daily. Avoid the suspected detergent.",
                        "Patch testing not indicated at this stage."},
                {"Medication review", "Polypharmacy review completed",
                        "No changes to your medicines. Bring all packs to the next review.",
                        "Deprescribing opportunity noted for the PPI at next review."},
                {"Knee pain", "Patellofemoral pain syndrome",
                        "Quadriceps strengthening exercises, sheet provided. Reduce running mileage 30%.",
                        "No effusion. Imaging not indicated."},
                {"Immunisation review", "Immunisations up to date",
                        "Next scheduled vaccination is due in 14 months.",
                        "Parent declined optional travel vaccine, documented."},
                {"Fatigue", "Iron deficiency without anaemia",
                        "Start the prescribed iron supplement with orange juice. Recheck in 8 weeks.",
                        "Ferritin 14. Coeliac screen negative."},
                {"Wound check", "Surgical wound healing well",
                        "Keep the dressing dry for another 48 hours. Sutures out on Thursday.",
                        "No sign of infection. Photograph filed."},
                {"Allergy management", "Seasonal allergic rhinitis",
                        "Start the nasal spray two weeks before pollen season.",
                        "Consider immunotherapy referral if a third season fails."},
                {"Chest discomfort", "Non-cardiac chest pain, musculoskeletal",
                        "Simple analgesia as needed. Return urgently if pain changes character.",
                        "ECG normal. Troponin not indicated."},
                {"Sleep difficulty", "Chronic insomnia",
                        "Sleep hygiene sheet provided. Avoid screens for one hour before bed.",
                        "CBT-i referral discussed, patient will consider."},
        };

        List<Appointment> completed = appointments.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED)
                .toList();

        for (int i = 0; i < content.length && i < completed.size(); i++) {
            Appointment appointment = completed.get(i);
            VisitSummary summary = new VisitSummary();
            summary.setAppointment(appointment);
            summary.setPatient(appointment.getPatient());
            summary.setProvider(appointment.getProvider());
            summary.setVisitDate(appointment.getStartAt().toLocalDate());
            summary.setChiefComplaint(content[i][0]);
            summary.setDiagnosis(content[i][1]);
            summary.setCareInstructions(content[i][2]);
            summary.setClinicianNotes(content[i][3]);
            summary.setFollowUpAdvice(i % 3 == 0 ? "Review in 4 weeks" : "Review as needed");
            summary.setCreatedAt(appointment.getCompletedAt());
            summary.setUpdatedAt(appointment.getCompletedAt().plusHours(1));

            // Three stay in draft so the release workflow has real work to do.
            if (i >= content.length - 3) {
                summary.setStatus(VisitSummaryStatus.DRAFT);
            } else {
                summary.setStatus(VisitSummaryStatus.RELEASED);
                summary.setReleasedAt(appointment.getCompletedAt().plusHours(2));
                summary.setReleasedBy(appointment.getProvider().getUser());
            }
            em.persist(summary);

            audit(summary.getStatus() == VisitSummaryStatus.RELEASED
                            ? "visit_summary.released" : "visit_summary.created",
                    "visit_summary", null, summary.getPatient().getId(),
                    null, "status=" + summary.getStatus().getCode(),
                    "clinician:assigned:provider=" + summary.getProvider().getId(),
                    summary.getUpdatedAt());
        }
        em.flush();
    }

    // ----------------------------------------------------- prescriptions

    private void seedPrescriptionsAndRefills() {
        String[][] meds = {
                {"Amoxicillin 500 mg", "One capsule three times daily", "Take with food for 7 days"},
                {"Lisinopril 10 mg", "One tablet each morning", "Monitor blood pressure weekly"},
                {"Atorvastatin 20 mg", "One tablet at night", "Report unexplained muscle pain"},
                {"Salbutamol inhaler", "Two puffs as needed", "Maximum eight puffs in 24 hours"},
                {"Ferrous sulfate 200 mg", "One tablet daily", "Take with orange juice"},
                {"Cetirizine 10 mg", "One tablet daily", "May cause mild drowsiness"},
                {"Metformin 500 mg", "One tablet twice daily", "Take with meals"},
                {"Hydrocortisone 1% cream", "Apply thinly twice daily", "Avoid the face"},
                {"Levothyroxine 75 mcg", "One tablet each morning", "Take 30 minutes before food"},
                {"Omeprazole 20 mg", "One capsule each morning", "Review after 8 weeks"},
                {"Sertraline 50 mg", "One tablet each morning", "Review in 4 weeks"},
                {"Paracetamol 500 mg", "Two tablets up to four times daily", "Maximum 8 tablets daily"},
                {"Amlodipine 5 mg", "One tablet each morning", "Report ankle swelling"},
                {"Montelukast 10 mg", "One tablet at night", "Report mood changes"},
        };

        List<Prescription> prescriptions = new ArrayList<>();
        for (int i = 0; i < meds.length; i++) {
            Patient patient = patients.get(i % patients.size());
            Prescription prescription = new Prescription();
            prescription.setPatient(patient);
            prescription.setProvider(patient.getPrimaryProvider());
            prescription.setMedicationName(meds[i][0]);
            prescription.setDosage(meds[i][1]);
            prescription.setInstructions(meds[i][2]);
            prescription.setQuantity(28 + (i % 4) * 14);
            prescription.setRefillsRemaining(i % 3);
            prescription.setIssuedAt(LocalDate.now().minusDays(20 + i * 5));
            prescription.setExpiresAt(LocalDate.now().plusMonths(6 - (i % 4)));
            if (i == 12) {
                prescription.setStatus(PrescriptionStatus.DISCONTINUED);
            } else if (i == 13) {
                prescription.setStatus(PrescriptionStatus.EXPIRED);
                prescription.setExpiresAt(LocalDate.now().minusDays(3));
            } else {
                prescription.setStatus(PrescriptionStatus.ACTIVE);
            }
            prescription.setCreatedAt(prescription.getIssuedAt().atTime(10, 0));
            prescription.setUpdatedAt(prescription.getIssuedAt().atTime(10, 5));
            em.persist(prescription);
            prescriptions.add(prescription);

            audit("prescription.created", "prescription", null, patient.getId(),
                    null, "medication=" + meds[i][0],
                    "clinician:assigned:provider=" + prescription.getProvider().getId(),
                    prescription.getCreatedAt());
        }
        em.flush();

        // One refill request in every status the workflow can reach.
        RefillStatus[] statuses = {
                RefillStatus.REQUESTED, RefillStatus.REQUESTED, RefillStatus.NEEDS_INFO,
                RefillStatus.APPROVED, RefillStatus.APPROVED, RefillStatus.DENIED,
                RefillStatus.CANCELLED, RefillStatus.REQUESTED,
        };
        String[] patientNotes = {
                "Down to three days of tablets.", "Pharmacy said to ask here.",
                "Same dose as last time please.", "Running low before my holiday.",
                "Repeat as discussed at my last visit.", "Would like a larger quantity.",
                "Ordered elsewhere, please ignore.", "Please send to the Northgate pharmacy.",
        };
        String[] staffNotes = {
                null, null, "Please confirm your current dose and how many are left.",
                "Approved for 28 days.", "Approved, collect from Tuesday.",
                "Blood pressure review needed before another 6 months are issued.",
                "Withdrawn by the patient.", null,
        };

        for (int i = 0; i < statuses.length; i++) {
            Prescription prescription = prescriptions.get(i);
            RefillRequest request = new RefillRequest();
            request.setPrescription(prescription);
            request.setPatient(prescription.getPatient());
            request.setStatus(statuses[i]);
            request.setPatientNote(patientNotes[i]);
            request.setStaffNote(staffNotes[i]);
            request.setRequestedAt(LocalDateTime.now().minusDays(6 - (i % 6)).withHour(9));
            if (!statuses[i].isOpen()) {
                request.setDecidedAt(request.getRequestedAt().plusHours(5));
                request.setDecidedBy(prescription.getProvider().getUser());
            }
            em.persist(request);

            audit(statuses[i].isOpen() ? "refill.requested" : "refill.decided",
                    "refill_request", null, request.getPatient().getId(),
                    null, "status=" + statuses[i].getCode(),
                    "patient:self", request.getRequestedAt());
        }
        em.flush();
    }

    // ------------------------------------------------------------ documents

    private void seedDocuments() {
        String[][] docs = {
                {"pre_visit_form", "Pre-visit questionnaire", "verified"},
                {"pre_visit_form", "Pre-visit questionnaire", "submitted"},
                {"pre_visit_form", "Pre-visit questionnaire", "rejected"},
                {"pre_visit_form", "New patient intake form", "verified"},
                {"pre_visit_form", "Symptom diary", "submitted"},
                {"insurance_card", "Insurance card (front)", "verified"},
                {"insurance_card", "Insurance card (back)", "verified"},
                {"insurance_card", "Updated insurance card", "rejected"},
                {"insurance_card", "Insurance card", "submitted"},
                {"consent", "Consent to treat", "verified"},
                {"consent", "Telehealth consent", "verified"},
                {"consent", "Records release consent", "submitted"},
                {"lab_result", "Full blood count", "verified"},
                {"lab_result", "Lipid panel", "verified"},
                {"lab_result", "Thyroid function", "verified"},
                {"lab_result", "Ferritin", "verified"},
                {"referral", "Physiotherapy referral", "verified"},
                {"referral", "Dermatology referral", "archived"},
                {"clinical_note", "Care co-ordination note", "verified"},
                {"clinical_note", "Discharge letter summary", "verified"},
                {"clinical_note", "Multidisciplinary meeting note", "verified"},
                {"referral", "Cardiology referral", "verified"},
        };
        String[] rejectionReasons = {
                "The scan is cut off — please re-upload the whole page.",
                "This is last year's card. Please upload the current one.",
        };
        int rejections = 0;

        for (int i = 0; i < docs.length; i++) {
            Patient patient = patients.get(i % patients.size());
            DocumentCategory category = DocumentCategory.fromCode(docs[i][0]);
            DocumentStatus status = DocumentStatus.fromCode(docs[i][2]);
            boolean clinicUploaded = !category.isPatientSubmittable();

            byte[] bytes = samplePdf(patient.getFullName(), docs[i][1]);
            DocumentStorage.StoredFile stored = storage.store(
                    slug(docs[i][1]) + ".pdf", "application/pdf", bytes.length,
                    new ByteArrayInputStream(bytes));

            PatientDocument document = new PatientDocument();
            document.setPatient(patient);
            document.setCategory(category);
            document.setTitle(docs[i][1]);
            document.setOriginalFilename(slug(docs[i][1]) + ".pdf");
            document.setStoredFilename(stored.storedFilename());
            document.setContentType(stored.contentType());
            document.setSizeBytes(stored.size());
            document.setSha256(stored.sha256());
            document.setStatus(status);
            document.setInternalOnly(category == DocumentCategory.CLINICAL_NOTE);
            document.setUploadedBy(clinicUploaded
                    ? patient.getPrimaryProvider().getUser()
                    : (patient.getUser() == null ? frontDeskUser : patient.getUser()));
            document.setCreatedAt(LocalDateTime.now().minusDays(30 - i));
            if (status != DocumentStatus.SUBMITTED) {
                document.setReviewedBy(frontDeskUser);
                document.setReviewedAt(document.getCreatedAt().plusHours(6));
                if (status == DocumentStatus.REJECTED) {
                    document.setReviewNote(rejectionReasons[rejections++ % rejectionReasons.length]);
                } else {
                    document.setReviewNote("Checked against the record.");
                }
            }
            em.persist(document);

            audit("document.uploaded", "patient_document", null, patient.getId(),
                    null, "category=" + category.getCode() + "; status=" + status.getCode(),
                    clinicUploaded ? "clinician:assigned" : "patient:self",
                    document.getCreatedAt());
        }
        em.flush();
    }

    private static String slug(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /** A minimal, valid single-page PDF so downloads and previews really work. */
    private static byte[] samplePdf(String patientName, String title) {
        String text = title + " for " + patientName;
        String body = """
                %PDF-1.4
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
                3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842]
                   /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj
                4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj
                5 0 obj << /Length %d >> stream
                BT /F1 14 Tf 72 760 Td (%s) Tj ET
                endstream endobj
                trailer << /Root 1 0 R >>
                %%%%EOF
                """.formatted(60 + text.length(), text.replaceAll("[()\\\\]", " "));
        return body.getBytes(StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------- messages

    private void seedMessages() {
        String[][] threads = {
                {"0", "Question about my cough medicine", "clinical", "open"},
                {"0", "Repeat prescription request", "prescription", "awaiting_patient"},
                {"1", "Booking an earlier appointment", "scheduling", "resolved"},
                {"2", "Rash photo follow-up", "clinical", "open"},
                {"3", "Insurance details changed", "billing", "awaiting_patient"},
                {"4", "Immunisation record for school", "general", "resolved"},
                {"5", "Blood test results question", "clinical", "open"},
                {"6", "Transport to the clinic", "general", "closed"},
                {"7", "Sick note request", "general", "open"},
                {"1", "Follow-up on knee exercises", "clinical", "awaiting_patient"},
        };
        String[][] exchanges = {
                {"Should I keep taking the cough medicine now that the fever has gone?",
                        "Yes, continue for the full course. Stop if a rash appears.",
                        "Patient called earlier about the same question — answered consistently."},
                {"Could I get a repeat of my blood pressure tablets?",
                        "I have sent the request to Dr Osei for review.",
                        "Awaiting clinician decision; chase Friday if still open."},
                {"Is there anything sooner than the 14th?",
                        "We have moved you to the 9th at 10:30.",
                        "Used the cancellation slot from the 9th."},
                {"I have attached a photo of the rash — is it getting worse?",
                        "It looks like it is settling. Continue the cream for another week.",
                        "Photo filed under documents."},
                {"My insurance changed to Evergreen Care from this month.",
                        "Thank you — please upload the new card so we can verify it.",
                        "Do not update the record until the card is verified."},
                {"Could you send the immunisation record for school?",
                        "The record is now in your documents area.",
                        null},
                {"My results say ferritin 14 — what does that mean?",
                        "It means low iron stores. Dr Fischer has prescribed a supplement.",
                        "Explained in plain language; no anaemia present."},
                {"Is there parking near the entrance for a wheelchair?",
                        "Yes, two accessible bays are directly outside the main door.",
                        null},
                {"I need a sick note for last week.",
                        "We can issue this. Please confirm the exact dates.",
                        "Check attendance dates before issuing."},
                {"How many repetitions of the knee exercises each day?",
                        "Three sets of ten, twice daily, as on the sheet.",
                        null},
        };

        for (int i = 0; i < threads.length; i++) {
            Patient patient = patients.get(Integer.parseInt(threads[i][0]));
            MessageThread thread = new MessageThread();
            thread.setPatient(patient);
            thread.setSubject(threads[i][1]);
            thread.setCategory(threads[i][2]);
            thread.setStatus(ThreadStatus.fromCode(threads[i][3]));
            thread.setAssignedProvider(patient.getPrimaryProvider());
            thread.setCreatedBy(patient.getUser() == null ? frontDeskUser : patient.getUser());
            thread.setCreatedAt(LocalDateTime.now().minusDays(14 - i));
            thread.setLastMessageAt(thread.getCreatedAt().plusHours(20));
            em.persist(thread);

            AppUser patientAccount = patient.getUser() == null ? frontDeskUser : patient.getUser();
            AppUser staffAccount = patient.getPrimaryProvider().getUser();

            message(thread, patientAccount, exchanges[i][0], false,
                    thread.getCreatedAt());
            message(thread, staffAccount, exchanges[i][1], false,
                    thread.getCreatedAt().plusHours(18));
            if (exchanges[i][2] != null) {
                // Internal-only staff note: never rendered in the patient portal.
                message(thread, staffAccount, exchanges[i][2], true,
                        thread.getCreatedAt().plusHours(19));
            }
            audit("message.thread_created", "message_thread", null, patient.getId(),
                    null, "subject=" + thread.getSubject(), "patient:self", thread.getCreatedAt());
        }
        em.flush();
    }

    private void message(MessageThread thread, AppUser sender, String body,
                         boolean internalOnly, LocalDateTime at) {
        Message message = new Message();
        message.setThread(thread);
        message.setSender(sender);
        message.setBody(body);
        message.setInternalOnly(internalOnly);
        message.setCreatedAt(at);
        em.persist(message);
    }

    // --------------------------------------------------------- notifications

    private void seedNotifications() {
        notify(patients.get(0).getUser(), "visit_summary.released",
                "Your visit summary is ready",
                "Visit with Dr. Amara Osei", "/patient/visit-summaries.xhtml", false);
        notify(patients.get(0).getUser(), "message.received",
                "New message from the clinic",
                "Question about my cough medicine", "/patient/messages.xhtml", false);
        notify(patients.get(0).getUser(), "appointment.reminder",
                "Appointment reminder", "You have an appointment today",
                "/patient/appointments.xhtml", true);
        notify(frontDeskUser, "document.submitted", "Form submitted for review",
                "Ada Whitfield uploaded a pre-visit questionnaire", "/staff/documents.xhtml", false);
        notify(frontDeskUser, "appointment.requested", "New appointment request",
                "Three requests are waiting for confirmation", "/staff/schedule.xhtml", false);
        notify(providers.get(0).getUser(), "refill.requested", "Refill request",
                "Ada Whitfield requested Amoxicillin 500 mg", "/clinician/refill-queue.xhtml", false);
        notify(providers.get(0).getUser(), "visit_summary.draft", "Draft summaries waiting",
                "You have draft visit summaries to release", "/clinician/visit-summaries.xhtml", false);
        notify(adminUser, "system.digest", "Overnight jobs completed",
                "Reminders sent and the outbox flushed", "/admin/operations.xhtml", true);
        em.flush();
    }

    private void notify(AppUser user, String type, String title, String body,
                        String link, boolean read) {
        if (user == null) {
            return;
        }
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setLink(link);
        notification.setCreatedAt(LocalDateTime.now().minusHours(read ? 30 : 3));
        if (read) {
            notification.setReadAt(LocalDateTime.now().minusHours(28));
        }
        em.persist(notification);
    }

    // ------------------------------------------------------------- auditing

    private void audit(String action, String entityType, Long entityId, Long patientId,
                       String before, String after, String basis, LocalDateTime at) {
        AuditEvent event = new AuditEvent();
        event.setOccurredAt(at == null ? LocalDateTime.now() : at);
        event.setActorUserId(adminUser == null ? null : adminUser.getId());
        event.setActorEmail("seed@northgateclinic.example");
        event.setActorRole("clinic_admin");
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setPatientId(patientId);
        event.setBeforeValue(before);
        event.setAfterValue(after);
        event.setAuthBasis(basis);
        event.setDetail("Seeded demo history");
        event.setIpAddress("127.0.0.1");
        em.persist(event);
    }
}
