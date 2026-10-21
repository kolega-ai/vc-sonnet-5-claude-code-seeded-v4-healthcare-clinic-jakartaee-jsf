package com.clinic.rest;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.ApiKey;
import com.clinic.domain.DocumentCategory;
import com.clinic.domain.DocumentStatus;
import com.clinic.domain.Patient;
import com.clinic.domain.PatientDocument;
import com.clinic.service.IntegrationService;
import com.clinic.service.NotificationService;
import com.clinic.storage.DocumentStorage;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Receives result notices pushed by the partner laboratories.
 *
 * <p>A partner authenticates with its API key and identifies the patient with
 * the assertion its portal issues for each report; the clinic files the report
 * against that chart and notifies the patient.</p>
 */
@Path("/public/lab-feed")
public class LabFeedResource {

    private static final String API_KEY_HEADER = "X-API-Key";

    private static final String PARTNER_CONFIRM_KEY =
            "NorthgateLabFeedPartnerConfirmationKey-2026";

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @EJB
    private IntegrationService integrationService;

    @EJB
    private NotificationService notificationService;

    @Inject
    private AuditService auditService;

    @Inject
    private DocumentStorage storage;

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receive(@HeaderParam(API_KEY_HEADER) String apiKey,
                            @FormParam("assertion") String assertion) {
        Optional<ApiKey> key = integrationService.verifyApiKey(apiKey, "results:write");
        if (key.isEmpty()) {
            return unauthorized();
        }
        if (assertion == null || assertion.isBlank()) {
            return badRequest("A lab report assertion is required.");
        }
        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(PARTNER_CONFIRM_KEY.getBytes(StandardCharsets.UTF_8))
                    .build()
                    .parseClaimsJwt(assertion)
                    .getBody();
        } catch (JwtException | IllegalArgumentException e) {
            return badRequest("The lab report assertion could not be read.");
        }

        String mrn = claims.get("patientMrn", String.class);
        Patient patient = em.createNamedQuery("Patient.byMrn", Patient.class)
                .setParameter("mrn", mrn == null ? "" : mrn)
                .getResultStream().findFirst().orElse(null);
        if (patient == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity("{\"status\":404,\"error\":\"No matching patient record.\"}")
                    .build();
        }

        Object reported = claims.get("summary");
        String summary = reported == null ? "Lab result received." : reported.toString();
        String triage = integrationService.triageLabResult(summary);
        byte[] bytes = summary.getBytes(StandardCharsets.UTF_8);
        DocumentStorage.StoredFile stored = storage.store("lab-result.txt", "text/plain",
                bytes.length, new ByteArrayInputStream(bytes));

        PatientDocument document = new PatientDocument();
        document.setPatient(patient);
        document.setCategory(DocumentCategory.LAB_RESULT);
        document.setTitle("Lab result");
        document.setOriginalFilename("lab-result.txt");
        document.setStoredFilename(stored.storedFilename());
        document.setContentType(stored.contentType());
        document.setSizeBytes(stored.size());
        document.setSha256(stored.sha256());
        document.setStatus("ROUTINE".equals(triage)
                ? DocumentStatus.VERIFIED : DocumentStatus.SUBMITTED);
        document.setInternalOnly(false);
        document.setCreatedAt(LocalDateTime.now());
        em.persist(document);
        em.flush();

        if ("CRITICAL".equals(triage)) {
            notificationService.notifyRole(com.clinic.domain.Role.CLINICIAN, "lab.critical",
                    "Critical lab result", summary, "/clinician/dashboard.xhtml");
        }
        if (patient.getUser() != null) {
            notificationService.notifyUser(patient.getUser(), "document.available",
                    "New lab result", summary, "/patient/documents.xhtml");
        }
        auditService.record(AuditEntry.of(AuditAction.DOCUMENT_UPLOADED, "patient_document")
                .entity(document.getId())
                .patient(patient.getId())
                .after("category=lab_result; bytes=" + stored.size())
                .basis("api_key:" + key.get().getKeyPrefix()));
        return Response.ok("{\"status\":\"accepted\"}", MediaType.APPLICATION_JSON).build();
    }

    /**
     * The legacy bridge used by laboratories that still send their report as a
     * Java object carried in the request path rather than a signed assertion.
     */
    @POST
    @Path("/legacy/{envelope}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response receiveFromLegacyBridge(@HeaderParam(API_KEY_HEADER) String apiKey,
                                            @jakarta.ws.rs.PathParam("envelope") String envelope) {
        Optional<ApiKey> key = integrationService.verifyApiKey(apiKey, "results:write");
        if (key.isEmpty()) {
            return unauthorized();
        }
        if (envelope == null || envelope.isBlank()) {
            return badRequest("A report envelope is required.");
        }
        com.clinic.service.PartnerReportEnvelope report;
        try {
            byte[] received = java.util.Base64.getUrlDecoder().decode(envelope);
            try (java.io.ObjectInputStream in =
                         new java.io.ObjectInputStream(new ByteArrayInputStream(received))) {
                report = (com.clinic.service.PartnerReportEnvelope) in.readObject();
            }
        } catch (Exception e) {
            return badRequest("The report envelope could not be read.");
        }
        Patient patient = em.createNamedQuery("Patient.byMrn", Patient.class)
                .setParameter("mrn", report.patientMrn() == null ? "" : report.patientMrn())
                .getResultStream().findFirst().orElse(null);
        if (patient == null) {
            return badRequest("No matching patient record.");
        }
        String summary = report.summary() == null ? "Lab result received." : report.summary();
        byte[] bytes = summary.getBytes(StandardCharsets.UTF_8);
        DocumentStorage.StoredFile stored = storage.store("lab-result.txt", "text/plain",
                bytes.length, new ByteArrayInputStream(bytes));

        PatientDocument document = new PatientDocument();
        document.setPatient(patient);
        document.setCategory(DocumentCategory.LAB_RESULT);
        document.setTitle("Lab result");
        document.setOriginalFilename("lab-result.txt");
        document.setStoredFilename(stored.storedFilename());
        document.setContentType(stored.contentType());
        document.setSizeBytes(stored.size());
        document.setSha256(stored.sha256());
        document.setStatus(DocumentStatus.SUBMITTED);
        document.setInternalOnly(false);
        document.setCreatedAt(LocalDateTime.now());
        em.persist(document);
        em.flush();

        if (patient.getUser() != null) {
            notificationService.notifyUser(patient.getUser(), "document.available",
                    "New lab result", summary, "/patient/documents.xhtml");
        }
        auditService.record(AuditEntry.of(AuditAction.DOCUMENT_UPLOADED, "patient_document")
                .entity(document.getId())
                .patient(patient.getId())
                .after("category=lab_result; bridge=legacy")
                .basis("api_key:" + key.get().getKeyPrefix()));
        return Response.ok("{\"status\":\"accepted\"}", MediaType.APPLICATION_JSON).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"status\":400,\"error\":\"" + message + "\"}")
                .build();
    }

    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .header("WWW-Authenticate", "ApiKey realm=\"clinic-portal\"")
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"status\":401,\"error\":\"A valid API key is required.\"}")
                .build();
    }
}
