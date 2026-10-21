package com.clinic.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Prescription refill request state machine.
 *
 * <pre>
 *   requested  -> approved | denied | needs_info | cancelled
 *   needs_info -> requested | approved | denied | cancelled
 *   approved   -> (terminal)
 *   denied     -> (terminal)
 *   cancelled  -> (terminal)
 * </pre>
 */
public enum RefillStatus implements CodedEnum {

    REQUESTED("requested", "Requested", "warning"),
    NEEDS_INFO("needs_info", "Needs information", "info"),
    APPROVED("approved", "Approved", "success"),
    DENIED("denied", "Denied", "danger"),
    CANCELLED("cancelled", "Cancelled", "neutral");

    private static final Map<RefillStatus, Set<RefillStatus>> EDGES = Map.of(
            REQUESTED, EnumSet.of(APPROVED, DENIED, NEEDS_INFO, CANCELLED),
            NEEDS_INFO, EnumSet.of(REQUESTED, APPROVED, DENIED, CANCELLED),
            APPROVED, EnumSet.noneOf(RefillStatus.class),
            DENIED, EnumSet.noneOf(RefillStatus.class),
            CANCELLED, EnumSet.noneOf(RefillStatus.class));

    private final String code;
    private final String label;
    private final String tone;

    RefillStatus(String code, String label, String tone) {
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

    public boolean isOpen() {
        return this == REQUESTED || this == NEEDS_INFO;
    }

    public boolean canTransitionTo(RefillStatus target) {
        return target != null && EDGES.get(this).contains(target);
    }

    public Set<RefillStatus> allowedTargets() {
        return EnumSet.copyOf(EDGES.get(this).isEmpty()
                ? EnumSet.noneOf(RefillStatus.class) : EDGES.get(this));
    }

    public static RefillStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown refill status"));
    }
}
