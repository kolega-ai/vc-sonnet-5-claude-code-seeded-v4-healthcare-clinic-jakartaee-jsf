package com.clinic.web;

import com.clinic.domain.Appointment;
import com.clinic.domain.AppointmentType;
import com.clinic.domain.Provider;
import com.clinic.security.CurrentUser;
import com.clinic.service.AppointmentService;
import com.clinic.service.AvailabilitySlot;
import com.clinic.service.ProviderService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The patient's appointment list plus the self-booking flow: choose a provider
 * and visit type, pick from real open slots, give a reason, confirm.
 */
@Named("patientAppointments")
@ViewScoped
public class PatientAppointmentsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private AppointmentService appointmentService;

    @EJB
    private ProviderService providerService;

    private List<Appointment> appointments = List.of();
    private List<Provider> providers = List.of();
    private List<AppointmentType> types = List.of();
    private List<AvailabilitySlot> slots = List.of();

    private Long providerId;
    private Long typeId;
    private LocalDate searchFrom = LocalDate.now().plusDays(1);
    private String selectedSlot;
    private String reason;
    private String cancelReason;
    private Long cancelTargetId;
    private boolean searched;

    @PostConstruct
    public void load() {
        try {
            if (currentUser.getPatientId() != null) {
                appointments = appointmentService.forPatient(currentUser.getPatientId());
            }
            providers = providerService.listActiveProviders();
            types = providerService.listBookableTypes();
            if (!types.isEmpty() && typeId == null) {
                typeId = types.get(0).getId();
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String findSlots() {
        searched = true;
        try {
            LocalDate from = searchFrom == null ? LocalDate.now() : searchFrom;
            slots = appointmentService.availability(providerId, typeId, from, from.plusDays(21));
            if (slots.isEmpty()) {
                Faces.warn("No open slots in the next three weeks for that combination. "
                        + "Try another provider or a later start date.");
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String book() {
        if (selectedSlot == null) {
            Faces.error("Choose an appointment slot first.");
            return null;
        }
        try {
            LocalDateTime start = LocalDateTime.parse(selectedSlot);
            Long chosenProvider = providerId;
            if (chosenProvider == null) {
                chosenProvider = slots.stream()
                        .filter(s -> s.start().equals(start))
                        .map(AvailabilitySlot::providerId)
                        .findFirst().orElse(null);
            }
            appointmentService.requestAppointment(chosenProvider, typeId, start, reason);
            Faces.success("Your appointment request has been sent. "
                    + "The front desk will confirm it shortly.");
            selectedSlot = null;
            reason = null;
            slots = List.of();
            searched = false;
            load();
        } catch (DateTimeParseException e) {
            Faces.error("That slot is no longer valid. Please search again.");
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String cancel() {
        try {
            appointmentService.cancelAsPatient(cancelTargetId, cancelReason);
            Faces.success("Your appointment has been cancelled.");
            cancelReason = null;
            cancelTargetId = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public void prepareCancel(Appointment appointment) {
        cancelTargetId = appointment.getId();
        cancelReason = null;
    }

    /** Only future, non-terminal bookings can be withdrawn by the patient. */
    public boolean isCancellable(Appointment appointment) {
        return appointment != null
                && !appointment.getStatus().isTerminal()
                && appointment.getStartAt().isAfter(LocalDateTime.now());
    }

    // ------------------------------------------------------------ accessors

    public List<Appointment> getAppointments() {
        return appointments;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public List<AppointmentType> getTypes() {
        return types;
    }

    public List<AvailabilitySlot> getSlots() {
        return slots;
    }

    public boolean isSearched() {
        return searched;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Long getTypeId() {
        return typeId;
    }

    public void setTypeId(Long typeId) {
        this.typeId = typeId;
    }

    public LocalDate getSearchFrom() {
        return searchFrom;
    }

    public void setSearchFrom(LocalDate searchFrom) {
        this.searchFrom = searchFrom;
    }

    public String getSelectedSlot() {
        return selectedSlot;
    }

    public void setSelectedSlot(String selectedSlot) {
        this.selectedSlot = selectedSlot;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public Long getCancelTargetId() {
        return cancelTargetId;
    }

    public void setCancelTargetId(Long cancelTargetId) {
        this.cancelTargetId = cancelTargetId;
    }
}
