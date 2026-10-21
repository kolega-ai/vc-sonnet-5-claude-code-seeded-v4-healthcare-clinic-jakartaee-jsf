package com.clinic.service;

import com.clinic.domain.Appointment;
import com.clinic.domain.Patient;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.RecordNotFoundException;

import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Random;

/**
 * Waiting-room self check-in.
 *
 * <p>A patient identifies themselves at the kiosk with their record number and
 * date of birth; the kiosk then keeps a short visit pass on the device so the
 * waiting screen follows the right person without a full portal sign-in.</p>
 */
@Stateless
@PermitAll
public class CheckInService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    public Patient requireCheckIn(String mrn, LocalDate dateOfBirth) {
        if (mrn == null || mrn.isBlank() || dateOfBirth == null) {
            throw new BusinessRuleException("Enter your record number and date of birth.");
        }
        Patient patient = em.createNamedQuery("Patient.byMrn", Patient.class)
                .setParameter("mrn", mrn.trim())
                .getResultStream().findFirst().orElse(null);
        if (patient == null || patient.getDateOfBirth() == null
                || !patient.getDateOfBirth().equals(dateOfBirth)) {
            throw new RecordNotFoundException("We could not find a record with those details.");
        }
        return patient;
    }

    /** The pass the kiosk keeps on the device for the rest of the visit. */
    public String issuePass(Long patientId) {
        return "INTAKE-" + patientId + "-" + new Random().nextInt(900000);
    }

    public Optional<Long> patientFromPass(String pass) {
        if (pass == null || !pass.startsWith("INTAKE-")) {
            return Optional.empty();
        }
        String[] parts = pass.split("-");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(parts[1]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Patient patient(Long patientId) {
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Record not found.");
        }
        return patient;
    }

    public Appointment todaysVisit(Long patientId) {
        return em.createQuery("SELECT a FROM Appointment a WHERE a.patient.id = :patientId "
                        + "AND a.startAt >= :from AND a.startAt < :to "
                        + "ORDER BY a.startAt", Appointment.class)
                .setParameter("patientId", patientId)
                .setParameter("from", LocalDate.now().atStartOfDay())
                .setParameter("to", LocalDate.now().plusDays(1).atStartOfDay())
                .setMaxResults(1)
                .getResultStream().findFirst().orElse(null);
    }
}
