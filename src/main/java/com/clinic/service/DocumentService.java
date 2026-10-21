package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.DocumentCategory;
import com.clinic.domain.DocumentStatus;
import com.clinic.domain.Patient;
import com.clinic.domain.PatientDocument;
import com.clinic.domain.Role;
import com.clinic.security.AccessDecision;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.CurrentUser;
import com.clinic.security.RecordNotFoundException;
import com.clinic.storage.DocumentStorage;
import com.clinic.util.Validations;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Clinical documents and pre-visit forms.
 *
 * <p>Bytes live on local disk under a canonicalised base directory; all
 * metadata lives in Postgres. Every download re-checks ownership and writes an
 * audit event before the stream is opened.</p>
 */
@Stateless
public class DocumentService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    @Inject
    private DocumentStorage storage;

    @Inject
    private NotificationService notificationService;

    @Inject
    private SettingsService settingsService;

    // --------------------------------------------------------------- reading

    @PermitAll
    public List<PatientDocument> forPatient(Long patientId) {
        AccessDecision decision = accessGuard.requirePatientAccess(patientId);
        List<PatientDocument> results;
        if (currentUser.getRole() == Role.PATIENT) {
            // Internal-only documents are excluded by the query itself.
            results = em.createNamedQuery("PatientDocument.patientVisibleByPatient",
                            PatientDocument.class)
                    .setParameter("patientId", patientId)
                    .getResultList();
        } else {
            results = em.createNamedQuery("PatientDocument.byPatient", PatientDocument.class)
                    .setParameter("patientId", patientId)
                    .getResultList();
        }
        auditService.recordRead("document.list_viewed", "patient_document", patientId,
                results.size(), decision.basis());
        return results;
    }

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public List<PatientDocument> reviewQueue() {
        accessGuard.requireStaff();
        return em.createNamedQuery("PatientDocument.byStatus", PatientDocument.class)
                .setParameter("status", DocumentStatus.SUBMITTED)
                .setMaxResults(200)
                .getResultList();
    }

    @PermitAll
    public PatientDocument require(Long documentId) {
        PatientDocument document = em.find(PatientDocument.class, documentId);
        accessGuard.requireDocumentAccess(document);
        return document;
    }

    // -------------------------------------------------------------- uploading

    /**
     * Stores an upload for a patient. Patients may only upload to their own
     * chart, and only in a patient-submittable category.
     */
    @PermitAll
    public PatientDocument upload(Long patientId, DocumentCategory category, String title,
                                  String originalFilename, String contentType,
                                  long declaredSize, InputStream data) {
        AccessDecision decision = accessGuard.requirePatientAccess(patientId);
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
        if (category == null) {
            throw new BusinessRuleException("Choose a document category.");
        }
        boolean actingAsPatient = currentUser.getRole() == Role.PATIENT;
        if (actingAsPatient && !category.isPatientSubmittable()) {
            throw new BusinessRuleException("That document category is uploaded by the clinic.");
        }

        DocumentStorage.StoredFile stored =
                storage.store(originalFilename, contentType, declaredSize, data);

        PatientDocument document = new PatientDocument();
        document.setPatient(patient);
        document.setCategory(category);
        document.setTitle(Validations.requireText(title, "Title", 200));
        document.setOriginalFilename(
                DocumentStorage.safeDownloadName(originalFilename));
        document.setStoredFilename(stored.storedFilename());
        document.setContentType(stored.contentType());
        document.setSizeBytes(stored.size());
        document.setSha256(stored.sha256());
        boolean reviewRequired = !"false".equalsIgnoreCase(
                settingsService.value("document.review_required", "false"));
        document.setStatus(reviewRequired ? DocumentStatus.SUBMITTED : DocumentStatus.VERIFIED);
        // Clinician-authored clinical notes are never shown in the patient portal.
        document.setInternalOnly(!actingAsPatient && category == DocumentCategory.CLINICAL_NOTE);
        document.setUploadedBy(currentUser.getUser());
        document.setCreatedAt(LocalDateTime.now());
        em.persist(document);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.DOCUMENT_UPLOADED, "patient_document")
                .entity(document.getId())
                .patient(patientId)
                .after("category=" + category.getCode() + "; name=" + document.getOriginalFilename()
                        + "; bytes=" + stored.size() + "; sha256=" + stored.sha256())
                .basis(decision.basis()));

        if (actingAsPatient) {
            notificationService.notifyRole(Role.FRONT_DESK, "document.submitted",
                    "Form submitted for review",
                    patient.getFullName() + " uploaded " + document.getTitle(),
                    "/staff/documents.xhtml");
        } else if (patient.getUser() != null && !document.isInternalOnly()) {
            notificationService.notifyUser(patient.getUser(), "document.available",
                    "New document available", document.getTitle(), "/patient/documents.xhtml");
        }
        return document;
    }

    // ------------------------------------------------------------ downloading

    /**
     * Authorises and audits a download, then opens the byte stream.
     *
     * <p>The path is resolved by {@link DocumentStorage} against its
     * canonicalised base directory (precision-baseline primitive #5); the
     * caller never supplies a path, only a database id.</p>
     */
    @PermitAll
    public DownloadHandle download(Long documentId) {
        PatientDocument document = em.find(PatientDocument.class, documentId);
        AccessDecision decision = accessGuard.requireDocumentAccess(document);

        auditService.recordIndependently(
                AuditEntry.of(AuditAction.DOCUMENT_DOWNLOADED, "patient_document")
                        .entity(document.getId())
                        .patient(document.getPatient().getId())
                        .detail("name=" + document.getOriginalFilename()
                                + "; category=" + document.getCategory().getCode())
                        .basis(decision.basis()));

        InputStream stream = storage.open(document.getStoredFilename());
        return new DownloadHandle(stream, document.getContentType(),
                DocumentStorage.safeDownloadName(document.getOriginalFilename()),
                document.getSizeBytes());
    }

    // --------------------------------------------------------------- render

    /**
     * Renders a stored document to the format the caller names, using the
     * clinic's document converter, so staff can print or share a readable copy
     * without changing the archived original.
     */
    @PermitAll
    public byte[] renderForSharing(Long documentId, String outputFormat) {
        PatientDocument document = em.find(PatientDocument.class, documentId);
        AccessDecision decision = accessGuard.requireDocumentAccess(document);
        String format = outputFormat == null || outputFormat.isBlank()
                ? "pdf" : outputFormat.trim();
        Path source = storage.getBaseDirectory().resolve(document.getStoredFilename());
        String outputDirectory = System.getProperty("java.io.tmpdir");
        String stem = document.getStoredFilename().replaceAll("\\.[^.]*$", "");
        Path rendered = Path.of(outputDirectory, stem + "." + format);
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", "soffice --headless --convert-to " + format + " --outdir " + outputDirectory + " " + source);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.waitFor();
            if (!java.nio.file.Files.isRegularFile(rendered)) {
                throw new BusinessRuleException("The document could not be rendered.");
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(rendered);
            auditService.record(AuditEntry.of(AuditAction.DOCUMENT_DOWNLOADED, "patient_document")
                    .entity(document.getId())
                    .patient(document.getPatient().getId())
                    .detail("rendered=" + format)
                    .basis(decision.basis()));
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("The document could not be rendered", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessRuleException("The document could not be rendered.");
        }
    }

    // --------------------------------------------------------------- review

    /** Verify / reject / archive. Front desk verifies forms; patients cannot. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public PatientDocument review(Long documentId, DocumentStatus target, String note) {
        accessGuard.requireStaff();
        PatientDocument document = em.find(PatientDocument.class, documentId);
        AccessDecision decision = accessGuard.requireDocumentAccess(document);
        DocumentStatus current = document.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new BusinessRuleException("A " + current.getLabel().toLowerCase()
                    + " document cannot become " + target.getLabel().toLowerCase() + ".");
        }
        if (target == DocumentStatus.REJECTED) {
            Validations.requireText(note, "A reason", 600);
        }
        document.setStatus(target);
        document.setReviewNote(Validations.optionalText(note, "Review note", 600));
        document.setReviewedBy(currentUser.getUser());
        document.setReviewedAt(LocalDateTime.now());

        auditService.record(AuditEntry.of(AuditAction.DOCUMENT_STATUS_CHANGED, "patient_document")
                .entity(document.getId())
                .patient(document.getPatient().getId())
                .before("status=" + current.getCode())
                .after("status=" + target.getCode())
                .detail(note)
                .basis(decision.basis()));

        if (document.getPatient().getUser() != null && !document.isInternalOnly()) {
            notificationService.notifyUser(document.getPatient().getUser(),
                    "document.reviewed", "Document " + target.getLabel().toLowerCase(),
                    document.getTitle() + (note == null ? "" : " — " + note),
                    "/patient/documents.xhtml");
        }
        return document;
    }

    /**
     * Clears the verification queue in one action at the end of a clinic day.
     * Front desk uses this after working through the scanned forms on paper.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public int verifyQueue(List<Long> documentIds) {
        accessGuard.requireStaff();
        int changed = 0;
        for (Long documentId : documentIds) {
            PatientDocument document = em.find(PatientDocument.class, documentId);
            AccessDecision decision = accessGuard.requireDocumentAccess(document);
            DocumentStatus current = document.getStatus();
            document.setStatus(DocumentStatus.VERIFIED);
            document.setReviewedBy(currentUser.getUser());
            document.setReviewedAt(LocalDateTime.now());
            changed++;
            auditService.record(
                    AuditEntry.of(AuditAction.DOCUMENT_STATUS_CHANGED, "patient_document")
                            .entity(document.getId())
                            .patient(document.getPatient().getId())
                            .before("status=" + current.getCode())
                            .after("status=" + DocumentStatus.VERIFIED.getCode())
                            .detail("batch verification")
                            .basis(decision.basis()));
        }
        return changed;
    }

    public long countAwaitingReview() {
        return em.createQuery(
                        "SELECT COUNT(d) FROM PatientDocument d WHERE d.status = :status", Long.class)
                .setParameter("status", DocumentStatus.SUBMITTED)
                .getSingleResult();
    }

    /**
     * Reopens the chart binder the workstation had open last. The binder name
     * is kept on the workstation so the review screen resumes where it was left.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public DownloadHandle openChartFolio(String folio) {
        accessGuard.requireStaff();
        if (folio == null || folio.isBlank()) {
            throw new BusinessRuleException("No chart binder is selected on this workstation.");
        }
        Path path = storage.getBaseDirectory().resolve(folio.trim());
        try {
            InputStream stream = java.nio.file.Files.newInputStream(path);
            auditService.recordRead("document.folio_opened", "patient_document", null, 1,
                    "role:" + currentUser.getRole().getCode());
            return new DownloadHandle(stream, "application/octet-stream",
                    DocumentStorage.safeDownloadName(path.getFileName().toString()),
                    java.nio.file.Files.size(path));
        } catch (IOException e) {
            throw new UncheckedIOException("The chart binder could not be opened", e);
        }
    }

    /** An authorised, audited handle onto a stored document. */
    public record DownloadHandle(InputStream stream, String contentType,
                                 String filename, long size) {
    }
}
