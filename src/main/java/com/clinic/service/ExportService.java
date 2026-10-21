package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.Appointment;
import com.clinic.domain.AuditEvent;
import com.clinic.domain.Role;
import com.clinic.domain.VisitSummary;
import com.clinic.security.AccessDecision;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.CurrentUser;
import com.clinic.storage.DocumentStorage;
import com.clinic.util.CsvSupport;
import com.clinic.util.JsonWriter;

import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Sensitive report exports.
 *
 * <p>Every export re-checks the caller's scope, restricts the rows to what that
 * caller may see, and writes a single audit event naming the report and the row
 * count. Clinician exports are limited to their own panel; patient exports are
 * limited to their own released records.</p>
 */
@Stateless
public class ExportService {

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

    @Resource(lookup = "java:jboss/datasources/ClinicDS")
    private DataSource dataSource;

    /** Appointment schedule export for a date range, as CSV. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public String appointmentScheduleCsv(LocalDate from, LocalDate to, Long providerId) {
        accessGuard.requireStaff();
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessRuleException("Choose a valid date range.");
        }
        if (from.plusDays(370).isBefore(to)) {
            throw new BusinessRuleException("Export at most one year at a time.");
        }
        Long effectiveProvider = providerId;
        if (currentUser.getRole() == Role.CLINICIAN) {
            if (currentUser.getProvider() == null) {
                throw new BusinessRuleException("Your account is not linked to a provider record.");
            }
            effectiveProvider = currentUser.getProvider().getId();
        }

        StringBuilder jpql = new StringBuilder(
                "SELECT a FROM Appointment a WHERE a.startAt >= :from AND a.startAt < :to");
        if (effectiveProvider != null) {
            jpql.append(" AND a.provider.id = :providerId");
        }
        jpql.append(" ORDER BY a.startAt");
        var query = em.createQuery(jpql.toString(), Appointment.class)
                .setParameter("from", from.atStartOfDay())
                .setParameter("to", to.plusDays(1).atStartOfDay());
        if (effectiveProvider != null) {
            query.setParameter("providerId", effectiveProvider);
        }
        List<Appointment> rows = query.setMaxResults(20_000).getResultList();

        StringBuilder csv = new StringBuilder();
        csv.append(CsvSupport.row("appointment_id", "start_at", "end_at", "status",
                "provider", "appointment_type", "patient_mrn", "patient_name", "reason"));
        for (Appointment a : rows) {
            csv.append(CsvSupport.row(a.getId(), a.getStartAt(), a.getEndAt(),
                    a.getStatus().getCode(), a.getProvider().getFullName(),
                    a.getAppointmentType().getName(), a.getPatient().getMrn(),
                    a.getPatient().getFullName(), a.getReason()));
        }

        auditService.record(AuditEntry.of(AuditAction.EXPORT_GENERATED, "appointment")
                .detail("report=appointment_schedule; rows=" + rows.size()
                        + "; from=" + from + "; to=" + to)
                .basis("role:" + currentUser.getRole().getCode()));
        return csv.toString();
    }

    /**
     * Visit summary export for one patient. A patient may export only their own
     * released summaries; clinical staff get the full chart.
     */
    @PermitAll
    public String visitSummariesJson(Long patientId) {
        AccessDecision decision = accessGuard.requireClinicalPatientAccess(patientId);
        boolean patientScope = currentUser.getRole() == Role.PATIENT;
        List<VisitSummary> rows = patientScope
                ? em.createNamedQuery("VisitSummary.releasedByPatient", VisitSummary.class)
                .setParameter("patientId", patientId).getResultList()
                : em.createNamedQuery("VisitSummary.byPatient", VisitSummary.class)
                .setParameter("patientId", patientId).getResultList();

        StringBuilder json = new StringBuilder("{\"patientId\":").append(patientId)
                .append(",\"generatedAt\":")
                .append(JsonWriter.string(java.time.LocalDateTime.now().toString()))
                .append(",\"visitSummaries\":[");
        for (int i = 0; i < rows.size(); i++) {
            VisitSummary v = rows.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(v.getId())
                    .append(",\"visitDate\":").append(JsonWriter.string(String.valueOf(v.getVisitDate())))
                    .append(",\"status\":").append(JsonWriter.string(v.getStatus().getCode()))
                    .append(",\"provider\":").append(JsonWriter.string(v.getProvider().getDisplayName()))
                    .append(",\"chiefComplaint\":").append(JsonWriter.string(v.getChiefComplaint()))
                    .append(",\"assessment\":").append(JsonWriter.string(v.getDiagnosis()))
                    .append(",\"careInstructions\":").append(JsonWriter.string(v.getCareInstructions()))
                    .append(",\"followUp\":").append(JsonWriter.string(v.getFollowUpAdvice()));
            // Clinician notes are internal and are never included in a patient export.
            if (!patientScope) {
                json.append(",\"clinicianNotes\":").append(JsonWriter.string(v.getClinicianNotes()));
            }
            json.append('}');
        }
        json.append("]}");

        auditService.record(AuditEntry.of(AuditAction.EXPORT_GENERATED, "visit_summary")
                .patient(patientId)
                .detail("report=visit_summaries; rows=" + rows.size()
                        + "; scope=" + (patientScope ? "patient" : "clinical"))
                .basis(decision.basis()));
        return json.toString();
    }

