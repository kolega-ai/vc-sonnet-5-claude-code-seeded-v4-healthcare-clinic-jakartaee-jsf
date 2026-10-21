package com.clinic.web;

import com.clinic.domain.DocumentCategory;
import com.clinic.domain.PatientDocument;
import com.clinic.security.CurrentUser;
import com.clinic.service.DocumentService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

/** The patient's own document area: upload a form, review status, download. */
@Named("patientDocuments")
@ViewScoped
public class PatientDocumentsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private DocumentService documentService;

    private List<PatientDocument> documents = List.of();
    private String category = DocumentCategory.PRE_VISIT_FORM.getCode();
    private String title;
    private transient Part upload;

    @PostConstruct
    public void load() {
        try {
            if (currentUser.getPatientId() != null) {
                documents = documentService.forPatient(currentUser.getPatientId());
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    /** Only the categories a patient is allowed to submit are offered. */
    public List<DocumentCategory> getCategories() {
        return Arrays.stream(DocumentCategory.values())
                .filter(DocumentCategory::isPatientSubmittable)
                .toList();
    }

    public String submit() {
        if (upload == null || upload.getSize() == 0) {
            Faces.error("Choose a file to upload.");
            return null;
        }
        try (InputStream in = upload.getInputStream()) {
            documentService.upload(currentUser.getPatientId(),
                    DocumentCategory.fromCode(category), title,
                    upload.getSubmittedFileName(), upload.getContentType(),
                    upload.getSize(), in);
            Faces.success("Your document has been uploaded and is now waiting for review.");
            title = null;
            upload = null;
            load();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String downloadUrl(PatientDocument document) {
        return Faces.contextPath() + "/api/secure/documents/" + document.getId() + "/download";
    }

    public List<PatientDocument> getDocuments() {
        return documents;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Part getUpload() {
        return upload;
    }

    public void setUpload(Part upload) {
        this.upload = upload;
    }
}
