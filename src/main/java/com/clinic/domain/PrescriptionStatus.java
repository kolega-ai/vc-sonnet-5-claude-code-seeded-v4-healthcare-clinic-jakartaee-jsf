package com.clinic.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Prescription lifecycle.
 *
 * <pre>
 *   active       -> discontinued | expired
 *   discontinued -> (terminal)
 *   expired      -> active (re-issued in place after an approved refill)
 * </pre>
 */
public enum PrescriptionStatus implements CodedEnum {

    ACTIVE("active", "Active", "success"),
    DISCONTINUED("discontinued", "Discontinued", "danger"),
    EXPIRED("expired", "Expired", "neutral");

    private static final Map<PrescriptionStatus, Set<PrescriptionStatus>> EDGES = Map.of(
            ACTIVE, EnumSet.of(DISCONTINUED, EXPIRED),
            DISCONTINUED, EnumSet.noneOf(PrescriptionStatus.class),
            EXPIRED, EnumSet.of(ACTIVE));

    private final String code;
    private final String label;
    private final String tone;

    PrescriptionStatus(String code, String label, String tone) {
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

    public boolean canTransitionTo(PrescriptionStatus target) {
        return target != null && EDGES.get(this).contains(target);
    }

    public static PrescriptionStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown prescription status"));
    }
}
