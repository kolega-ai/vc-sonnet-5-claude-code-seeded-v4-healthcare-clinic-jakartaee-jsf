package com.clinic.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Secure message thread lifecycle.
 *
 * <pre>
 *   open              -> awaiting_patient | resolved | closed
 *   awaiting_patient  -> open | resolved | closed
 *   resolved          -> open | closed
 *   closed            -> (terminal)
 * </pre>
 */
public enum ThreadStatus implements CodedEnum {

    OPEN("open", "Open", "info"),
    AWAITING_PATIENT("awaiting_patient", "Awaiting patient", "warning"),
    RESOLVED("resolved", "Resolved", "success"),
    CLOSED("closed", "Closed", "neutral");

    private static final Map<ThreadStatus, Set<ThreadStatus>> EDGES = Map.of(
            OPEN, EnumSet.of(AWAITING_PATIENT, RESOLVED, CLOSED),
            AWAITING_PATIENT, EnumSet.of(OPEN, RESOLVED, CLOSED),
            RESOLVED, EnumSet.of(OPEN, CLOSED),
            CLOSED, EnumSet.noneOf(ThreadStatus.class));

    private final String code;
    private final String label;
    private final String tone;

    ThreadStatus(String code, String label, String tone) {
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

    public String getTone() {
        return tone;
    }

    public boolean canTransitionTo(ThreadStatus target) {
        return target != null && EDGES.get(this).contains(target);
    }

    public static ThreadStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown thread status"));
    }
}
