package com.clinic.web;

import com.clinic.domain.VisitSummary;
import com.clinic.security.CurrentUser;
import com.clinic.service.VisitSummaryService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/**
 * The patient's released visit summaries.
 *
 * <p>The list comes from the released-only query, and opening one goes through
 * the guard again, so an unreleased draft is unreachable from here even by id.</p>
 */
@Named("patientVisitSummaries")
@ViewScoped
public class PatientVisitSummariesBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private VisitSummaryService visitSummaryService;

    private List<VisitSummary> summaries = List.of();
    private VisitSummary selected;

    @PostConstruct
    public void load() {
        try {
            if (currentUser.getPatientId() != null) {
                summaries = visitSummaryService.releasedForPatient(currentUser.getPatientId());
            }
            Long requested = Faces.longParam("id");
            if (requested != null) {
                open(requested);
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String open(Long summaryId) {
        try {
            selected = visitSummaryService.open(summaryId);
        } catch (RuntimeException e) {
            selected = null;
            Faces.handle(e);
        }
        return null;
    }

    public String exportUrl() {
        return Faces.contextPath() + "/api/secure/exports/visit-summaries.json";
    }

    public List<VisitSummary> getSummaries() {
        return summaries;
    }

    public VisitSummary getSelected() {
        return selected;
    }
}
