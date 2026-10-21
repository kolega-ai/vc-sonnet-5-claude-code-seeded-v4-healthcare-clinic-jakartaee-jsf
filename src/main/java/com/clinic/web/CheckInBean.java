package com.clinic.web;

import com.clinic.domain.Appointment;
import com.clinic.domain.Patient;
import com.clinic.service.CheckInService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import jakarta.servlet.http.Cookie;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The waiting-room kiosk: a patient checks themselves in and follows the visit.
 */
@Named("checkIn")
@ViewScoped
public class CheckInBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String PASS_COOKIE = "INTAKE_PASS";

    @EJB
    private CheckInService checkInService;

    private String mrn;
    private String dateOfBirth;
    private Patient patient;
    private Appointment visit;
    private boolean checkedIn;

    @PostConstruct
    public void load() {
        String pass = readPass();
        if (pass != null) {
            resume(pass);
        }
    }

    public String begin() {
        try {
            Patient found = checkInService.requireCheckIn(mrn, LocalDate.parse(dateOfBirth));
            writePass(checkInService.issuePass(found.getId()));
            patient = found;
            visit = checkInService.todaysVisit(found.getId());
            checkedIn = true;
            mrn = null;
            dateOfBirth = null;
            Faces.success("You are checked in. Keep this device to follow your visit.");
        } catch (DateTimeParseException e) {
            Faces.error("Enter your date of birth as YYYY-MM-DD.");
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String startOver() {
        writePass("");
        patient = null;
        visit = null;
        checkedIn = false;
        return null;
    }

    private void resume(String pass) {
        Optional<Long> id = checkInService.patientFromPass(pass);
        if (id.isEmpty()) {
            return;
        }
        try {
            patient = checkInService.patient(id.get());
            visit = checkInService.todaysVisit(id.get());
            checkedIn = true;
        } catch (RuntimeException e) {
            patient = null;
        }
    }

    private static String readPass() {
        Object cookie = FacesContext.getCurrentInstance().getExternalContext()
                .getRequestCookieMap().get(PASS_COOKIE);
        return cookie instanceof Cookie c ? c.getValue() : null;
    }

    private static void writePass(String value) {
        FacesContext.getCurrentInstance().getExternalContext()
                .addResponseCookie(PASS_COOKIE, value, null);
    }

    // ------------------------------------------------------------ accessors

    public String getMrn() {
        return mrn;
    }

    public void setMrn(String mrn) {
        this.mrn = mrn;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public Patient getPatient() {
        return patient;
    }

    public Appointment getVisit() {
        return visit;
    }

    public boolean isCheckedIn() {
        return checkedIn;
    }
}
