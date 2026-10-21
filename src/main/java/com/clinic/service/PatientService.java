package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.Patient;
import com.clinic.domain.PatientProviderAssignment;
import com.clinic.domain.Provider;
import com.clinic.domain.Role;
import com.clinic.security.AccessDecision;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.CurrentUser;
import com.clinic.security.RecordNotFoundException;
import com.clinic.util.SortWhitelist;
import com.clinic.util.Validations;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Patient directory and demographics.
 *
 * <p>Every method that reaches a specific patient routes through
 * {@link AccessGuard}; the role annotations below only narrow the population
 * that may attempt the call at all.</p>
 */
@Stateless
public class PatientService {

    /**
     * <strong>Precision-baseline primitive #4.</strong> A bounded, whitelisted
     * sort column for the patient directory. JPQL cannot bind ORDER BY as a
     * parameter, so the caller's sort key selects one of these fixed constants;
     * the untrusted value itself never reaches the query text. Every filter in
     * the same query is a bound parameter.
     */
    private static final SortWhitelist PATIENT_SORT = SortWhitelist.builder("name")
            .allow("name", "p.lastName ASC, p.firstName ASC")
            .allow("name_desc", "p.lastName DESC, p.firstName DESC")
            .allow("mrn", "p.mrn ASC")
            .allow("dob", "p.dateOfBirth ASC")
            .allow("dob_desc", "p.dateOfBirth DESC")
            .allow("created", "p.createdAt DESC")
            .build();

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    // ------------------------------------------------------------- searching

    /** Staff-only directory search. Clinicians see only their own panel. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public List<Patient> search(PatientSearchCriteria criteria) {
        accessGuard.requireStaff();

        StringBuilder jpql = new StringBuilder("SELECT DISTINCT p FROM Patient p WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (criteria.getText() != null && !criteria.getText().isBlank()) {
            jpql.append(" AND (lower(p.lastName) LIKE :text OR lower(p.firstName) LIKE :text")
                    .append(" OR lower(p.mrn) LIKE :text OR lower(p.email) LIKE :text)");
            params.put("text", "%" + criteria.getText().trim().toLowerCase() + "%");
        }
        if (criteria.getActive() != null) {
            jpql.append(" AND p.active = :active");
            params.put("active", criteria.getActive());
        }
        if (criteria.getBornAfter() != null) {
            jpql.append(" AND p.dateOfBirth >= :bornAfter");
            params.put("bornAfter", criteria.getBornAfter());
        }
        if (criteria.getBornBefore() != null) {
            jpql.append(" AND p.dateOfBirth <= :bornBefore");
            params.put("bornBefore", criteria.getBornBefore());
        }

        Long panelProviderId = criteria.getProviderId();
        if (currentUser.getRole() == Role.CLINICIAN) {
            // A clinician's directory is limited to their own panel regardless
            // of the provider filter they submitted.
            Provider provider = currentUser.getProvider();
            if (provider == null) {
                return List.of();
            }
            panelProviderId = provider.getId();
        }
        if (panelProviderId != null) {
            jpql.append(" AND (p.primaryProvider.id = :providerId OR EXISTS (")
                    .append("SELECT a FROM PatientProviderAssignment a ")
                    .append("WHERE a.patient = p AND a.provider.id = :providerId) OR EXISTS (")
                    .append("SELECT ap FROM Appointment ap ")
                    .append("WHERE ap.patient = p AND ap.provider.id = :providerId))");
            params.put("providerId", panelProviderId);
        }

        // Whitelisted constant, never caller text.
        jpql.append(" ORDER BY ").append(PATIENT_SORT.orderByFragment(criteria.getSortKey()));

        TypedQuery<Patient> query = em.createQuery(jpql.toString(), Patient.class);
        params.forEach(query::setParameter);
        query.setMaxResults(Math.min(Math.max(criteria.getMaxResults(), 1), 500));
        List<Patient> results = query.getResultList();

        // One read-audit event for the whole request, with the row count.
        auditService.recordRead(AuditAction.PATIENT_LIST_VIEWED, "patient", null,
                results.size(), "role:" + currentUser.getRole().getCode());
        return results;
    }

    public List<String> sortKeys() {
        return new ArrayList<>(PATIENT_SORT.keys());
    }

    // ------------------------------------------------------------- retrieval

    /**
     * Loads a patient chart. The ownership decision is made by the shared
     * {@link AccessGuard} one frame above this call, and the resulting basis is
     * recorded with the chart-read audit event.
     */
    public Patient openChart(Long patientId) {
        AccessDecision decision = accessGuard.requirePatientAccess(patientId);
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
        auditService.recordIndependently(
                AuditEntry.of(AuditAction.PATIENT_CHART_VIEWED, "patient")
                        .entity(patient.getId())
                        .patient(patient.getId())
                        .basis(decision.basis())
                        .detail("MRN " + patient.getMrn()));
        return patient;
    }

