package com.clinic.web;

import com.clinic.domain.VisitSummary;
import com.clinic.service.VisitSummaryService;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/**
 * Clinician authoring screen for a visit summary: edit the draft, then release
 * it to the patient or amend an already-released one.
 */
@Named("visitSummaryEditor")
@ViewScoped
public class VisitSummaryEditorBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private VisitSummaryService visitSummaryService;

    private Long summaryId;
    private VisitSummary summary;
    private boolean loaded;

    private String chiefComplaint;
    private String diagnosis;
    private String careInstructions;
    private String clinicianNotes;
    private String followUpAdvice;
    private String amendReason;

    private List<VisitSummary> drafts = List.of();

    public void load() {
        if (summaryId == null) {
            summaryId = Faces.longParam("id");
        }
        try {
            drafts = visitSummaryService.myDrafts(25);
            if (summaryId != null) {
                summary = visitSummaryService.open(summaryId);
                chiefComplaint = summary.getChiefComplaint();
                diagnosis = summary.getDiagnosis();
                careInstructions = summary.getCareInstructions();
                clinicianNotes = summary.getClinicianNotes();
                followUpAdvice = summary.getFollowUpAdvice();
                loaded = true;
            }
        } catch (RuntimeException e) {
            loaded = false;
            Faces.handle(e);
        }
    }

    public String save() {
        try {
            visitSummaryService.saveContent(summaryId, chiefComplaint, diagnosis,
                    careInstructions, clinicianNotes, followUpAdvice);
            Faces.success("Visit summary saved. It is not visible to the patient until released.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String release() {
        try {
            visitSummaryService.saveContent(summaryId, chiefComplaint, diagnosis,
                    careInstructions, clinicianNotes, followUpAdvice);
            visitSummaryService.release(summaryId);
            Faces.success("Visit summary released. The patient can now read it in their portal.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String amend() {
        try {
            visitSummaryService.amend(summaryId, amendReason);
            Faces.success("Summary reopened for amendment.");
            amendReason = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public boolean isReleasable() {
        return summary != null
                && summary.getStatus().canTransitionTo(
                        com.clinic.domain.VisitSummaryStatus.RELEASED);
    }

    public boolean isAmendable() {
        return summary != null
                && summary.getStatus().canTransitionTo(
                        com.clinic.domain.VisitSummaryStatus.AMENDED);
    }

    // ------------------------------------------------------------ accessors

    public Long getSummaryId() {
        return summaryId;
    }

    public void setSummaryId(Long summaryId) {
        this.summaryId = summaryId;
    }

    public VisitSummary getSummary() {
        return summary;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public List<VisitSummary> getDrafts() {
        return drafts;
    }

    public String getChiefComplaint() {
        return chiefComplaint;
    }

    public void setChiefComplaint(String chiefComplaint) {
        this.chiefComplaint = chiefComplaint;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public void setDiagnosis(String diagnosis) {
        this.diagnosis = diagnosis;
    }

    public String getCareInstructions() {
        return careInstructions;
    }

    public void setCareInstructions(String careInstructions) {
        this.careInstructions = careInstructions;
    }

    public String getClinicianNotes() {
        return clinicianNotes;
    }

    public void setClinicianNotes(String clinicianNotes) {
        this.clinicianNotes = clinicianNotes;
    }

    public String getFollowUpAdvice() {
        return followUpAdvice;
    }

    public void setFollowUpAdvice(String followUpAdvice) {
        this.followUpAdvice = followUpAdvice;
    }

    public String getAmendReason() {
        return amendReason;
    }

    public void setAmendReason(String amendReason) {
        this.amendReason = amendReason;
    }
}
