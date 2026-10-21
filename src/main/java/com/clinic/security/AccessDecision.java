package com.clinic.security;

/**
 * The outcome of an ownership/scope check: which rule permitted the action.
 * The basis string is written to the audit log so a reviewer can see not just
 * that access happened but why it was allowed.
 */
public record AccessDecision(String basis) {

    public static AccessDecision self() {
        return new AccessDecision("patient:self");
    }

    public static AccessDecision assignedClinician(long providerId) {
        return new AccessDecision("clinician:assigned:provider=" + providerId);
    }

    public static AccessDecision treatingClinician(long providerId) {
        return new AccessDecision("clinician:has_appointment:provider=" + providerId);
    }

    public static AccessDecision frontDesk() {
        return new AccessDecision("front_desk:scheduling_scope");
    }

    public static AccessDecision clinicAdmin() {
        return new AccessDecision("clinic_admin:administrative_scope");
    }

    @Override
    public String toString() {
        return basis;
    }
}