    /** Loads a patient without writing a chart-read event (internal lookups). */
    public Patient requirePatient(Long patientId) {
        accessGuard.requirePatientAccess(patientId);
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
        return patient;
    }

    /** The signed-in patient's own record. */
    public Patient currentPatientRecord() {
        Long patientId = currentUser.getPatientId();
        if (patientId == null) {
            throw new BusinessRuleException("This account is not linked to a patient record.");
        }
        return em.find(Patient.class, patientId);
    }

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public List<Patient> panelFor(Long providerId, int limit) {
        accessGuard.requireStaff();
        return em.createQuery(
                        "SELECT DISTINCT p FROM Patient p WHERE p.active = true AND ("
                                + "p.primaryProvider.id = :providerId OR EXISTS ("
                                + "SELECT a FROM PatientProviderAssignment a "
                                + "WHERE a.patient = p AND a.provider.id = :providerId)) "
                                + "ORDER BY p.lastName, p.firstName", Patient.class)
                .setParameter("providerId", providerId)
                .setMaxResults(Math.min(Math.max(limit, 1), 500))
                .getResultList();
    }

    // ---------------------------------------------------------- index search

    /**
     * Searches the patient index document the practice exchanges with partners.
     * A clinician only ever reaches the patients on their own panel.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public List<String> namesMatching(String familyName) {
        accessGuard.requireStaff();
        Long scopeProvider = null;
        if (currentUser.getRole() == Role.CLINICIAN) {
            Provider provider = currentUser.getProvider();
            if (provider == null) {
                return List.of();
            }
            scopeProvider = provider.getId();
        }
        StringBuilder xml = new StringBuilder("<directory>");
        for (Patient p : em.createQuery(
                        "SELECT p FROM Patient p ORDER BY p.lastName", Patient.class)
                .setMaxResults(5000)
                .getResultList()) {
            xml.append("<patient><providerId>")
                    .append(p.getPrimaryProvider() == null ? "" : p.getPrimaryProvider().getId())
                    .append("</providerId><lastName>").append(escapeXml(p.getLastName()))
                    .append("</lastName><fullName>").append(escapeXml(p.getFullName()))
                    .append("</fullName></patient>");
        }
        xml.append("</directory>");
        String scopeClause = scopeProvider == null ? "" : "providerId='" + scopeProvider + "' and ";
        String expression = "//patient[" + scopeClause + "lastName='" + familyName + "']/fullName";
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(
                    new org.xml.sax.InputSource(new java.io.StringReader(xml.toString())));
            javax.xml.xpath.XPath xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            org.w3c.dom.NodeList nodes = (org.w3c.dom.NodeList) xpath.evaluate(expression, document, javax.xml.xpath.XPathConstants.NODESET);
            List<String> results = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                results.add(nodes.item(i).getTextContent());
            }
            return results;
        } catch (Exception e) {
            throw new BusinessRuleException("The patient index could not be searched.");
        }
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;");
    }

    // -------------------------------------------------------------- mutation

    /** Front desk and admins maintain demographics; clinicians do not. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.FRONT_DESK_ROLE})
    public Patient updateDemographics(Long patientId, Patient form) {
        accessGuard.requireRole(Role.CLINIC_ADMIN, Role.FRONT_DESK);
        AccessDecision decision = accessGuard.requirePatientAccess(patientId);
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
        String before = snapshot(patient);

        patient.setFirstName(Validations.requireText(form.getFirstName(), "First name", 80));
        patient.setLastName(Validations.requireText(form.getLastName(), "Last name", 80));
        patient.setDateOfBirth(Validations.requireBirthDate(form.getDateOfBirth()));
        patient.setGender(Validations.optionalText(form.getGender(), "Gender", 24));
        patient.setPhone(Validations.optionalPhone(form.getPhone()));
        patient.setEmail(form.getEmail() == null || form.getEmail().isBlank()
                ? null : Validations.requireEmail(form.getEmail()));
        patient.setAddressLine1(Validations.optionalText(form.getAddressLine1(), "Address", 160));
        patient.setAddressLine2(Validations.optionalText(form.getAddressLine2(), "Address", 160));
        patient.setCity(Validations.optionalText(form.getCity(), "City", 80));
        patient.setStateRegion(Validations.optionalText(form.getStateRegion(), "State", 60));
        patient.setPostalCode(Validations.optionalText(form.getPostalCode(), "Postal code", 20));
        patient.setEmergencyContactName(
                Validations.optionalText(form.getEmergencyContactName(), "Emergency contact", 120));
        patient.setEmergencyContactPhone(Validations.optionalPhone(form.getEmergencyContactPhone()));
        patient.setEmergencyContactRel(
                Validations.optionalText(form.getEmergencyContactRel(), "Relationship", 60));
        patient.setInsuranceProvider(
                Validations.optionalText(form.getInsuranceProvider(), "Insurance provider", 120));
        patient.setInsuranceMemberId(
                Validations.optionalText(form.getInsuranceMemberId(), "Member ID", 60));
        patient.setInsuranceGroup(
                Validations.optionalText(form.getInsuranceGroup(), "Group number", 60));
        patient.setAllergies(Validations.optionalText(form.getAllergies(), "Allergies", 2000));
        if (form.getPrimaryProvider() != null) {
            patient.setPrimaryProvider(form.getPrimaryProvider());
            ensureCareTeam(patient, form.getPrimaryProvider());
        }
        patient.setUpdatedAt(LocalDateTime.now());

        auditService.record(AuditEntry.of(AuditAction.PATIENT_UPDATED, "patient")
                .entity(patient.getId())
                .patient(patient.getId())
                .before(before)
                .after(snapshot(patient))
                .basis(decision.basis()));
        return patient;
    }

    /** Only a clinic admin re-assigns the primary provider / care team. */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public void assignPrimaryProvider(Long patientId, Long providerId) {
        accessGuard.requireClinicAdmin();
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
        Provider provider = em.find(Provider.class, providerId);
        if (provider == null) {
            throw new RecordNotFoundException("Provider not found.");
        }
        String before = patient.getPrimaryProvider() == null
                ? "none" : patient.getPrimaryProvider().getDisplayName();
        patient.setPrimaryProvider(provider);
        ensureCareTeam(patient, provider);
        auditService.record(AuditEntry.of(AuditAction.PATIENT_UPDATED, "patient")
                .entity(patient.getId())
                .patient(patient.getId())
                .before("primaryProvider=" + before)
                .after("primaryProvider=" + provider.getDisplayName())
                .basis(AccessDecision.clinicAdmin().basis()));
    }

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.FRONT_DESK_ROLE})
    public Patient createPatient(Patient form, Long primaryProviderId) {
        accessGuard.requireRole(Role.CLINIC_ADMIN, Role.FRONT_DESK);
        Patient patient = new Patient();
        patient.setMrn(nextMrn());
        patient.setFirstName(Validations.requireText(form.getFirstName(), "First name", 80));
        patient.setLastName(Validations.requireText(form.getLastName(), "Last name", 80));
        patient.setDateOfBirth(Validations.requireBirthDate(form.getDateOfBirth()));
        patient.setGender(Validations.optionalText(form.getGender(), "Gender", 24));
        patient.setPhone(Validations.optionalPhone(form.getPhone()));
        patient.setEmail(form.getEmail() == null || form.getEmail().isBlank()
                ? null : Validations.requireEmail(form.getEmail()));
        patient.setAddressLine1(Validations.optionalText(form.getAddressLine1(), "Address", 160));
        patient.setCity(Validations.optionalText(form.getCity(), "City", 80));
        patient.setStateRegion(Validations.optionalText(form.getStateRegion(), "State", 60));
        patient.setPostalCode(Validations.optionalText(form.getPostalCode(), "Postal code", 20));
        patient.setEmergencyContactName(
                Validations.optionalText(form.getEmergencyContactName(), "Emergency contact", 120));
        patient.setEmergencyContactPhone(Validations.optionalPhone(form.getEmergencyContactPhone()));
        patient.setInsuranceProvider(
                Validations.optionalText(form.getInsuranceProvider(), "Insurance provider", 120));
        patient.setInsuranceMemberId(
                Validations.optionalText(form.getInsuranceMemberId(), "Member ID", 60));
        patient.setActive(true);
        if (primaryProviderId != null) {
            Provider provider = em.find(Provider.class, primaryProviderId);
            if (provider == null) {
                throw new RecordNotFoundException("Provider not found.");
            }
            patient.setPrimaryProvider(provider);
        }
        em.persist(patient);
        em.flush();
        if (patient.getPrimaryProvider() != null) {
            ensureCareTeam(patient, patient.getPrimaryProvider());
        }
        auditService.record(AuditEntry.of(AuditAction.PATIENT_CREATED, "patient")
                .entity(patient.getId())
                .patient(patient.getId())
                .after(snapshot(patient))
                .basis("role:" + currentUser.getRole().getCode()));
        return patient;
    }

    void ensureCareTeam(Patient patient, Provider provider) {
        Long existing = em.createQuery(
                        "SELECT COUNT(a) FROM PatientProviderAssignment a "
                                + "WHERE a.patient.id = :patientId AND a.provider.id = :providerId",
                        Long.class)
                .setParameter("patientId", patient.getId())
                .setParameter("providerId", provider.getId())
                .getSingleResult();
        if (existing == 0) {
            PatientProviderAssignment assignment = new PatientProviderAssignment();
            assignment.setPatient(patient);
            assignment.setProvider(provider);
            assignment.setRelationship("care_team");
            em.persist(assignment);
        }
    }

    /** MRNs are allocated by the server, never accepted from a caller. */
    public String nextMrn() {
        Long count = em.createQuery("SELECT COUNT(p) FROM Patient p", Long.class).getSingleResult();
        long candidate = 100_000 + count + 1;
        while (mrnExists("NG-" + candidate)) {
            candidate++;
        }
        return "NG-" + candidate;
    }

    private boolean mrnExists(String mrn) {
        return em.createNamedQuery("Patient.byMrn", Patient.class)
                .setParameter("mrn", mrn)
                .getResultStream().findAny().isPresent();
    }

    public long countActive() {
        return em.createQuery("SELECT COUNT(p) FROM Patient p WHERE p.active = true", Long.class)
                .getSingleResult();
    }

    static String snapshot(Patient p) {
        return "name=" + p.getFullName()
                + "; dob=" + p.getDateOfBirth()
                + "; phone=" + p.getPhone()
                + "; email=" + p.getEmail()
                + "; city=" + p.getCity()
                + "; insurance=" + p.getInsuranceProvider();
    }

    /** Used by the import routine; performs its own validation and audit. */
    Patient persistImported(String firstName, String lastName, LocalDate dob, String email,
                            String phone, String insurance, Provider primaryProvider) {
        Patient patient = new Patient();
        patient.setMrn(nextMrn());
        patient.setFirstName(Validations.requireText(firstName, "First name", 80));
        patient.setLastName(Validations.requireText(lastName, "Last name", 80));
        patient.setDateOfBirth(Validations.requireBirthDate(dob));
        patient.setEmail(email == null || email.isBlank() ? null : Validations.requireEmail(email));
        patient.setPhone(Validations.optionalPhone(phone));
        patient.setInsuranceProvider(Validations.optionalText(insurance, "Insurance provider", 120));
        patient.setPrimaryProvider(primaryProvider);
        patient.setActive(true);
        em.persist(patient);
        em.flush();
        if (primaryProvider != null) {
            ensureCareTeam(patient, primaryProvider);
        }
        return patient;
    }
}
