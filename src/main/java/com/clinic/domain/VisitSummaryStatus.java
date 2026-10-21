package com.clinic.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Visit summary state machine.
 *
 * <pre>
 *   draft    -> released
 *   released -> amended
 *   amended  -> released
 * </pre>
 *
 * Only {@code released} and {@code amended} summaries are ever visible to a
 * patient; a {@code draft} is clinician-only.
 */
public enum VisitSummaryStatus implements CodedEnum {

    DRAFT("draft", "Draft", "warning"),
    RELEASED("released", "Released", "success"),
    AMENDED("amended", "Amended", "info");

    private static final Map<VisitSummaryStatus, Set<VisitSummaryStatus>> EDGES = Map.of(
            DRAFT, EnumSet.of(RELEASED),
            RELEASED, EnumSet.of(AMENDED),
            AMENDED, EnumSet.of(RELEASED));

    private final String code;
    private final String label;
    private final String tone;

    VisitSummaryStatus(String code, String label, String tone) {
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

    /** True when the summary has been released to the patient at least once. */
    public boolean isPatientVisible() {
        return this == RELEASED || this == AMENDED;
    }

    public boolean canTransitionTo(VisitSummaryStatus target) {
        return target != null && EDGES.get(this).contains(target);
    }

    public Set<VisitSummaryStatus> allowedTargets() {
        return EnumSet.copyOf(EDGES.get(this));
    }

    public static VisitSummaryStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown visit summary status"));
    }
}
