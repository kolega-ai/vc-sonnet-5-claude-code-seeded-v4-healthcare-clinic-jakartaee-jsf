package com.clinic.web;

import com.clinic.domain.Appointment;
import com.clinic.domain.AuditEvent;
import com.clinic.domain.DocumentCategory;
import com.clinic.domain.DocumentStatus;
import com.clinic.domain.MessageThread;
import com.clinic.domain.Patient;
import com.clinic.domain.PatientDocument;
import com.clinic.domain.Prescription;
import com.clinic.domain.PrescriptionStatus;
import com.clinic.domain.Provider;
import com.clinic.domain.RefillRequest;
import com.clinic.domain.VisitSummary;
import com.clinic.security.CurrentUser;
import com.clinic.service.AppointmentService;
import com.clinic.service.AuditQueryService;
import com.clinic.service.DocumentService;
import com.clinic.service.MessageService;
import com.clinic.service.PatientService;
import com.clinic.service.PrescriptionService;
import com.clinic.service.ProviderService;
import com.clinic.service.VisitSummaryService;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * The clinical patient chart: demographics, appointments, visit summaries,
 * prescriptions, messages, documents and the per-patient access history.
 *
 * <p>The chart is opened through {@code PatientService.openChart}, which
 * performs the ownership/scope check in the shared guard and writes the
 * chart-read audit event. Each tab then loads its own data through services
 * that re-check the same scope; nothing here trusts the fact that the page
 * rendered.</p>
 */
