package com.clinic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** Type of appointment (visit reason) offered by the clinic. */
@Entity
@Table(name = "appointment_type")
@NamedQueries({
        @NamedQuery(name = "AppointmentType.active",
                query = "SELECT t FROM AppointmentType t WHERE t.active = true ORDER BY t.name"),
        @NamedQuery(name = "AppointmentType.bookable",
                query = "SELECT t FROM AppointmentType t WHERE t.active = true AND t.patientBookable = true ORDER BY t.name"),
        @NamedQuery(name = "AppointmentType.byCode",
                query = "SELECT t FROM AppointmentType t WHERE t.code = :code")
})
public class AppointmentType implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 30;

    @Column(name = "color", nullable = false, length = 16)
    private String color = "#2563eb";

    @Column(name = "patient_bookable", nullable = false)
    private boolean patientBookable = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public boolean isPatientBookable() {
        return patientBookable;
    }

    public void setPatientBookable(boolean patientBookable) {
        this.patientBookable = patientBookable;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AppointmentType other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
