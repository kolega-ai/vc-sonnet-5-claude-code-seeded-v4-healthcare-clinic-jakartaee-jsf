package com.clinic.domain;

import java.util.Arrays;

/**
 * Application roles. The code values are also the role names used by the
 * container in {@code web.xml} security constraints and {@code @RolesAllowed}.
 */
public enum Role implements CodedEnum {

    CLINIC_ADMIN("clinic_admin", "Clinic administrator"),
    CLINICIAN("clinician", "Clinician"),
    FRONT_DESK("front_desk", "Front desk"),
    PATIENT("patient", "Patient");

    public static final String CLINIC_ADMIN_ROLE = "clinic_admin";
    public static final String CLINICIAN_ROLE = "clinician";
    public static final String FRONT_DESK_ROLE = "front_desk";
    public static final String PATIENT_ROLE = "patient";

    private final String code;
    private final String label;

    Role(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getLabel() {
        return label;
    }

    public boolean isStaff() {
        return this != PATIENT;
    }

    public static Role fromCode(String code) {
        return Arrays.stream(values())
                .filter(r -> r.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown role code"));
    }
}
