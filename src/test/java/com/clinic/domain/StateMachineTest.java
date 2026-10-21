package com.clinic.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workflow rules the specification names are enforced by these edge lists,
 * so they are asserted directly rather than only through the services.
 */
class StateMachineTest {

    @Test
    @DisplayName("every appointment status is reachable through a declared edge")
    void appointmentStatusesAreReachable() {
        Set<AppointmentStatus> reachable = new HashSet<>();
        reachable.add(AppointmentStatus.REQUESTED);
        reachable.add(AppointmentStatus.SCHEDULED);
        for (AppointmentStatus from : AppointmentStatus.values()) {
            reachable.addAll(from.allowedTargets());
        }
        assertEquals(AppointmentStatus.values().length, reachable.size(),
                "an unreachable appointment status would be un-seedable by the application");
    }

    @Test
    void appointmentEdgesMatchTheSpecification() {
        assertTrue(AppointmentStatus.REQUESTED.canTransitionTo(AppointmentStatus.SCHEDULED));
        assertTrue(AppointmentStatus.REQUESTED.canTransitionTo(AppointmentStatus.CANCELLED));
        assertFalse(AppointmentStatus.REQUESTED.canTransitionTo(AppointmentStatus.COMPLETED));

        assertTrue(AppointmentStatus.SCHEDULED.canTransitionTo(AppointmentStatus.CHECKED_IN));
        assertTrue(AppointmentStatus.SCHEDULED.canTransitionTo(AppointmentStatus.NO_SHOW));
        assertTrue(AppointmentStatus.CHECKED_IN.canTransitionTo(AppointmentStatus.COMPLETED));

        assertTrue(AppointmentStatus.COMPLETED.isTerminal());
        assertTrue(AppointmentStatus.CANCELLED.isTerminal());
        assertFalse(AppointmentStatus.COMPLETED.canTransitionTo(AppointmentStatus.SCHEDULED));
        assertFalse(AppointmentStatus.CANCELLED.canTransitionTo(AppointmentStatus.SCHEDULED));
    }

    @Test
    @DisplayName("a draft visit summary is never patient-visible")
    void visitSummaryVisibility() {
        assertFalse(VisitSummaryStatus.DRAFT.isPatientVisible());
        assertTrue(VisitSummaryStatus.RELEASED.isPatientVisible());
        assertTrue(VisitSummaryStatus.AMENDED.isPatientVisible());

        assertTrue(VisitSummaryStatus.DRAFT.canTransitionTo(VisitSummaryStatus.RELEASED));
        assertFalse(VisitSummaryStatus.DRAFT.canTransitionTo(VisitSummaryStatus.AMENDED));
        assertTrue(VisitSummaryStatus.RELEASED.canTransitionTo(VisitSummaryStatus.AMENDED));
    }

    @Test
    void refillEdgesCoverEveryDecision() {
        assertTrue(RefillStatus.REQUESTED.canTransitionTo(RefillStatus.APPROVED));
        assertTrue(RefillStatus.REQUESTED.canTransitionTo(RefillStatus.DENIED));
        assertTrue(RefillStatus.REQUESTED.canTransitionTo(RefillStatus.NEEDS_INFO));
        assertTrue(RefillStatus.REQUESTED.canTransitionTo(RefillStatus.CANCELLED));
        assertTrue(RefillStatus.NEEDS_INFO.canTransitionTo(RefillStatus.APPROVED));

        assertFalse(RefillStatus.APPROVED.canTransitionTo(RefillStatus.DENIED));
        assertFalse(RefillStatus.DENIED.canTransitionTo(RefillStatus.APPROVED));
        assertTrue(RefillStatus.REQUESTED.isOpen());
        assertTrue(RefillStatus.NEEDS_INFO.isOpen());
        assertFalse(RefillStatus.APPROVED.isOpen());
    }

    @Test
    void documentEdgesAllowReSubmissionAfterRejection() {
        assertTrue(DocumentStatus.SUBMITTED.canTransitionTo(DocumentStatus.VERIFIED));
        assertTrue(DocumentStatus.SUBMITTED.canTransitionTo(DocumentStatus.REJECTED));
        assertTrue(DocumentStatus.REJECTED.canTransitionTo(DocumentStatus.SUBMITTED));
        assertFalse(DocumentStatus.ARCHIVED.canTransitionTo(DocumentStatus.VERIFIED));
    }

    @Test
    void prescriptionEdges() {
        assertTrue(PrescriptionStatus.ACTIVE.canTransitionTo(PrescriptionStatus.EXPIRED));
        assertTrue(PrescriptionStatus.ACTIVE.canTransitionTo(PrescriptionStatus.DISCONTINUED));
        assertFalse(PrescriptionStatus.DISCONTINUED.canTransitionTo(PrescriptionStatus.ACTIVE));
    }

    @Test
    void threadEdges() {
        assertTrue(ThreadStatus.OPEN.canTransitionTo(ThreadStatus.RESOLVED));
        assertTrue(ThreadStatus.RESOLVED.canTransitionTo(ThreadStatus.OPEN));
        assertFalse(ThreadStatus.CLOSED.canTransitionTo(ThreadStatus.OPEN));
    }

    @Test
    @DisplayName("database codes and Java constants never drift")
    void codesRoundTrip() {
        for (AppointmentStatus s : AppointmentStatus.values()) {
            assertEquals(s, AppointmentStatus.fromCode(s.getCode()));
        }
        for (VisitSummaryStatus s : VisitSummaryStatus.values()) {
            assertEquals(s, VisitSummaryStatus.fromCode(s.getCode()));
        }
        for (RefillStatus s : RefillStatus.values()) {
            assertEquals(s, RefillStatus.fromCode(s.getCode()));
        }
        for (DocumentStatus s : DocumentStatus.values()) {
            assertEquals(s, DocumentStatus.fromCode(s.getCode()));
        }
        for (DocumentCategory c : DocumentCategory.values()) {
            assertEquals(c, DocumentCategory.fromCode(c.getCode()));
        }
        for (Role r : Role.values()) {
            assertEquals(r, Role.fromCode(r.getCode()));
        }
    }

    @Test
    void onlyPatientRoleIsNonStaff() {
        assertFalse(Role.PATIENT.isStaff());
        assertTrue(Role.CLINICIAN.isStaff());
        assertTrue(Role.FRONT_DESK.isStaff());
        assertTrue(Role.CLINIC_ADMIN.isStaff());
    }

    @Test
    void patientsMaySubmitOnlyTheirOwnCategories() {
        assertTrue(DocumentCategory.PRE_VISIT_FORM.isPatientSubmittable());
        assertTrue(DocumentCategory.INSURANCE_CARD.isPatientSubmittable());
        assertTrue(DocumentCategory.CONSENT.isPatientSubmittable());
        assertFalse(DocumentCategory.CLINICAL_NOTE.isPatientSubmittable());
        assertFalse(DocumentCategory.LAB_RESULT.isPatientSubmittable());
    }
}