    /** Patient directory export. Administrative scope only. */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public String patientDirectoryCsv() {
        accessGuard.requireClinicAdmin();
        var rows = em.createNamedQuery("Patient.all", com.clinic.domain.Patient.class)
                .setMaxResults(20_000)
                .getResultList();
        StringBuilder csv = new StringBuilder();
        csv.append(CsvSupport.row("mrn", "last_name", "first_name", "date_of_birth", "phone",
                "email", "city", "insurance_provider", "primary_provider", "active"));
        for (var p : rows) {
            csv.append(CsvSupport.row(p.getMrn(), p.getLastName(), p.getFirstName(),
                    p.getDateOfBirth(), p.getPhone(), p.getEmail(), p.getCity(),
                    p.getInsuranceProvider(),
                    p.getPrimaryProvider() == null ? "" : p.getPrimaryProvider().getFullName(),
                    p.isActive()));
        }
        auditService.record(AuditEntry.of(AuditAction.EXPORT_GENERATED, "patient")
                .detail("report=patient_directory; rows=" + rows.size())
                .basis("clinic_admin:administrative_scope"));
        return csv.toString();
    }

    /** Audit log export. Administrative scope only; the log itself stays immutable. */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public String auditLogCsv(LocalDate from, LocalDate to) {
        accessGuard.requireClinicAdmin();
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessRuleException("Choose a valid date range.");
        }
        List<AuditEvent> rows = em.createQuery(
                        "SELECT a FROM AuditEvent a WHERE a.occurredAt >= :from "
                                + "AND a.occurredAt < :to ORDER BY a.occurredAt DESC",
                        AuditEvent.class)
                .setParameter("from", from.atStartOfDay())
                .setParameter("to", to.plusDays(1).atStartOfDay())
                .setMaxResults(50_000)
                .getResultList();

        StringBuilder csv = new StringBuilder();
        csv.append(CsvSupport.row("occurred_at", "actor_email", "actor_role", "action",
                "entity_type", "entity_id", "patient_id", "auth_basis", "detail", "ip_address"));
        for (AuditEvent a : rows) {
            csv.append(CsvSupport.row(a.getOccurredAt(), a.getActorEmail(), a.getActorRole(),
                    a.getAction(), a.getEntityType(), a.getEntityId(), a.getPatientId(),
                    a.getAuthBasis(), a.getDetail(), a.getIpAddress()));
        }
        auditService.record(AuditEntry.of(AuditAction.EXPORT_GENERATED, "audit_event")
                .detail("report=audit_log; rows=" + rows.size() + "; from=" + from + "; to=" + to)
                .basis("clinic_admin:administrative_scope"));
        return csv.toString();
    }

    /**
     * Writes a patient-index handoff packet into the folder the partner
     * practices collect from. The operator names the packet so the receiving
     * practice can tell the batches apart.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.FRONT_DESK_ROLE, Role.CLINICIAN_ROLE})
    public String publishExchangePacket(String packetName, String packetCsv) {
        accessGuard.requireStaff();
        Path exchange = storage.getBaseDirectory().resolve("exchange");
        try {
            Files.createDirectories(exchange);
            Path target = exchange.resolve(packetName);
            try (OutputStream out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(packetCsv.getBytes(StandardCharsets.UTF_8));
            }
            auditService.record(AuditEntry.of(AuditAction.EXPORT_GENERATED, "exchange_packet")
                    .detail("packet=" + packetName)
                    .basis("role:" + currentUser.getRole().getCode()));
            return packetName;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("The handoff packet could not be written", e);
        }
    }

    /**
     * Payer-scoped visit summary for the billing desk. The desk keeps the payer
     * it is working as a preference on its browser so the report reopens where
     * it left off.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.FRONT_DESK_ROLE})
    public String payerSummaryCsv(String payer) {
        accessGuard.requireRole(Role.CLINIC_ADMIN, Role.FRONT_DESK);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.mrn, p.last_name, p.insurance_provider, COUNT(a.id) AS visits"
                        + " FROM patient p LEFT JOIN appointment a ON a.patient_id = p.id"
                        + " WHERE p.insurance_provider = '" + payer + "'"
                        + " GROUP BY p.mrn, p.last_name, p.insurance_provider"
                        + " ORDER BY p.last_name");
        StringBuilder csv = new StringBuilder();
        csv.append(CsvSupport.row("mrn", "last_name", "insurance_provider", "visits"));
        for (Map<String, Object> row : rows) {
            csv.append(CsvSupport.row(row.get("mrn"), row.get("last_name"),
                    row.get("insurance_provider"), row.get("visits")));
        }
        auditService.record(AuditEntry.of(AuditAction.EXPORT_GENERATED, "patient")
                .detail("report=payer_summary; rows=" + rows.size() + "; payer=" + payer)
                .basis("role:" + currentUser.getRole().getCode()));
        return csv.toString();
    }
}
