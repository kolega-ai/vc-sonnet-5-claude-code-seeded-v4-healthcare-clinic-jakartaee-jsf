package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.Appointment;
import com.clinic.domain.AppointmentStatus;
import com.clinic.domain.JobRun;
import com.clinic.domain.Prescription;
import com.clinic.domain.PrescriptionStatus;
import com.clinic.domain.Role;
import com.clinic.security.AccessGuard;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-process scheduled work. No external broker or scheduler is involved.
 *
 * <p>Three jobs run on a timer and can also be triggered manually from the
 * admin operations screen: appointment reminders, overdue no-show marking, and
 * expiring prescriptions plus outbox/webhook flushing.</p>
 */
@Singleton
@PermitAll
public class ScheduledJobs {

    private static final Logger LOG = Logger.getLogger(ScheduledJobs.class.getName());

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AuditService auditService;

    @Inject
    private NotificationService notificationService;

    @Inject
    private AccessGuard accessGuard;

    @EJB
    private IntegrationService integrationService;

    @EJB
    private PasswordResetService passwordResetService;

    // --------------------------------------------------------------- timers

    /** Reminders for appointments starting in the next 24 hours. */
    @Schedule(hour = "*", minute = "5", persistent = false)
    public void hourlyReminders() {
        runJob("appointment-reminders", this::sendReminders);
    }

    /** Marks scheduled appointments that are well past their slot as no-shows. */
    @Schedule(hour = "*", minute = "20", persistent = false)
    public void hourlyNoShowSweep() {
        runJob("no-show-sweep", this::markNoShows);
    }

    /** Housekeeping: expire prescriptions, flush the outbox and webhook queue. */
    @Schedule(hour = "2", minute = "0", persistent = false)
    public void nightlyHousekeeping() {
        runJob("nightly-housekeeping", this::housekeeping);
    }

    // ------------------------------------------------------------ manual run

    /** Lets an administrator run a job on demand from the operations screen. */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public String runNow(String jobName) {
        accessGuard.requireClinicAdmin();
        return switch (jobName) {
            case "appointment-reminders" -> runJob("appointment-reminders", this::sendReminders);
            case "no-show-sweep" -> runJob("no-show-sweep", this::markNoShows);
            case "nightly-housekeeping" -> runJob("nightly-housekeeping", this::housekeeping);
            default -> throw new com.clinic.security.BusinessRuleException("Unknown job.");
        };
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<JobRun> recentRuns(int limit) {
        accessGuard.requireClinicAdmin();
        return em.createNamedQuery("JobRun.recent", JobRun.class)
                .setMaxResults(Math.min(Math.max(limit, 1), 100))
                .getResultList();
    }

    // ----------------------------------------------------------------- work

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public String runJob(String name, JobBody body) {
        JobRun run = new JobRun();
        run.setJobName(name);
        run.setStartedAt(LocalDateTime.now());
        run.setStatus("running");
        em.persist(run);
        em.flush();
        try {
            String detail = body.execute();
            run.setStatus("succeeded");
            run.setDetail(detail);
            auditService.record(AuditEntry.of(AuditAction.JOB_EXECUTED, "job_run")
                    .entity(run.getId())
                    .after(name + ": " + detail)
                    .basis("scheduler"));
            return detail;
        } catch (RuntimeException e) {
            run.setStatus("failed");
            run.setDetail(e.getClass().getSimpleName());
            LOG.log(Level.WARNING, "Scheduled job {0} failed", name);
            return "failed";
        } finally {
            run.setFinishedAt(LocalDateTime.now());
        }
    }

    private String sendReminders() {
        LocalDateTime from = LocalDateTime.now();
        LocalDateTime to = from.plusHours(24);
        List<Appointment> due = em.createQuery(
                        "SELECT a FROM Appointment a WHERE a.status = :status "
                                + "AND a.startAt BETWEEN :from AND :to", Appointment.class)
                .setParameter("status", AppointmentStatus.SCHEDULED)
                .setParameter("from", from)
                .setParameter("to", to)
                .setMaxResults(500)
                .getResultList();
        int sent = 0;
        for (Appointment appointment : due) {
            if (appointment.getPatient().getUser() != null) {
                notificationService.notifyUser(appointment.getPatient().getUser(),
                        "appointment.reminder", "Appointment tomorrow",
                        appointment.getAppointmentType().getName() + " with "
                                + appointment.getProvider().getDisplayName() + " at "
                                + appointment.getStartAt().toLocalTime(),
                        "/patient/appointments.xhtml");
                sent++;
            }
            if (appointment.getPatient().getEmail() != null) {
                integrationService.queueEmail(appointment.getPatient().getEmail(),
                        "Appointment reminder",
                        "You have an appointment on " + appointment.getStartAt()
                                + " with " + appointment.getProvider().getDisplayName() + ".");
            }
        }
        return "reminders=" + sent + "; appointments=" + due.size();
    }

    private String markNoShows() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(45);
        List<Appointment> stale = em.createQuery(
                        "SELECT a FROM Appointment a WHERE a.status = :status AND a.endAt < :cutoff",
                        Appointment.class)
                .setParameter("status", AppointmentStatus.SCHEDULED)
                .setParameter("cutoff", cutoff)
                .setMaxResults(500)
                .getResultList();
        int changed = 0;
        for (Appointment appointment : stale) {
            // The same edge list the interactive screens use.
            if (!appointment.getStatus().canTransitionTo(AppointmentStatus.NO_SHOW)) {
                continue;
            }
            appointment.setStatus(AppointmentStatus.NO_SHOW);
            appointment.setUpdatedAt(LocalDateTime.now());
            auditService.record(
                    AuditEntry.of(AuditAction.APPOINTMENT_STATUS_CHANGED, "appointment")
                            .entity(appointment.getId())
                            .patient(appointment.getPatient().getId())
                            .before("status=scheduled")
                            .after("status=no_show")
                            .detail("Marked automatically by the no-show sweep")
                            .basis("scheduler"));
            changed++;
        }
        return "noShows=" + changed;
    }

    private String housekeeping() {
        int expired = 0;
        List<Prescription> due = em.createQuery(
                        "SELECT p FROM Prescription p WHERE p.status = :status "
                                + "AND p.expiresAt IS NOT NULL AND p.expiresAt < :today",
                        Prescription.class)
                .setParameter("status", PrescriptionStatus.ACTIVE)
                .setParameter("today", LocalDate.now())
                .setMaxResults(1000)
                .getResultList();
        for (Prescription prescription : due) {
            if (!prescription.getStatus().canTransitionTo(PrescriptionStatus.EXPIRED)) {
                continue;
            }
            prescription.setStatus(PrescriptionStatus.EXPIRED);
            prescription.setUpdatedAt(LocalDateTime.now());
            auditService.record(
                    AuditEntry.of(AuditAction.PRESCRIPTION_STATUS_CHANGED, "prescription")
                            .entity(prescription.getId())
                            .patient(prescription.getPatient().getId())
                            .before("status=active")
                            .after("status=expired")
                            .detail("Expired automatically at the expiry date")
                            .basis("scheduler"));
            expired++;
        }
        int mail = integrationService.flushOutbox(200);
        int hooks = integrationService.deliverPendingWebhooks(200);
        int tokens = passwordResetService.purgeExpiredTokens();
        return "expiredPrescriptions=" + expired + "; mailFlushed=" + mail
                + "; webhooksDelivered=" + hooks + "; tokensPurged=" + tokens;
    }

    /** One unit of scheduled work. */
    @FunctionalInterface
    public interface JobBody {
        String execute();
    }
}
