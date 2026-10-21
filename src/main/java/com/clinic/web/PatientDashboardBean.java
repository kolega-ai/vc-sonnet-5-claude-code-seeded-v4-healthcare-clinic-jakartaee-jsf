package com.clinic.web;

import com.clinic.domain.Appointment;
import com.clinic.domain.PatientDocument;
import com.clinic.domain.RefillRequest;
import com.clinic.domain.VisitSummary;
import com.clinic.security.CurrentUser;
import com.clinic.service.DashboardService;
import com.clinic.service.MessageService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/** The patient's home screen. Everything shown is scoped to their own record. */
@Named("patientDashboard")
@ViewScoped
public class PatientDashboardBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private DashboardService dashboardService;

    @EJB
    private MessageService messageService;

    private List<Appointment> upcoming = List.of();
    private List<VisitSummary> summaries = List.of();
    private List<PatientDocument> documents = List.of();
    private List<RefillRequest> refills = List.of();
    private long activePrescriptions;
    private long outstandingForms;
    private long unreadMessages;
    private boolean linked = true;

    @PostConstruct
    public void load() {
        if (currentUser.getPatientId() == null) {
            linked = false;
            return;
        }
        try {
            upcoming = dashboardService.myUpcomingAppointments(5);
            summaries = dashboardService.myReleasedSummaries(5);
            documents = dashboardService.myDocuments(5);
            refills = dashboardService.myRefillRequests(5);
            activePrescriptions = dashboardService.myActivePrescriptions();
            outstandingForms = dashboardService.myOutstandingForms();
            unreadMessages = messageService.unreadForPatient(currentUser.getPatientId());
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public boolean isLinked() {
        return linked;
    }

    public String getFirstName() {
        return currentUser.getPatientRecord() == null ? "" : currentUser.getPatientRecord().getFirstName();
    }

    public List<Appointment> getUpcoming() {
        return upcoming;
    }

    public Appointment getNextAppointment() {
        return upcoming.isEmpty() ? null : upcoming.get(0);
    }

    public List<VisitSummary> getSummaries() {
        return summaries;
    }

    public List<PatientDocument> getDocuments() {
        return documents;
    }

    public List<RefillRequest> getRefills() {
        return refills;
    }

    public long getActivePrescriptions() {
        return activePrescriptions;
    }

    public long getOutstandingForms() {
        return outstandingForms;
    }

    public long getUnreadMessages() {
        return unreadMessages;
    }

    public int getUpcomingCount() {
        return upcoming.size();
    }
}
