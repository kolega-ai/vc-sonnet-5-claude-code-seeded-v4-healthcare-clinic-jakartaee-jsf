package com.clinic.domain;

/**
 * Marker for enums that are persisted using an explicit lowercase database code
 * rather than the Java constant name. Keeping the code explicit means the
 * database CHECK constraints and the Java model can never drift apart.
 */
public interface CodedEnum {

    String getCode();

    String getLabel();
}
