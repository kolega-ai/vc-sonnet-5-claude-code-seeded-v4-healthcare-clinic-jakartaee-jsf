package com.clinic.web;

import com.clinic.domain.AppointmentType;
import com.clinic.domain.Provider;
import com.clinic.domain.ProviderSchedule;
import com.clinic.service.ProviderService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider directory, weekly schedule templates and the appointment catalogue. */
@Named("adminProviders")
@ViewScoped
public class AdminProvidersBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private ProviderService providerService;

    private List<Provider> providers = List.of();
    private List<AppointmentType> types = List.of();
    private final Map<Long, List<ProviderSchedule>> schedules = new LinkedHashMap<>();

    private Provider providerForm = new Provider();
    private AppointmentType typeForm = newType();

    private Long scheduleProviderId;
    private int scheduleDay = 1;
    private LocalTime scheduleStart = LocalTime.of(9, 0);
    private LocalTime scheduleEnd = LocalTime.of(12, 0);
    private int scheduleSlotMinutes = 30;

    @PostConstruct
    public void load() {
        try {
            providers = providerService.listAllProviders();
            types = providerService.listActiveTypes();
            schedules.clear();
            for (Provider provider : providers) {
                schedules.put(provider.getId(), providerService.schedulesFor(provider.getId()));
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    private static AppointmentType newType() {
        AppointmentType type = new AppointmentType();
        type.setDurationMinutes(30);
        type.setColor("#2563eb");
        type.setPatientBookable(true);
        type.setActive(true);
        return type;
    }

    public List<ProviderSchedule> schedulesFor(Provider provider) {
        return schedules.getOrDefault(provider.getId(), List.of());
    }

    public void editProvider(Provider provider) {
        providerForm = new Provider();
        providerForm.setId(provider.getId());
        providerForm.setFirstName(provider.getFirstName());
        providerForm.setLastName(provider.getLastName());
        providerForm.setSpecialty(provider.getSpecialty());
        providerForm.setCredentials(provider.getCredentials());
        providerForm.setRoom(provider.getRoom());
        providerForm.setNpi(provider.getNpi());
        providerForm.setActive(provider.isActive());
    }

    public void newProvider() {
        providerForm = new Provider();
        providerForm.setActive(true);
    }

    public String saveProvider() {
        try {
            providerService.saveProvider(providerForm);
            Faces.success("Provider saved.");
            newProvider();
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String addSchedule() {
        try {
            providerService.addSchedule(scheduleProviderId, scheduleDay, scheduleStart,
                    scheduleEnd, scheduleSlotMinutes);
            Faces.success("Schedule block added.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String removeSchedule(ProviderSchedule schedule) {
        try {
            providerService.deactivateSchedule(schedule.getId());
            Faces.success("Schedule block removed.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public void editType(AppointmentType type) {
        typeForm = new AppointmentType();
        typeForm.setId(type.getId());
        typeForm.setCode(type.getCode());
        typeForm.setName(type.getName());
        typeForm.setDurationMinutes(type.getDurationMinutes());
        typeForm.setColor(type.getColor());
        typeForm.setPatientBookable(type.isPatientBookable());
        typeForm.setActive(type.isActive());
    }

    public void newAppointmentType() {
        typeForm = newType();
    }

    public String saveType() {
        try {
            providerService.saveType(typeForm);
            Faces.success("Appointment type saved.");
            newAppointmentType();
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public Map<Integer, String> getDayOptions() {
        Map<Integer, String> days = new LinkedHashMap<>();
        days.put(1, "Monday");
        days.put(2, "Tuesday");
        days.put(3, "Wednesday");
        days.put(4, "Thursday");
        days.put(5, "Friday");
        days.put(6, "Saturday");
        days.put(7, "Sunday");
        return days;
    }

    // ------------------------------------------------------------ accessors

    public List<Provider> getProviders() {
        return providers;
    }

    public List<AppointmentType> getTypes() {
        return types;
    }

    public Provider getProviderForm() {
        return providerForm;
    }

    public AppointmentType getTypeForm() {
        return typeForm;
    }

    public Long getScheduleProviderId() {
        return scheduleProviderId;
    }

    public void setScheduleProviderId(Long scheduleProviderId) {
        this.scheduleProviderId = scheduleProviderId;
    }

    public int getScheduleDay() {
        return scheduleDay;
    }

    public void setScheduleDay(int scheduleDay) {
        this.scheduleDay = scheduleDay;
    }

    public LocalTime getScheduleStart() {
        return scheduleStart;
    }

    public void setScheduleStart(LocalTime scheduleStart) {
        this.scheduleStart = scheduleStart;
    }

    public LocalTime getScheduleEnd() {
        return scheduleEnd;
    }

    public void setScheduleEnd(LocalTime scheduleEnd) {
        this.scheduleEnd = scheduleEnd;
    }

    public int getScheduleSlotMinutes() {
        return scheduleSlotMinutes;
    }

    public void setScheduleSlotMinutes(int scheduleSlotMinutes) {
        this.scheduleSlotMinutes = scheduleSlotMinutes;
    }
}
