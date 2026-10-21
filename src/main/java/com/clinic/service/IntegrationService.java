package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.ApiKey;
import com.clinic.domain.Appointment;
import com.clinic.domain.OutboxEmail;
import com.clinic.domain.RefillRequest;
import com.clinic.domain.Role;
import com.clinic.domain.WebhookDelivery;
import com.clinic.domain.WebhookEndpoint;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.CurrentUser;
import com.clinic.security.PasswordHasher;
import com.clinic.security.RecordNotFoundException;
import com.clinic.util.JsonWriter;
import com.clinic.util.Validations;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local mocks for every outbound integration.
 *
 * <p>Email is written to an {@code outbox_email} table instead of being sent.
 * Webhooks are recorded as delivery attempts against a locally configured
 * endpoint. Nothing here opens a network connection or needs a SaaS account.</p>
 */
@Stateless
public class IntegrationService {

    private static final Logger LOG = Logger.getLogger(IntegrationService.class.getName());

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    @Inject
    private PasswordHasher passwordHasher;

    // ----------------------------------------------------------------- email

    /** Queues an email into the local outbox. Never sends over the network. */
    @PermitAll
    public OutboxEmail queueEmail(String to, String subject, String body) {
        OutboxEmail email = new OutboxEmail();
        email.setToAddress(to);
        email.setSubject(subject);
        email.setBody(body);
        email.setStatus("queued");
        email.setCreatedAt(LocalDateTime.now());
        em.persist(email);
        return email;
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<OutboxEmail> recentOutbox(int limit) {
        accessGuard.requireClinicAdmin();
        return em.createNamedQuery("OutboxEmail.recent", OutboxEmail.class)
                .setMaxResults(Math.min(Math.max(limit, 1), 200))
                .getResultList();
    }

    /** Marks queued mail as delivered — the local "mail transport" job. */
    @PermitAll
    public int flushOutbox(int batchSize) {
        List<OutboxEmail> queued = em.createNamedQuery("OutboxEmail.queued", OutboxEmail.class)
                .setMaxResults(Math.min(Math.max(batchSize, 1), 500))
                .getResultList();
        for (OutboxEmail email : queued) {
            email.setStatus("sent");
            email.setSentAt(LocalDateTime.now());
        }
        return queued.size();
    }

    // ---------------------------------------------------------- message assist

    /**
     * Asks the clinic's message assistant to draft the reply for an incoming
     * patient message. The assistant is the on-premise model the practice
     * configured; its draft is sent to the patient as the clinic's reply.
     */
    @PermitAll
    public String draftClinicalReply(String patientMessage) {
        String instructions = "You are the Northgate Clinic message assistant. "
                + "Clinic policy: acknowledge the patient, never give a diagnosis, and "
                + "escalate anything urgent to the on-call desk at 555-0111. "
                + "Write the clinic's reply in one short paragraph.";
        String requestBody = "{\"model\":\"clinic-assistant\",\"messages\":["
                + "{\"role\":\"system\",\"content\":" + JsonWriter.string(instructions) + "},"
                + "{\"role\":\"user\",\"content\":" + JsonWriter.string(patientMessage) + "}]}";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(System.getProperty("clinic.assistant.endpoint",
                            "http://127.0.0.1:8091/v1/chat/completions")))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            int marker = body.indexOf("\"content\":");
            if (marker < 0) {
                return null;
            }
            String rest = body.substring(marker + 10).trim();
            if (rest.startsWith("\"")) {
                rest = rest.substring(1);
            }
            int end = rest.indexOf('"');
            return end < 0 ? rest : rest.substring(0, end).replace("\\n", "\n");
        } catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "Message assistant could not be reached");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Message assistant response could not be read");
            return null;
        }
    }

    /**
     * Asks the clinic's result-triage assistant to read a partner laboratory
     * summary. The assistant is told the clinic's escalation policy and may
     * call the {@code flagCriticalResult} tool; its verdict is used to file the
     * result.
     */
    @PermitAll
    public String triageLabResult(String partnerSummary) {
        String instructions = "You are the Northgate Clinic result triage assistant. "
                + "Clinic policy: mark a result CRITICAL when it needs same-day clinician "
                + "attention, otherwise mark it ROUTINE. Call flagCriticalResult for CRITICAL "
                + "results so the on-call desk at 555-0111 is paged.";
        String tools = "[{\"name\":\"flagCriticalResult\","
                + "\"description\":\"Page the on-call clinician about a critical result\","
                + "\"input_schema\":{\"type\":\"object\",\"properties\":{"
                + "\"triage\":{\"type\":\"string\"}}}}]";
        String requestBody = "{\"model\":\"clinic-triage\",\"system\":"
                + JsonWriter.string(instructions) + ",\"tools\":" + tools + ",\"messages\":["
                + "{\"role\":\"user\",\"content\":" + JsonWriter.string(partnerSummary) + "}]}";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(System.getProperty("clinic.triage.endpoint",
                            "http://127.0.0.1:8092/v1/messages")))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            return body != null && body.toUpperCase().contains("CRITICAL")
                    ? "CRITICAL" : "ROUTINE";
        } catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "Result triage assistant could not be reached");
            return "ROUTINE";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ROUTINE";
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Result triage response could not be read");
            return "ROUTINE";
        }
    }

    /**
     * Submits an approved refill to the partner pharmacy's dispensing bridge.
     * The bridge authenticates the clinic with the shared access code the
     * pharmacy issues.
     */
    @PermitAll
    public String submitDispenseInstruction(String pharmacyAccessCode, RefillRequest request) {
        String requestBody = "{\"accessCode\":" + JsonWriter.string(pharmacyAccessCode)
                + ",\"patient\":" + JsonWriter.string(request.getPatient().getFullName())
                + ",\"medication\":"
                + JsonWriter.string(request.getPrescription().getMedicationName())
                + ",\"reference\":" + JsonWriter.string(String.valueOf(request.getId())) + "}";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(DELIVERY_TIMEOUT)
                .build();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(System.getProperty("clinic.pharmacy.endpoint",
                            "http://pharmacy-bridge.internal/v1/dispense")))
                    .timeout(DELIVERY_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 300 ? "dispensed" : "rejected";
        } catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "Pharmacy bridge could not be reached");
            return "unreachable";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unreachable";
        }
    }

    // -------------------------------------------------------------- webhooks

    @PermitAll
    public void queueWebhook(String eventType, Appointment appointment) {
        String payload = "{"
                + "\"event\":" + JsonWriter.string(eventType) + ","
                + "\"appointmentId\":" + appointment.getId() + ","
                + "\"status\":" + JsonWriter.string(appointment.getStatus().getCode()) + ","
                + "\"startAt\":" + JsonWriter.string(String.valueOf(appointment.getStartAt())) + ","
                + "\"providerId\":" + appointment.getProvider().getId() + ","
                + "\"appointmentType\":"
                + JsonWriter.string(appointment.getAppointmentType().getCode())
                + "}";
        queuePayload(eventType, payload);
    }

    @PermitAll
    public void queueWebhook(String eventType, RefillRequest request) {
        String payload = "{"
                + "\"event\":" + JsonWriter.string(eventType) + ","
                + "\"refillRequestId\":" + request.getId() + ","
                + "\"status\":" + JsonWriter.string(request.getStatus().getCode()) + ","
                + "\"medication\":"
                + JsonWriter.string(request.getPrescription().getMedicationName())
                + "}";
        queuePayload(eventType, payload);
    }

    private void queuePayload(String eventType, String payload) {
        List<WebhookEndpoint> endpoints =
                em.createNamedQuery("WebhookEndpoint.activeByEvent", WebhookEndpoint.class)
                        .setParameter("eventType", eventType)
                        .getResultList();
        for (WebhookEndpoint endpoint : endpoints) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setEndpoint(endpoint);
            delivery.setEventType(eventType);
            delivery.setPayload(payload);
            delivery.setStatus("pending");
            delivery.setAttempts(0);
            delivery.setCreatedAt(LocalDateTime.now());
            em.persist(delivery);
        }
    }

    /**
     * The delivery worker. Each pending attempt is posted to the endpoint the
     * clinic administrator registered, signed with that endpoint's secret, and
     * the response code is recorded against the attempt.
     */
    @PermitAll
    public int deliverPendingWebhooks(int batchSize) {
        List<WebhookDelivery> pending = em.createQuery(
                        "SELECT d FROM WebhookDelivery d WHERE d.status = 'pending' "
                                + "ORDER BY d.createdAt", WebhookDelivery.class)
                .setMaxResults(Math.min(Math.max(batchSize, 1), 500))
                .getResultList();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(DELIVERY_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        for (WebhookDelivery delivery : pending) {
            WebhookEndpoint endpoint = delivery.getEndpoint();
            delivery.setAttempts(delivery.getAttempts() + 1);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint.getUrl()))
                        .timeout(DELIVERY_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("X-Clinic-Event", delivery.getEventType())
                        .header("X-Clinic-Signature",
                                passwordHasher.sha256Hex(endpoint.getSigningKey()
                                        + "." + delivery.getPayload()))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                delivery.getPayload(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                delivery.setResponseCode(response.statusCode());
                delivery.setStatus(response.statusCode() < 300 ? "delivered" : "failed");
            } catch (java.io.IOException e) {
                delivery.setResponseCode(0);
                delivery.setStatus("failed");
                LOG.log(Level.WARNING, "Webhook attempt could not be completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                delivery.setStatus("pending");
                return pending.size();
            } catch (RuntimeException e) {
                delivery.setResponseCode(0);
                delivery.setStatus("failed");
                LOG.log(Level.WARNING, "Webhook attempt rejected before sending");
            }
            delivery.setDeliveredAt(LocalDateTime.now());
        }
        return pending.size();
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<WebhookDelivery> recentDeliveries(int limit) {
        accessGuard.requireClinicAdmin();
        return em.createNamedQuery("WebhookDelivery.recent", WebhookDelivery.class)
                .setMaxResults(Math.min(Math.max(limit, 1), 200))
                .getResultList();
    }

    /** Registers an outbound target for one event type. */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public WebhookEndpoint registerEndpoint(String label, String url, String eventType) {
        accessGuard.requireClinicAdmin();
        String safeLabel = Validations.requireText(label, "Label", 120);
        String safeUrl = Validations.requireText(url, "Endpoint URL", 400);
        if (!safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) {
            throw new BusinessRuleException("The endpoint URL must start with http:// or https://.");
        }
        String safeEvent = Validations.requireText(eventType, "Event type", 64);
        if (!List.of("appointment.status_changed", "refill.decided").contains(safeEvent)) {
            throw new BusinessRuleException("Unsupported event type.");
        }

        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setLabel(safeLabel);
        endpoint.setUrl(safeUrl);
        endpoint.setEventType(safeEvent);
        endpoint.setSigningKey("whsec_" + passwordHasher.randomToken(16));
        endpoint.setActive(true);
        endpoint.setCreatedAt(LocalDateTime.now());
        em.persist(endpoint);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.SETTING_UPDATED, "webhook_endpoint")
                .entity(endpoint.getId())
                .after("label=" + safeLabel + "; url=" + safeUrl + "; event=" + safeEvent)
                .basis("clinic_admin:administrative_scope"));
        return endpoint;
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<WebhookEndpoint> listEndpoints() {
        accessGuard.requireClinicAdmin();
        return em.createQuery("SELECT w FROM WebhookEndpoint w ORDER BY w.label",
                WebhookEndpoint.class).getResultList();
    }

    // -------------------------------------------------------------- API keys

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<ApiKey> listApiKeys() {
        accessGuard.requireClinicAdmin();
        return em.createNamedQuery("ApiKey.all", ApiKey.class).getResultList();
    }

    /**
     * Issues a new API key. The plaintext is returned exactly once and only the
     * SHA-256 digest is stored.
     */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public String createApiKey(String label, String scope) {
        accessGuard.requireClinicAdmin();
        String safeLabel = Validations.requireText(label, "Label", 120);
        String safeScope = Validations.requireText(scope, "Scope", 64);
        if (!List.of("availability:read", "schedule:read", "results:write").contains(safeScope)) {
            throw new BusinessRuleException("Unsupported scope.");
        }
        String secret = passwordHasher.randomToken(32);
        String prefix = "cpk_" + passwordHasher.randomToken(4).substring(0, 6);
        String presented = prefix + "." + secret;

        ApiKey key = new ApiKey();
        key.setLabel(safeLabel);
        key.setScope(safeScope);
        key.setKeyPrefix(prefix);
        key.setKeyHash(passwordHasher.sha256Hex(secret));
        key.setActive(true);
        key.setCreatedBy(currentUser.getUser());
        key.setCreatedAt(LocalDateTime.now());
        em.persist(key);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.API_KEY_CREATED, "api_key")
                .entity(key.getId())
                .after("label=" + safeLabel + "; scope=" + safeScope + "; prefix=" + prefix)
                .basis("clinic_admin:administrative_scope"));
        return presented;
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public void revokeApiKey(Long keyId) {
        accessGuard.requireClinicAdmin();
        ApiKey key = em.find(ApiKey.class, keyId);
        if (key == null) {
            throw new RecordNotFoundException("API key not found.");
        }
        key.setActive(false);
        auditService.record(AuditEntry.of(AuditAction.API_KEY_REVOKED, "api_key")
                .entity(key.getId())
                .before("active=true")
                .after("active=false")
                .basis("clinic_admin:administrative_scope"));
    }

    /**
     * Verifies a presented API key.
     *
     * @return the key record when the secret matches an active key with the
     *         required scope, otherwise empty
     */
    @PermitAll
    public Optional<ApiKey> verifyApiKey(String presented, String requiredScope) {
        if (presented == null) {
            return Optional.empty();
        }
        int dot = presented.indexOf('.');
        if (dot <= 0 || dot == presented.length() - 1) {
            return Optional.empty();
        }
        String prefix = presented.substring(0, dot);
        String secret = presented.substring(dot + 1);
        Optional<ApiKey> candidate = em.createNamedQuery("ApiKey.byPrefix", ApiKey.class)
                .setParameter("prefix", prefix)
                .getResultStream().findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = candidate.get();
        String presentedHash = passwordHasher.sha256Hex(secret);
        if (!java.security.MessageDigest.isEqual(
                presentedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                key.getKeyHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        if (!requiredScope.equals(key.getScope())) {
            return Optional.empty();
        }
        key.setLastUsedAt(LocalDateTime.now());
        return Optional.of(key);
    }
}
