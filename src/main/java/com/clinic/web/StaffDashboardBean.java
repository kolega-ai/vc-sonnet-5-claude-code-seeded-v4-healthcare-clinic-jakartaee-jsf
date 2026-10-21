package com.clinic.web;

import com.clinic.domain.Appointment;
import com.clinic.domain.PatientDocument;
import com.clinic.domain.RefillRequest;
import com.clinic.domain.VisitSummary;
import com.clinic.security.CurrentUser;
import com.clinic.service.AppointmentService;
import com.clinic.service.DashboardService;
import com.clinic.service.DocumentService;
import com.clinic.service.MessageService;
import com.clinic.service.PrescriptionService;
import com.clinic.service.VisitSummaryService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/**
 * Shared dashboard for front desk, clinicians and administrators. Each tile is
 * computed with the caller's own scope, so a clinician sees their panel and the
 * front desk sees the whole clinic.
 */
@Named("staffDashboard")
@ViewScoped
public class StaffDashboardBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private DashboardService dashboardService;

    @EJB
    private AppointmentService appointmentService;

    @EJB
    private DocumentService documentService;

    @EJB
    private PrescriptionService prescriptionService;

    @EJB
    private MessageService messageService;

    @EJB
    private VisitSummaryService visitSummaryService;

    private List<Appointment> todaysSchedule = List.of();
    private List<Appointment> pendingRequests = List.of();
    private List<PatientDocument> reviewQueue = List.of();
    private List<RefillRequest> refillQueue = List.of();
    private List<VisitSummary> myDrafts = List.of();

    private long appointmentsToday;
    private long checkedIn;
    private long formsAwaitingReview;
    private long openThreads;
    private long openRefills;
    private long panelSize;

    @PostConstruct
    public void load() {
        try {
            todaysSchedule = dashboardService.todaysSchedule(12);
            appointmentsToday = dashboardService.appointmentsToday();
            checkedIn = dashboardService.checkedInNow();
            formsAwaitingReview = dashboardService.formsAwaitingReview();
            openThreads = messageService.openThreadCount();
            pendingRequests = appointmentService.queueAwaitingConfirmation();
            reviewQueue = documentService.reviewQueue();
            if (currentUser.isClinician()) {
                refillQueue = prescriptionService.openQueue();
                myDrafts = visitSummaryService.myDrafts(6);
                openRefills = dashboardService.myOpenRefills();
                panelSize = dashboardService.myPanelSize();
            } else if (currentUser.isClinicAdmin()) {
                refillQueue = prescriptionService.openQueue();
                openRefills = prescriptionService.countOpenRefills();
            } else {
                openRefills = prescriptionService.countOpenRefills();
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public List<Appointment> getTodaysSchedule() {
        return todaysSchedule;
    }

    public List<Appointment> getPendingRequests() {
        return pendingRequests;
    }

    public List<PatientDocument> getReviewQueue() {
        return reviewQueue;
    }

    public List<RefillRequest> getRefillQueue() {
        return refillQueue;
    }

    public List<VisitSummary> getMyDrafts() {
        return myDrafts;
    }

    public long getAppointmentsToday() {
        return appointmentsToday;
    }

    public long getCheckedIn() {
        return checkedIn;
    }

    public long getFormsAwaitingReview() {
        return formsAwaitingReview;
    }

    public long getOpenThreads() {
        return openThreads;
    }

    public long getOpenRefills() {
        return openRefills;
    }

    public long getPanelSize() {
        return panelSize;
    }

    public int getPendingRequestCount() {
        return pendingRequests.size();
    }
}
