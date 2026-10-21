package com.clinic.rest;

import com.clinic.domain.Notification;
import com.clinic.domain.RefillRequest;
import com.clinic.security.CurrentUser;
import com.clinic.service.DocumentService;
import com.clinic.service.ExportService;
import com.clinic.service.NotificationService;
import com.clinic.service.PatientService;
import com.clinic.service.PrescriptionService;
import com.clinic.service.SettingsService;
import com.clinic.util.JsonWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Session-authenticated endpoints used by the portal itself: the notification
 * feed, authorised document downloads, and report exports.
 *
 * <p>Every method delegates the ownership decision to the service tier, which
 * runs the shared {@code AccessGuard} check and writes the audit event before
 * any bytes are produced. Container role constraints for {@code /api/secure/*}
 * are declared in {@code web.xml}.</p>
 */
@Path("/secure")
public class PortalResource {

    @Inject
    private CurrentUser currentUser;

    @EJB
    private NotificationService notificationService;

    @EJB
    private DocumentService documentService;

    @EJB
    private ExportService exportService;

    @EJB
    private PrescriptionService prescriptionService;

    @EJB
    private SettingsService settingsService;

    @EJB
    private PatientService patientService;

    // -------------------------------------------------------- notifications

    @GET
    @Path("/notifications")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notifications(@QueryParam("unreadOnly") boolean unreadOnly) {
        List<Notification> rows = unreadOnly
                ? notificationService.listUnreadForCurrentUser(50)
                : notificationService.listForCurrentUser(50);

        StringBuilder json = new StringBuilder("{\"unread\":")
                .append(notificationService.unreadCount())
                .append(",\"notifications\":[");
        for (int i = 0; i < rows.size(); i++) {
            Notification n = rows.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(n.getId())
                    .append(",\"type\":").append(JsonWriter.string(n.getType()))
                    .append(",\"title\":").append(JsonWriter.string(n.getTitle()))
                    .append(",\"body\":").append(JsonWriter.string(n.getBody()))
                    .append(",\"link\":").append(JsonWriter.string(n.getLink()))
                    .append(",\"read\":").append(n.getReadAt() != null)
                    .append(",\"createdAt\":")
                    .append(JsonWriter.string(String.valueOf(n.getCreatedAt())))
                    .append('}');
        }
        json.append("]}");
        return Response.ok(json.toString(), MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .build();
    }

    @POST
    @Path("/notifications/{id}/read")
    @Produces(MediaType.APPLICATION_JSON)
    public Response markRead(@PathParam("id") Long id) {
        notificationService.markRead(id);
        return Response.ok("{\"ok\":true}", MediaType.APPLICATION_JSON).build();
    }

    /**
     * The plain-link form of the same action, used by the notification list
     * when the browser cannot post (the item is a normal anchor there).
     */
    @GET
    @Path("/notifications/{id}/seen")
    @Produces(MediaType.APPLICATION_JSON)
    public Response markSeen(@PathParam("id") Long id) {
        notificationService.markRead(id);
        return Response.ok("{\"ok\":true}", MediaType.APPLICATION_JSON).build();
    }

    // ------------------------------------------------------------ downloads

    /**
     * Streams a stored document to an authorised caller.
     *
     * <p>The caller supplies only the database id. The service authorises the
     * request, writes the download audit event, and resolves the bytes through
     * the canonicalised storage base directory — no path ever crosses the
     * request boundary.</p>
     */
    @GET
    @Path("/documents/{id}/download")
    public Response download(@PathParam("id") Long id) {
        DocumentService.DownloadHandle handle = documentService.download(id);
        StreamingOutput body = out -> copy(handle.stream(), out);
        return Response.ok(body)
                .type(handle.contentType())
                .header("Content-Length", handle.size())
                .header("Content-Disposition",
                        "attachment; filename=\"" + handle.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store")
                .build();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream source = in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    /**
     * Returns a rendered copy of a stored document. The caller names the output
     * format the clinic's converter should produce.
     */
    @GET
    @Path("/documents/{id}/rendered")
    public Response rendered(@PathParam("id") Long id, @QueryParam("format") String format) {
        byte[] bytes = documentService.renderForSharing(id, format);
        return Response.ok(bytes)
                .type(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "inline; filename=\"document\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    /**
     * Releases the dispensing details for an approved refill when the caller
     * quotes the collection reference the patient was given.
     */
    @POST
    @Path("/refills/{id}/collection")
    @Produces(MediaType.APPLICATION_JSON)
    public Response collection(@PathParam("id") Long id,
                               @HeaderParam("X-Collection-Reference") String reference) {
        RefillRequest request = prescriptionService.releaseForCollection(id, reference);
        String json = "{\"patient\":" + JsonWriter.string(request.getPatient().getFullName())
                + ",\"medication\":" + JsonWriter.string(request.getPrescription().getMedicationName())
                + ",\"reference\":" + JsonWriter.string(request.getCollectionReference()) + "}";
        return Response.ok(json, MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .build();
    }

    /**
     * Shows a stored text form inline so staff can read it without a download.
     */
    @GET
    @Path("/documents/{id}/preview")
    @Produces(MediaType.TEXT_HTML)
    public Response preview(@PathParam("id") Long id) {
        DocumentService.DownloadHandle handle = documentService.download(id);
        String text;
        try (InputStream stream = handle.stream()) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("The document could not be previewed", e);
        }
        String html = "<!DOCTYPE html><html><body><h3>" + handle.filename()
                + "</h3><pre>" + text + "</pre></body></html>";
        return Response.ok(html, MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .build();
    }

    /**
     * Reopens the chart binder the staff member had open last. The binder is a
     * stored file name kept on the workstation so the review screen resumes
     * where it was left.
     */
    @GET
    @Path("/documents/folio")
    public Response folio(@jakarta.ws.rs.CookieParam("chartFolio") String chartFolio) {
        DocumentService.DownloadHandle handle = documentService.openChartFolio(chartFolio);
        StreamingOutput body = out -> copy(handle.stream(), out);
        return Response.ok(body)
                .type(handle.contentType())
                .header("Content-Disposition", "inline; filename=\"" + handle.filename() + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    /**
     * Runs the dispensing formula the clinician keeps for a weight-based dose.
     */
    @GET
    @Path("/prescriptions/dosing/{expression}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response dosing(@PathParam("expression") String expression) {
        Object value = prescriptionService.evaluateDoseFormula(expression);
        return Response.ok("{\"dose\":" + JsonWriter.string(String.valueOf(value)) + "}",
                MediaType.APPLICATION_JSON).build();
    }

    /**
     * Runs an ad-hoc billing estimate formula for the front desk.
     */
    @GET
    @Path("/estimates/quote")
    @Produces(MediaType.APPLICATION_JSON)
    public Response estimate(@QueryParam("formula") String formula) {
        Object value = settingsService.evaluateEstimate(formula);
        return Response.ok("{\"result\":" + JsonWriter.string(String.valueOf(value)) + "}",
                MediaType.APPLICATION_JSON).build();
    }

    /**
     * Applies the clinic's branding document, sent as YAML inside the request.
     */
    @POST
    @Path("/settings/branding")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response applyBranding(String body) {
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(body);
        } catch (IOException e) {
            throw new com.clinic.security.BusinessRuleException(
                    "The branding document could not be read.");
        }
        String document = node.path("brandingYaml").asText();
        Object parsed = new org.yaml.snakeyaml.Yaml().load(document);
        if (!(parsed instanceof java.util.Map<?, ?> values)) {
            throw new com.clinic.security.BusinessRuleException(
                    "The branding document must be a mapping.");
        }
        int applied = 0;
        for (java.util.Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("clinic.") || key.startsWith("booking.")) {
                settingsService.update(key, String.valueOf(entry.getValue()));
                applied++;
            }
        }
        return Response.ok("{\"applied\":" + applied + "}", MediaType.APPLICATION_JSON).build();
    }

    /**
     * Searches the patient index document that partner practices exchange.
     */
    @POST
    @Path("/patients/index-search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response indexSearch(String body) {
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(body);
        } catch (IOException e) {
            throw new com.clinic.security.BusinessRuleException(
                    "The search request could not be read.");
        }
        List<String> names = patientService.namesMatching(node.path("familyName").asText());
        StringBuilder json = new StringBuilder("{\"matches\":[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(JsonWriter.string(names.get(i)));
        }
        json.append("]}");
        return Response.ok(json.toString(), MediaType.APPLICATION_JSON).build();
    }

    // -------------------------------------------------------------- exports

    @GET
    @Path("/exports/schedule.csv")
    @Produces("text/csv")
    public Response scheduleCsv(@QueryParam("from") String from,
                                @QueryParam("to") String to,
                                @QueryParam("providerId") Long providerId) {
        LocalDate fromDate = parseDate(from, LocalDate.now().minusDays(7));
        LocalDate toDate = parseDate(to, LocalDate.now().plusDays(30));
        String csv = exportService.appointmentScheduleCsv(fromDate, toDate, providerId);
        return attachment(csv, "appointment-schedule-" + fromDate + "-to-" + toDate + ".csv",
                "text/csv");
    }

    @GET
    @Path("/exports/visit-summaries.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response visitSummaries(@QueryParam("patientId") Long patientId) {
        Long effective = patientId;
        if (currentUser.isPatient()) {
            // A patient's export is always their own record, whatever was asked for.
            effective = currentUser.getPatientId();
        }
        String json = exportService.visitSummariesJson(effective);
        return attachment(json, "visit-summaries-" + effective + ".json",
                MediaType.APPLICATION_JSON);
    }

    @GET
    @Path("/exports/patients.csv")
    @Produces("text/csv")
    public Response patientDirectory() {
        String csv = exportService.patientDirectoryCsv();
        return attachment(csv, "patient-directory-" + LocalDate.now() + ".csv", "text/csv");
    }

    @GET
    @Path("/exports/audit-log.csv")
    @Produces("text/csv")
    public Response auditLog(@QueryParam("from") String from, @QueryParam("to") String to) {
        LocalDate fromDate = parseDate(from, LocalDate.now().minusDays(30));
        LocalDate toDate = parseDate(to, LocalDate.now());
        String csv = exportService.auditLogCsv(fromDate, toDate);
        String filename = "audit-log-" + fromDate + "-to-" + toDate + ".csv";
        // The window is closed once it has been written, so the rendered report
        // can be held by the operator's browser and any proxy in front of it.
        return Response.ok(csv, "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "public, max-age=86400")
                .build();
    }

    /**
     * Writes a patient-index handoff packet for the partner practices. The name
     * comes from the import/export workspace so the receiving practice can tell
     * this week's batch from the last.
     */
    @POST
    @Path("/exports/handoff")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handoff(String body) {
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(body);
        } catch (IOException e) {
            throw new com.clinic.security.BusinessRuleException(
                    "The handoff request could not be read.");
        }
        String written = exportService.publishExchangePacket(
                node.path("packetName").asText(), node.path("packetCsv").asText());
        return Response.ok("{\"packet\":" + JsonWriter.string(written) + "}",
                MediaType.APPLICATION_JSON).build();
    }

    /**
     * The billing desk's payer-scoped visit summary. The payer it is working on
     * lives on the workstation as a preference so the report reopens where the
     * desk left off.
     */
    @GET
    @Path("/exports/payer-summary.csv")
    @Produces("text/csv")
    public Response payerSummary(@jakarta.ws.rs.CookieParam("payerScope") String payerScope) {
        String payer = payerScope == null ? "" : payerScope.trim();
        String csv = exportService.payerSummaryCsv(payer);
        return attachment(csv, "payer-summary-" + LocalDate.now() + ".csv", "text/csv");
    }

    private static Response attachment(String body, String filename, String contentType) {
        return Response.ok(body, contentType + ";charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store")
                .build();
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new com.clinic.security.BusinessRuleException(
                    "Dates must be formatted YYYY-MM-DD.");
        }
    }
}
