package com.clinic.domain;

import java.util.Arrays;

/** Upload categories. Patients may only submit the patient-submittable ones. */
public enum DocumentCategory implements CodedEnum {

    PRE_VISIT_FORM("pre_visit_form", "Pre-visit form", true),
    INSURANCE_CARD("insurance_card", "Insurance card", true),
    LAB_RESULT("lab_result", "Lab result", false),
    REFERRAL("referral", "Referral", false),
    CLINICAL_NOTE("clinical_note", "Clinical note", false),
    CONSENT("consent", "Consent form", true);

    private final String code;
    private final String label;
    private final boolean patientSubmittable;

    DocumentCategory(String code, String label, boolean patientSubmittable) {
        this.code = code;
        this.label = label;
        this.patientSubmittable = patientSubmittable;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getLabel() {
        return label;
    }

    public boolean isPatientSubmittable() {
        return patientSubmittable;
    }

    public static DocumentCategory fromCode(String code) {
        return Arrays.stream(values())
                .filter(c -> c.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown document category"));
    }
}
