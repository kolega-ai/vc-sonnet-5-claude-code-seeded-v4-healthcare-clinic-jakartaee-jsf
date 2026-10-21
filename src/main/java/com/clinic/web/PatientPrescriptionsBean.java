package com.clinic.web;

import com.clinic.domain.Prescription;
import com.clinic.domain.PrescriptionStatus;
import com.clinic.domain.RefillRequest;
import com.clinic.security.CurrentUser;
import com.clinic.service.PrescriptionService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/** The patient's medication list and their refill requests. */
@Named("patientPrescriptions")
@ViewScoped
public class PatientPrescriptionsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private PrescriptionService prescriptionService;

    private List<Prescription> prescriptions = List.of();
    private List<RefillRequest> refills = List.of();
    private Long refillTargetId;
    private String refillNote;

    @PostConstruct
    public void load() {
        try {
            Long patientId = currentUser.getPatientId();
            if (patientId != null) {
                prescriptions = prescriptionService.forPatient(patientId);
                refills = prescriptionService.refillsForPatient(patientId);
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public void prepareRefill(Prescription prescription) {
        refillTargetId = prescription.getId();
        refillNote = null;
    }

    public String requestRefill() {
        try {
            prescriptionService.requestRefill(refillTargetId, refillNote);
            Faces.success("Your refill request has been sent to your clinician.");
            refillTargetId = null;
            refillNote = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String cancelRefill(RefillRequest request) {
        try {
            prescriptionService.cancelRefill(request.getId());
            Faces.success("Refill request withdrawn.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public boolean isRefillable(Prescription prescription) {
        if (prescription.getStatus() != PrescriptionStatus.ACTIVE) {
            return false;
        }
        return refills.stream()
                .noneMatch(r -> r.getPrescription().getId().equals(prescription.getId())
                        && r.getStatus().isOpen());
    }

    public List<Prescription> getPrescriptions() {
        return prescriptions;
    }

    public List<RefillRequest> getRefills() {
        return refills;
    }

    public Long getRefillTargetId() {
        return refillTargetId;
    }

    public void setRefillTargetId(Long refillTargetId) {
        this.refillTargetId = refillTargetId;
    }

    public String getRefillNote() {
        return refillNote;
    }

    public void setRefillNote(String refillNote) {
        this.refillNote = refillNote;
    }
}
