package com.clinic.web;

import com.clinic.domain.Appointment;
import com.clinic.domain.AppointmentStatus;
import com.clinic.domain.AppointmentType;
import com.clinic.domain.Patient;
import com.clinic.domain.Provider;
import com.clinic.security.CurrentUser;
import com.clinic.service.AppointmentService;
import com.clinic.service.AvailabilitySlot;
import com.clinic.service.PatientSearchCriteria;
import com.clinic.service.PatientService;
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
import java.util.Map;

/**
 * The staff schedule board: a day's calendar with check-in, completion,
 * cancellation, no-show, rescheduling and staff-side booking.
 */
@Named("scheduleBoard")
@ViewScoped
public class ScheduleBoardBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private AppointmentService appointmentService;

    @EJB
    private ProviderService providerService;

    @EJB
    private PatientService patientService;

    private LocalDate day = LocalDate.now();
    private Long providerFilter;
    private String statusFilter;
    private String textFilter;

    private List<Appointment> appointments = List.of();
    private List<Appointment> requests = List.of();
    private List<Provider> providers = List.of();
    private List<AppointmentType> types = List.of();
    private Map<String, Long> statusCounts = Map.of();

    private Appointment selected;
    private String actionNote;

    private LocalDate rescheduleDate = LocalDate.now().plusDays(1);
    private Long rescheduleProviderId;
    private String rescheduleSlot;
    private List<AvailabilitySlot> rescheduleSlots = List.of();

    private String patientSearch;
    private List<Patient> patientResults = List.of();
    private Long bookPatientId;
    private Long bookProviderId;
    private Long bookTypeId;
    private String bookSlot;
    private String bookReason;
    private List<AvailabilitySlot> bookSlots = List.of();

    @PostConstruct
    public void load() {
        try {
            AppointmentStatus status = statusFilter == null || statusFilter.isBlank()
                    ? null : AppointmentStatus.fromCode(statusFilter);
            appointments = appointmentService.board(day, providerFilter, status, textFilter);
            requests = appointmentService.queueAwaitingConfirmation();
            providers = providerService.listActiveProviders();
            types = providerService.listActiveTypes();
            statusCounts = appointmentService.statusCountsForDay(day);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String search() {
        load();
        return null;
    }

    public String resetFilters() {
        day = LocalDate.now();
        providerFilter = null;
        statusFilter = null;
        textFilter = null;
        load();
        return null;
    }

    public String previousDay() {
        day = day.minusDays(1);
        load();
        return null;
    }

    public String nextDay() {
        day = day.plusDays(1);
        load();
        return null;
    }

    public String today() {
        day = LocalDate.now();
        load();
        return null;
    }

    // ------------------------------------------------------------ workflow

    public void select(Appointment appointment) {
        selected = appointment;
        actionNote = null;
        rescheduleProviderId = appointment.getProvider().getId();
        rescheduleSlots = List.of();
        rescheduleSlot = null;
    }

    public String transition(Appointment appointment, String targetCode) {
        try {
            appointmentService.transition(appointment.getId(),
                    AppointmentStatus.fromCode(targetCode), actionNote);
            Faces.success("Appointment updated.");
            actionNote = null;
            selected = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String confirmRequest(Appointment appointment) {
        return transition(appointment, AppointmentStatus.SCHEDULED.getCode());
    }

    public String cancelSelected() {
        if (selected == null) {
            return null;
        }
        return transition(selected, AppointmentStatus.CANCELLED.getCode());
    }

    public boolean canTransition(Appointment appointment, String targetCode) {
        if (appointment == null) {
            return false;
        }
        AppointmentStatus target = AppointmentStatus.fromCode(targetCode);
        if (!appointment.getStatus().canTransitionTo(target)) {
            return false;
        }
        // Front desk may not close out a clinical encounter.
        return !(currentUser.isFrontDesk() && target == AppointmentStatus.COMPLETED);
    }

    public String findRescheduleSlots() {
        if (selected == null) {
            return null;
        }
        try {
            Long provider = rescheduleProviderId == null
                    ? selected.getProvider().getId() : rescheduleProviderId;
            rescheduleSlots = appointmentService.availability(provider,
                    selected.getAppointmentType().getId(),
                    rescheduleDate, rescheduleDate.plusDays(14));
            if (rescheduleSlots.isEmpty()) {
                Faces.warn("No open slots in that window.");
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String reschedule() {
        if (selected == null || rescheduleSlot == null) {
            Faces.error("Choose a new slot first.");
            return null;
        }
        try {
            appointmentService.reschedule(selected.getId(), rescheduleProviderId,
                    LocalDateTime.parse(rescheduleSlot));
            Faces.success("Appointment rescheduled.");
            selected = null;
            rescheduleSlot = null;
            rescheduleSlots = List.of();
            load();
        } catch (DateTimeParseException e) {
            Faces.error("That slot is no longer valid. Please search again.");
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String saveStaffNotes() {
        if (selected == null) {
            return null;
        }
        try {
            appointmentService.updateStaffNotes(selected.getId(), selected.getStaffNotes());
            Faces.success("Internal notes saved.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    // ------------------------------------------------------- staff booking

    public String findPatients() {
        try {
            PatientSearchCriteria criteria = new PatientSearchCriteria();
            criteria.setText(patientSearch);
            criteria.setMaxResults(25);
            patientResults = patientService.search(criteria);
            if (patientResults.isEmpty()) {
                Faces.warn("No patients matched that search.");
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String findBookingSlots() {
        try {
            bookSlots = appointmentService.availability(bookProviderId, bookTypeId,
                    day, day.plusDays(21));
            if (bookSlots.isEmpty()) {
                Faces.warn("No open slots for that provider and visit type.");
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String bookForPatient() {
        if (bookPatientId == null || bookSlot == null) {
            Faces.error("Choose a patient and a slot.");
            return null;
        }
        try {
            appointmentService.scheduleForPatient(bookPatientId, bookProviderId, bookTypeId,
                    LocalDateTime.parse(bookSlot), bookReason);
            Faces.success("Appointment booked.");
            bookSlot = null;
            bookReason = null;
            bookSlots = List.of();
            load();
        } catch (DateTimeParseException e) {
            Faces.error("That slot is no longer valid. Please search again.");
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String scheduleExportUrl() {
        return Faces.contextPath() + "/api/secure/exports/schedule.csv?from="
                + day + "&to=" + day.plusDays(7);
    }

    public AppointmentStatus[] getAllStatuses() {
        return AppointmentStatus.values();
    }

    // ------------------------------------------------------------ accessors

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public Long getProviderFilter() {
        return providerFilter;
    }

    public void setProviderFilter(Long providerFilter) {
        this.providerFilter = providerFilter;
    }

    public String getStatusFilter() {
        return statusFilter;
    }

    public void setStatusFilter(String statusFilter) {
        this.statusFilter = statusFilter;
    }

    public String getTextFilter() {
        return textFilter;
    }

    public void setTextFilter(String textFilter) {
        this.textFilter = textFilter;
    }

    public List<Appointment> getAppointments() {
        return appointments;
    }

    public List<Appointment> getRequests() {
        return requests;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public List<AppointmentType> getTypes() {
        return types;
    }

    public Map<String, Long> getStatusCounts() {
        return statusCounts;
    }

    public Appointment getSelected() {
        return selected;
    }

    public String getActionNote() {
        return actionNote;
    }

    public void setActionNote(String actionNote) {
        this.actionNote = actionNote;
    }

    public LocalDate getRescheduleDate() {
        return rescheduleDate;
    }

    public void setRescheduleDate(LocalDate rescheduleDate) {
        this.rescheduleDate = rescheduleDate;
    }

    public Long getRescheduleProviderId() {
        return rescheduleProviderId;
    }

    public void setRescheduleProviderId(Long rescheduleProviderId) {
        this.rescheduleProviderId = rescheduleProviderId;
    }

    public String getRescheduleSlot() {
        return rescheduleSlot;
    }

    public void setRescheduleSlot(String rescheduleSlot) {
        this.rescheduleSlot = rescheduleSlot;
    }

    public List<AvailabilitySlot> getRescheduleSlots() {
        return rescheduleSlots;
    }

    public String getPatientSearch() {
        return patientSearch;
    }

    public void setPatientSearch(String patientSearch) {
        this.patientSearch = patientSearch;
    }

    public List<Patient> getPatientResults() {
        return patientResults;
    }

    public Long getBookPatientId() {
        return bookPatientId;
    }

    public void setBookPatientId(Long bookPatientId) {
        this.bookPatientId = bookPatientId;
    }

    public Long getBookProviderId() {
        return bookProviderId;
    }

    public void setBookProviderId(Long bookProviderId) {
        this.bookProviderId = bookProviderId;
    }

    public Long getBookTypeId() {
        return bookTypeId;
    }

    public void setBookTypeId(Long bookTypeId) {
        this.bookTypeId = bookTypeId;
    }

    public String getBookSlot() {
        return bookSlot;
    }

    public void setBookSlot(String bookSlot) {
        this.bookSlot = bookSlot;
    }

    public String getBookReason() {
        return bookReason;
    }

    public void setBookReason(String bookReason) {
        this.bookReason = bookReason;
    }

    public List<AvailabilitySlot> getBookSlots() {
        return bookSlots;
    }
}
