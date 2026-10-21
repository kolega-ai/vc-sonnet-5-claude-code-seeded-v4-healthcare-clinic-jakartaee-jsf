package com.clinic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** Patient request to refill an existing prescription, routed to staff for a decision. */
@Entity
@Table(name = "refill_request")
@NamedQueries({
        @NamedQuery(name = "RefillRequest.open",
                query = "SELECT r FROM RefillRequest r WHERE r.status IN "
                        + "(com.clinic.domain.RefillStatus.REQUESTED, com.clinic.domain.RefillStatus.NEEDS_INFO) "
                        + "ORDER BY r.requestedAt"),
        @NamedQuery(name = "RefillRequest.byPatient",
                query = "SELECT r FROM RefillRequest r WHERE r.patient.id = :patientId ORDER BY r.requestedAt DESC"),
        @NamedQuery(name = "RefillRequest.byPrescription",
                query = "SELECT r FROM RefillRequest r WHERE r.prescription.id = :prescriptionId ORDER BY r.requestedAt DESC")
})
public class RefillRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id")
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Column(name = "status", nullable = false, length = 24)
    private RefillStatus status;

    @Column(name = "patient_note", length = 1000)
    private String patientNote;

    @Column(name = "staff_note", length = 1000)
    private String staffNote;

    @Column(name = "collection_reference", length = 12)
    private String collectionReference;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_user_id")
    private AppUser decidedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Prescription getPrescription() {
        return prescription;
    }

    public void setPrescription(Prescription prescription) {
        this.prescription = prescription;
    }

    public Patient getPatient() {
        return patient;
    }

    public void setPatient(Patient patient) {
        this.patient = patient;
    }

    public RefillStatus getStatus() {
        return status;
    }

    public void setStatus(RefillStatus status) {
        this.status = status;
    }

    public String getPatientNote() {
        return patientNote;
    }

    public void setPatientNote(String patientNote) {
        this.patientNote = patientNote;
    }

    public String getStaffNote() {
        return staffNote;
    }

    public void setStaffNote(String staffNote) {
        this.staffNote = staffNote;
    }

    public String getCollectionReference() {
        return collectionReference;
    }

    public void setCollectionReference(String collectionReference) {
        this.collectionReference = collectionReference;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public AppUser getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(AppUser decidedBy) {
        this.decidedBy = decidedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RefillRequest other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
