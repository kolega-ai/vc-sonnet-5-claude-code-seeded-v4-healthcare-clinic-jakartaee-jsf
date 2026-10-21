package com.clinic.web;

import com.clinic.domain.DocumentStatus;
import com.clinic.domain.PatientDocument;
import com.clinic.service.DocumentService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/** The staff form-verification queue: verify, reject with a reason, or archive. */
@Named("documentReview")
@ViewScoped
public class DocumentReviewBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private DocumentService documentService;

    private List<PatientDocument> queue = List.of();
    private PatientDocument selected;
    private String reviewNote;

    @PostConstruct
    public void load() {
        try {
            queue = documentService.reviewQueue();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public void select(PatientDocument document) {
        selected = document;
        reviewNote = null;
    }

    public String verify() {
        return review(DocumentStatus.VERIFIED);
    }

    public String reject() {
        return review(DocumentStatus.REJECTED);
    }

    public String archive() {
        return review(DocumentStatus.ARCHIVED);
    }

    private String review(DocumentStatus target) {
        if (selected == null) {
            Faces.error("Choose a document first.");
            return null;
        }
        try {
            documentService.review(selected.getId(), target, reviewNote);
            Faces.success("Document " + target.getLabel().toLowerCase() + ".");
            selected = null;
            reviewNote = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String verifyAll() {
        if (queue.isEmpty()) {
            Faces.error("There is nothing waiting for review.");
            return null;
        }
        try {
            int changed = documentService.verifyQueue(
                    queue.stream().map(PatientDocument::getId).toList());
            Faces.success(changed + " documents verified.");
            selected = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String downloadUrl(PatientDocument document) {
        return Faces.contextPath() + "/api/secure/documents/" + document.getId() + "/download";
    }

    public String renderUrl(PatientDocument document) {
        return Faces.contextPath() + "/api/secure/documents/" + document.getId()
                + "/rendered?format=pdf";
    }

    public String previewUrl(PatientDocument document) {
        return Faces.contextPath() + "/api/secure/documents/" + document.getId() + "/preview";
    }

    public List<PatientDocument> getQueue() {
        return queue;
    }

    public boolean isEmptyQueue() {
        return queue.isEmpty();
    }

    public PatientDocument getSelected() {
        return selected;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }
}
