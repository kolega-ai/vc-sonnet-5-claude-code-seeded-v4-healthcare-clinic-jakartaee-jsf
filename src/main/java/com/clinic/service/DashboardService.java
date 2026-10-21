package com.clinic.service;

import com.clinic.domain.Appointment;
import com.clinic.domain.AppointmentStatus;
import com.clinic.domain.DocumentStatus;
import com.clinic.domain.PatientDocument;
import com.clinic.domain.Provider;
import com.clinic.domain.RefillRequest;
import com.clinic.domain.RefillStatus;
import com.clinic.domain.Role;
import com.clinic.domain.VisitSummary;
import com.clinic.domain.VisitSummaryStatus;
import com.clinic.security.AccessGuard;
import com.clinic.security.CurrentUser;

import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Aggregates for the role dashboards. Every query is scoped to the calling
 * role: a clinician's tiles count only their own panel.
 */
@Stateless
@PermitAll
public class DashboardService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AccessGuard accessGuard;

    // ------------------------------------------------------------- clinic

    public long appointmentsToday() {
        accessGuard.requireStaff();
        return em.createQuery(
                        "SELECT COUNT(a) FROM Appointment a WHERE a.startAt >= :from "
                                + "AND a.startAt < :to AND a.status <> :cancelled", Long.class)
                .setParameter("from", LocalDate.now().atStartOfDay())
                .setParameter("to", LocalDate.now().plusDays(1).atStartOfDay())
                .setParameter("cancelled", AppointmentStatus.CANCELLED)
                .getSingleResult();
    }

    public long checkedInNow() {
        accessGuard.requireStaff();
        return em.createQuery("SELECT COUNT(a) FROM Appointment a WHERE a.status = :status",
                        Long.class)
                .setParameter("status", AppointmentStatus.CHECKED_IN)
                .getSingleResult();
    }

    public long pendingRequests() {
        accessGuard.requireStaff();
        return em.createQuery("SELECT COUNT(a) FROM Appointment a WHERE a.status = :status",
                        Long.class)
                .setParameter("status", AppointmentStatus.REQUESTED)
                .getSingleResult();
    }

    public long formsAwaitingReview() {
        accessGuard.requireStaff();
        return em.createQuery(
                        "SELECT COUNT(d) FROM PatientDocument d WHERE d.status = :status", Long.class)
                .setParameter("status", DocumentStatus.SUBMITTED)
                .getSingleResult();
    }

    public List<Appointment> todaysSchedule(int limit) {
        accessGuard.requireStaff();
        StringBuilder jpql = new StringBuilder(
                "SELECT a FROM Appointment a WHERE a.startAt >= :from AND a.startAt < :to");
        Long providerId = null;
        if (currentUser.getRole() == Role.CLINICIAN) {
            Provider provider = currentUser.getProvider();
            if (provider == null) {
                return List.of();
            }
            providerId = provider.getId();
            jpql.append(" AND a.provider.id = :providerId");
        }
        jpql.append(" ORDER BY a.startAt");
        var query = em.createQuery(jpql.toString(), Appointment.class)
                .setParameter("from", LocalDate.now().atStartOfDay())
                .setParameter("to", LocalDate.now().plusDays(1).atStartOfDay());
        if (providerId != null) {
            query.setParameter("providerId", providerId);
        }
        return query.setMaxResults(Math.min(Math.max(limit, 1), 100)).getResultList();
    }

    // ---------------------------------------------------------- clinician

    public long myOpenRefills() {
        Provider provider = currentUser.getProvider();
        if (provider == null) {
            return 0;
        }
        return em.createQuery(
                        "SELECT COUNT(r) FROM RefillRequest r WHERE r.status IN (:open) "
                                + "AND r.prescription.provider.id = :providerId", Long.class)
                .setParameter("open", List.of(RefillStatus.REQUESTED, RefillStatus.NEEDS_INFO))
                .setParameter("providerId", provider.getId())
                .getSingleResult();
    }

    public long myDraftSummaries() {
        Provider provider = currentUser.getProvider();
        if (provider == null) {
            return 0;
        }
        return em.createQuery(
                        "SELECT COUNT(v) FROM VisitSummary v WHERE v.provider.id = :providerId "
                                + "AND v.status = :status", Long.class)
                .setParameter("providerId", provider.getId())
                .setParameter("status", VisitSummaryStatus.DRAFT)
                .getSingleResult();
    }

    public long myPanelSize() {
        Provider provider = currentUser.getProvider();
        if (provider == null) {
            return 0;
        }
        return em.createQuery(
                        "SELECT COUNT(DISTINCT p) FROM Patient p WHERE p.active = true AND ("
                                + "p.primaryProvider.id = :providerId OR EXISTS ("
                                + "SELECT a FROM PatientProviderAssignment a "
                                + "WHERE a.patient = p AND a.provider.id = :providerId))", Long.class)
                .setParameter("providerId", provider.getId())
                .getSingleResult();
    }

    // ------------------------------------------------------------ patient

    public List<Appointment> myUpcomingAppointments(int limit) {
        Long patientId = currentUser.getPatientId();
        if (patientId == null) {
            return List.of();
        }
        return em.createQuery(
                        "SELECT a FROM Appointment a WHERE a.patient.id = :patientId "
                                + "AND a.startAt >= :now AND a.status IN (:active) "
                                + "ORDER BY a.startAt", Appointment.class)
                .setParameter("patientId", patientId)
                .setParameter("now", LocalDateTime.now().minusHours(2))
                .setParameter("active", List.of(AppointmentStatus.REQUESTED,
                        AppointmentStatus.SCHEDULED, AppointmentStatus.CHECKED_IN))
                .setMaxResults(Math.min(Math.max(limit, 1), 20))
                .getResultList();
    }

    public List<VisitSummary> myReleasedSummaries(int limit) {
        Long patientId = currentUser.getPatientId();
        if (patientId == null) {
            return List.of();
        }
        return em.createNamedQuery("VisitSummary.releasedByPatient", VisitSummary.class)
                .setParameter("patientId", patientId)
                .setMaxResults(Math.min(Math.max(limit, 1), 20))
                .getResultList();
    }

    public List<PatientDocument> myDocuments(int limit) {
        Long patientId = currentUser.getPatientId();
        if (patientId == null) {
            return List.of();
        }
        return em.createNamedQuery("PatientDocument.patientVisibleByPatient", PatientDocument.class)
                .setParameter("patientId", patientId)
                .setMaxResults(Math.min(Math.max(limit, 1), 20))
                .getResultList();
    }

    public List<RefillRequest> myRefillRequests(int limit) {
        Long patientId = currentUser.getPatientId();
        if (patientId == null) {
            return List.of();
        }
        return em.createNamedQuery("RefillRequest.byPatient", RefillRequest.class)
                .setParameter("patientId", patientId)
                .setMaxResults(Math.min(Math.max(limit, 1), 20))
                .getResultList();
    }

    public long myActivePrescriptions() {
        Long patientId = currentUser.getPatientId();
        if (patientId == null) {
            return 0;
        }
        return em.createQuery(
                        "SELECT COUNT(p) FROM Prescription p WHERE p.patient.id = :patientId "
                                + "AND p.status = :status", Long.class)
                .setParameter("patientId", patientId)
                .setParameter("status", com.clinic.domain.PrescriptionStatus.ACTIVE)
                .getSingleResult();
    }

    public long myOutstandingForms() {
        Long patientId = currentUser.getPatientId();
        if (patientId == null) {
            return 0;
        }
        return em.createQuery(
                        "SELECT COUNT(d) FROM PatientDocument d WHERE d.patient.id = :patientId "
                                + "AND d.internalOnly = false AND d.status IN (:pending)", Long.class)
                .setParameter("patientId", patientId)
                .setParameter("pending", List.of(DocumentStatus.SUBMITTED, DocumentStatus.REJECTED))
                .getSingleResult();
    }
}
