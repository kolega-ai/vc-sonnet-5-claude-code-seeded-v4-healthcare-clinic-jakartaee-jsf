package com.clinic.rest;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.ApiKey;
import com.clinic.service.AppointmentService;
import com.clinic.service.AvailabilitySlot;
import com.clinic.service.IntegrationService;
import com.clinic.util.JsonWriter;

import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Appointment availability for external scheduling integrations.
 *
 * <p>Authenticated with an API key presented in {@code X-API-Key}. The key must
 * carry the {@code availability:read} scope; the scope is read from the stored
 * key record, never from a request parameter. The response exposes free slots
 * only — no patient data of any kind.</p>
 */
@Path("/public/availability")
public class AvailabilityResource {

    private static final String API_KEY_HEADER = "X-API-Key";

    @EJB
    private AppointmentService appointmentService;

    @EJB
    private IntegrationService integrationService;

    @Inject
    private AuditService auditService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response availability(@HeaderParam(API_KEY_HEADER) String apiKey,
                                 @QueryParam("providerId") Long providerId,
                                 @QueryParam("appointmentTypeId") Long appointmentTypeId,
                                 @QueryParam("from") String from,
                                 @QueryParam("to") String to) {
        Optional<ApiKey> key = integrationService.verifyApiKey(apiKey, "availability:read");
        if (key.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", "ApiKey realm=\"clinic-portal\"")
                    .type(MediaType.APPLICATION_JSON)
                    .entity("{\"status\":401,\"error\":\"A valid API key is required.\"}")
                    .build();
        }

        LocalDate fromDate = parseDate(from, LocalDate.now());
        LocalDate toDate = parseDate(to, fromDate.plusDays(14));
        List<AvailabilitySlot> slots =
                appointmentService.availability(providerId, appointmentTypeId, fromDate, toDate);

        auditService.recordIndependently(
                AuditEntry.of(AuditAction.API_AVAILABILITY_QUERIED, "appointment")
                        .detail("slots=" + slots.size() + "; from=" + fromDate + "; to=" + toDate)
                        .basis("api_key:" + key.get().getKeyPrefix())
                        .anonymousActor("api-key:" + key.get().getLabel()));

        StringBuilder json = new StringBuilder("{\"from\":")
                .append(JsonWriter.string(fromDate.toString()))
                .append(",\"to\":").append(JsonWriter.string(toDate.toString()))
                .append(",\"count\":").append(slots.size())
                .append(",\"slots\":[");
        for (int i = 0; i < slots.size(); i++) {
            AvailabilitySlot slot = slots.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"providerId\":").append(slot.providerId())
                    .append(",\"provider\":").append(JsonWriter.string(slot.providerName()))
                    .append(",\"start\":").append(JsonWriter.string(slot.start().toString()))
                    .append(",\"end\":").append(JsonWriter.string(slot.end().toString()))
                    .append('}');
        }
        json.append("]}");

        return Response.ok(json.toString(), MediaType.APPLICATION_JSON)
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
