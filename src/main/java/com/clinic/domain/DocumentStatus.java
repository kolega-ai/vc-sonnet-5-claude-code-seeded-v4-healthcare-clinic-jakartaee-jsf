package com.clinic.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Patient document review state machine.
 *
 * <pre>
 *   submitted -> verified | rejected | archived
 *   verified  -> archived
 *   rejected  -> submitted (patient re-uploads) | archived
 *   archived  -> (terminal)
 * </pre>
 */
public enum DocumentStatus implements CodedEnum {

    SUBMITTED("submitted", "Submitted", "warning"),
    VERIFIED("verified", "Verified", "success"),
    REJECTED("rejected", "Rejected", "danger"),
    ARCHIVED("archived", "Archived", "neutral");

    private static final Map<DocumentStatus, Set<DocumentStatus>> EDGES = Map.of(
            SUBMITTED, EnumSet.of(VERIFIED, REJECTED, ARCHIVED),
            VERIFIED, EnumSet.of(ARCHIVED),
            REJECTED, EnumSet.of(SUBMITTED, ARCHIVED),
            ARCHIVED, EnumSet.noneOf(DocumentStatus.class));

    private final String code;
    private final String label;
    private final String tone;

    DocumentStatus(String code, String label, String tone) {
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

    public boolean canTransitionTo(DocumentStatus target) {
        return target != null && EDGES.get(this).contains(target);
    }

    public Set<DocumentStatus> allowedTargets() {
        return EnumSet.copyOf(EDGES.get(this).isEmpty()
                ? EnumSet.noneOf(DocumentStatus.class) : EDGES.get(this));
    }

    public static DocumentStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown document status"));
    }
}