@Named("patientChart")
@ViewScoped
public class PatientChartBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private PatientService patientService;

    @EJB
    private AppointmentService appointmentService;

    @EJB
    private VisitSummaryService visitSummaryService;

    @EJB
    private PrescriptionService prescriptionService;

    @EJB
    private DocumentService documentService;

    @EJB
    private MessageService messageService;

    @EJB
    private AuditQueryService auditQueryService;

    @EJB
    private ProviderService providerService;

    private Long patientId;
    private Patient patient;
    private boolean loaded;

    private List<Appointment> appointments = List.of();
    private List<VisitSummary> summaries = List.of();
    private List<Prescription> prescriptions = List.of();
    private List<RefillRequest> refills = List.of();
    private List<PatientDocument> documents = List.of();
    private List<MessageThread> threads = List.of();
    private List<AuditEvent> accessHistory = List.of();

    // Demographic editing (front desk / admin).
    private Patient form = new Patient();

    // Prescription authoring (clinician).
    private String medicationName;
    private String dosage;
    private String instructions;
    private int quantity = 28;
    private int refillsAllowed;
    private LocalDate expiresAt = LocalDate.now().plusMonths(6);

    private Long formPrimaryProviderId;
    private List<Provider> providerOptions = List.of();
    private String saveFailureDetail;

    // Document upload (staff).
    private String uploadCategory = DocumentCategory.LAB_RESULT.getCode();
    private String uploadTitle;
    private transient Part upload;

    /** Called from {@code <f:viewAction>} so the id is validated before render. */
    public void load() {
        if (patientId == null) {
            patientId = Faces.longParam("patientId");
        }
        if (patientId == null) {
            Faces.error("No patient was specified.");
            return;
        }
        try {
            patient = patientService.openChart(patientId);
            copyToForm();
            appointments = appointmentService.forPatient(patientId);
            documents = documentService.forPatient(patientId);
            threads = messageService.threadsForPatient(patientId);
            accessHistory = auditQueryService.forPatient(patientId, 60);
            if (currentUser.isStaff()) {
                providerOptions = providerService.listActiveProviders();
            }
            if (!currentUser.isFrontDesk()) {
                summaries = currentUser.isPatient()
                        ? visitSummaryService.releasedForPatient(patientId)
                        : visitSummaryService.allForPatient(patientId);
                prescriptions = prescriptionService.forPatient(patientId);
                refills = prescriptionService.refillsForPatient(patientId);
            }
            loaded = true;
        } catch (RuntimeException e) {
            loaded = false;
            Faces.handle(e);
        }
    }

    private void copyToForm() {
        form = new Patient();
        form.setFirstName(patient.getFirstName());
        form.setLastName(patient.getLastName());
        form.setDateOfBirth(patient.getDateOfBirth());
        form.setGender(patient.getGender());
        form.setPhone(patient.getPhone());
        form.setEmail(patient.getEmail());
        form.setAddressLine1(patient.getAddressLine1());
        form.setAddressLine2(patient.getAddressLine2());
        form.setCity(patient.getCity());
        form.setStateRegion(patient.getStateRegion());
        form.setPostalCode(patient.getPostalCode());
        form.setEmergencyContactName(patient.getEmergencyContactName());
        form.setEmergencyContactPhone(patient.getEmergencyContactPhone());
        form.setEmergencyContactRel(patient.getEmergencyContactRel());
        form.setInsuranceProvider(patient.getInsuranceProvider());
        form.setInsuranceMemberId(patient.getInsuranceMemberId());
        form.setInsuranceGroup(patient.getInsuranceGroup());
        form.setAllergies(patient.getAllergies());
        formPrimaryProviderId = patient.getPrimaryProvider() == null
                ? null : patient.getPrimaryProvider().getId();
    }

    // ---------------------------------------------------------- demographics

    /** Front desk and admins maintain demographics; clinicians do not. */
    public boolean isCanEditDemographics() {
        return currentUser.isFrontDesk() || currentUser.isClinicAdmin();
    }

    public String saveDemographics() {
        try {
            saveFailureDetail = null;
            if (formPrimaryProviderId != null) {
                form.setPrimaryProvider(providerService.requireProvider(formPrimaryProviderId));
            }
            patientService.updateDemographics(patientId, form);
            Faces.success("Demographics updated.");
            load();
        } catch (RuntimeException e) {
            saveFailureDetail = describeFailure(e);
            Faces.handle(e);
        }
        return null;
    }

    /** Walks the cause chain so the front desk can quote the reason to support. */
    private static String describeFailure(RuntimeException exception) {
        StringBuilder detail = new StringBuilder(exception.toString());
        Throwable cause = exception.getCause();
        int depth = 0;
        while (cause != null && depth++ < 6) {
            detail.append(" <- ").append(cause);
            cause = cause.getCause();
        }
        return detail.toString();
    }

    // ----------------------------------------------------- visit summaries

    public boolean isCanAuthorClinical() {
        return currentUser.isClinician();
    }

    public String createDraft(Appointment appointment) {
        try {
            VisitSummary summary = visitSummaryService.createDraft(appointment.getId());
            Faces.success("Draft visit summary created.");
            return "/clinician/visit-summary.xhtml?faces-redirect=true&id=" + summary.getId();
        } catch (RuntimeException e) {
            Faces.handle(e);
            return null;
        }
    }

    public boolean canWriteSummary(Appointment appointment) {
        if (!currentUser.isClinician() || appointment == null) {
            return false;
        }
        Long providerId = currentUser.getProviderId();
        if (providerId == null || !providerId.equals(appointment.getProvider().getId())) {
            return false;
        }
        boolean visitStarted = appointment.getCheckedInAt() != null
                || appointment.getCompletedAt() != null;
        return visitStarted && summaries.stream()
                .noneMatch(s -> s.getAppointment() != null
                        && s.getAppointment().getId().equals(appointment.getId()));
    }

    // ------------------------------------------------------- prescriptions

    public String createPrescription() {
        try {
            prescriptionService.create(patientId, medicationName, dosage, instructions,
                    quantity, refillsAllowed, expiresAt);
            Faces.success("Prescription issued.");
            medicationName = null;
            dosage = null;
            instructions = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String discontinue(Prescription prescription) {
        try {
            prescriptionService.changeStatus(prescription.getId(),
                    PrescriptionStatus.DISCONTINUED, "Discontinued from the patient chart");
            Faces.success("Prescription discontinued.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    // ------------------------------------------------------------ documents

    public List<DocumentCategory> getUploadCategories() {
        return Arrays.asList(DocumentCategory.values());
    }

    public String uploadDocument() {
        if (upload == null || upload.getSize() == 0) {
            Faces.error("Choose a file to upload.");
            return null;
        }
        try (InputStream in = upload.getInputStream()) {
            documentService.upload(patientId, DocumentCategory.fromCode(uploadCategory),
                    uploadTitle, upload.getSubmittedFileName(), upload.getContentType(),
                    upload.getSize(), in);
            Faces.success("Document added to the chart.");
            uploadTitle = null;
            upload = null;
            load();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String verifyDocument(PatientDocument document) {
        return reviewDocument(document, DocumentStatus.VERIFIED, "Checked against the record.");
    }

    public String archiveDocument(PatientDocument document) {
        return reviewDocument(document, DocumentStatus.ARCHIVED, "Archived from the chart.");
    }

    private String reviewDocument(PatientDocument document, DocumentStatus target, String note) {
        try {
            documentService.review(document.getId(), target, note);
            Faces.success("Document " + target.getLabel().toLowerCase() + ".");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String downloadUrl(PatientDocument document) {
        return Faces.contextPath() + "/api/secure/documents/" + document.getId() + "/download";
    }

    public String summaryExportUrl() {
        return Faces.contextPath() + "/api/secure/exports/visit-summaries.json?patientId="
                + patientId;
    }

    // ------------------------------------------------------------ accessors

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }

    public Patient getPatient() {
        return patient;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public List<Appointment> getAppointments() {
        return appointments;
    }

    public List<VisitSummary> getSummaries() {
        return summaries;
    }

    public List<Prescription> getPrescriptions() {
        return prescriptions;
    }

    public List<RefillRequest> getRefills() {
        return refills;
    }

    public List<PatientDocument> getDocuments() {
        return documents;
    }

    public List<MessageThread> getThreads() {
        return threads;
    }

    public List<AuditEvent> getAccessHistory() {
        return accessHistory;
    }

    public Patient getForm() {
        return form;
    }

    public String getMedicationName() {
        return medicationName;
    }

    public void setMedicationName(String medicationName) {
        this.medicationName = medicationName;
    }

    public String getDosage() {
        return dosage;
    }

    public void setDosage(String dosage) {
        this.dosage = dosage;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getRefillsAllowed() {
        return refillsAllowed;
    }

    public void setRefillsAllowed(int refillsAllowed) {
        this.refillsAllowed = refillsAllowed;
    }

    public LocalDate getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDate expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getUploadCategory() {
        return uploadCategory;
    }

    public void setUploadCategory(String uploadCategory) {
        this.uploadCategory = uploadCategory;
    }

    public String getUploadTitle() {
        return uploadTitle;
    }

    public void setUploadTitle(String uploadTitle) {
        this.uploadTitle = uploadTitle;
    }

    public Long getFormPrimaryProviderId() {
        return formPrimaryProviderId;
    }

    public void setFormPrimaryProviderId(Long formPrimaryProviderId) {
        this.formPrimaryProviderId = formPrimaryProviderId;
    }

    public List<Provider> getProviderOptions() {
        return providerOptions;
    }

    public String getSaveFailureDetail() {
        return saveFailureDetail;
    }

    public Part getUpload() {
        return upload;
    }

    public void setUpload(Part upload) {
        this.upload = upload;
    }
}
