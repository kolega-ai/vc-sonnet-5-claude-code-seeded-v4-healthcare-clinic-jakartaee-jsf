package com.clinic.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Appointment state machine.
 *
 * <p>Allowed transition edge list (every reachable status is produced by one of
 * these edges; nothing is seeded into an otherwise unreachable state):</p>
 * <pre>
 *   requested   -> scheduled | cancelled
 *   scheduled   -> checked_in | cancelled | no_show | scheduled (reschedule)
 *   checked_in  -> completed | cancelled | no_show
 *   completed   -> (terminal)
 *   cancelled   -> (terminal)
 *   no_show     -> scheduled (re-booked in place)
 * </pre>
 */
public enum AppointmentStatus implements CodedEnum {

    REQUESTED("requested", "Requested", "warning"),
    SCHEDULED("scheduled", "Scheduled", "info"),
    CHECKED_IN("checked_in", "Checked in", "primary"),
    COMPLETED("completed", "Completed", "success"),
    CANCELLED("cancelled", "Cancelled", "neutral"),
    NO_SHOW("no_show", "No show", "danger");

    private static final Map<AppointmentStatus, Set<AppointmentStatus>> EDGES = Map.of(
            REQUESTED, EnumSet.of(SCHEDULED, CANCELLED),
            SCHEDULED, EnumSet.of(SCHEDULED, CHECKED_IN, CANCELLED, NO_SHOW),
            CHECKED_IN, EnumSet.of(COMPLETED, CANCELLED, NO_SHOW),
            COMPLETED, EnumSet.noneOf(AppointmentStatus.class),
            CANCELLED, EnumSet.noneOf(AppointmentStatus.class),
            NO_SHOW, EnumSet.of(SCHEDULED));

    private final String code;
    private final String label;
    private final String tone;

    AppointmentStatus(String code, String label, String tone) {
        this.code = code;
        this.label = label;
        this.tone = tone;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getLabel() {
        return label;
    }

    /** Semantic badge tone used by the UI (never colour-only: the label ships too). */
    public String getTone() {
        return tone;
    }

    public boolean isTerminal() {
        return EDGES.get(this).isEmpty();
    }

    public boolean canTransitionTo(AppointmentStatus target) {
        return target != null && EDGES.get(this).contains(target);
    }

    public Set<AppointmentStatus> allowedTargets() {
        return EnumSet.copyOf(EDGES.get(this).isEmpty()
                ? EnumSet.noneOf(AppointmentStatus.class) : EDGES.get(this));
    }

    public static AppointmentStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown appointment status"));
    }
}
