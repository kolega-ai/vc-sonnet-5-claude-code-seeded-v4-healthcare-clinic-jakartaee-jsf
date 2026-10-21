package com.clinic.web;

import com.clinic.domain.RefillRequest;
import com.clinic.domain.RefillStatus;
import com.clinic.security.CurrentUser;
import com.clinic.service.MessageService;
import com.clinic.service.PrescriptionService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/**
 * The prescription refill queue: approve, deny, ask for more information, or
 * open a message thread with the patient.
 */
@Named("refillQueue")
@ViewScoped
public class RefillQueueBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private PrescriptionService prescriptionService;

    @EJB
    private MessageService messageService;

    private List<RefillRequest> queue = List.of();
    private RefillRequest selected;
    private String staffNote;
    private String pharmacyAccessCode;
    private String messageSubject;
    private String messageBody;

    @PostConstruct
    public void load() {
        try {
            queue = prescriptionService.openQueue();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public void select(RefillRequest request) {
        selected = request;
        staffNote = null;
        messageSubject = "About your " + request.getPrescription().getMedicationName()
                + " refill request";
        messageBody = null;
    }

    public String approve() {
        return decide(RefillStatus.APPROVED, "Refill approved.");
    }

    public String deny() {
        return decide(RefillStatus.DENIED, "Refill denied.");
    }

    public String requestInformation() {
        return decide(RefillStatus.NEEDS_INFO, "More information requested from the patient.");
    }

    private String decide(RefillStatus target, String message) {
        if (selected == null) {
            Faces.error("Choose a request first.");
            return null;
        }
        try {
            prescriptionService.decide(selected.getId(), target, staffNote);
            Faces.success(message);
            selected = null;
            staffNote = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    /** Sends an approved refill to the partner pharmacy's dispensing bridge. */
    public String sendToPharmacy() {
        if (selected == null) {
            Faces.error("Choose a request first.");
            return null;
        }
        try {
            prescriptionService.dispatchToPharmacy(selected.getId(), pharmacyAccessCode);
            Faces.success("The refill was sent to the pharmacy bridge.");
            pharmacyAccessCode = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    /** Opens a secure message thread with the patient about this request. */
    public String messagePatient() {
        if (selected == null) {
            Faces.error("Choose a request first.");
            return null;
        }
        try {
            messageService.startThread(selected.getPatient().getId(), messageSubject,
                    "prescription", messageBody, null);
            Faces.success("Message sent to the patient.");
            messageBody = null;
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public boolean isDecider() {
        return currentUser.isClinician();
    }

    public List<RefillRequest> getQueue() {
        return queue;
    }

    public boolean isEmptyQueue() {
        return queue.isEmpty();
    }

    public RefillRequest getSelected() {
        return selected;
    }

    public String getStaffNote() {
        return staffNote;
    }

    public void setStaffNote(String staffNote) {
        this.staffNote = staffNote;
    }

    public String getPharmacyAccessCode() {
        return pharmacyAccessCode;
    }

    public void setPharmacyAccessCode(String pharmacyAccessCode) {
        this.pharmacyAccessCode = pharmacyAccessCode;
    }

    public String getMessageSubject() {
        return messageSubject;
    }

    public void setMessageSubject(String messageSubject) {
        this.messageSubject = messageSubject;
    }

    public String getMessageBody() {
        return messageBody;
    }

    public void setMessageBody(String messageBody) {
        this.messageBody = messageBody;
    }
}
