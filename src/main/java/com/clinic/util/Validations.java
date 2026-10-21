package com.clinic.util;

import com.clinic.security.BusinessRuleException;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Small server-side validation helpers shared by the service tier. */
public final class Validations {

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,190}\\.[A-Za-z]{2,24}$");
    private static final Pattern PHONE =
            Pattern.compile("^[+()0-9\\-.\\s]{7,40}$");
    private static final Pattern MRN =
            Pattern.compile("^[A-Z]{2}-[0-9]{6}$");

    private Validations() {
    }

    public static String requireText(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessRuleException(field + " is required.");
        }
        if (trimmed.length() > max) {
            throw new BusinessRuleException(field + " must be " + max + " characters or fewer.");
        }
        return trimmed;
    }

    public static String optionalText(String value, String field, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new BusinessRuleException(field + " must be " + max + " characters or fewer.");
        }
        return trimmed;
    }

    public static String requireEmail(String value) {
        String trimmed = requireText(value, "Email address", 190).toLowerCase();
        if (!EMAIL.matcher(trimmed).matches()) {
            throw new BusinessRuleException("Enter a valid email address.");
        }
        return trimmed;
    }

    public static String optionalPhone(String value) {
        String trimmed = optionalText(value, "Phone number", 40);
        if (trimmed != null && !PHONE.matcher(trimmed).matches()) {
            throw new BusinessRuleException("Enter a valid phone number.");
        }
        return trimmed;
    }

    public static String requireMrn(String value) {
        String trimmed = requireText(value, "Medical record number", 24).toUpperCase();
        if (!MRN.matcher(trimmed).matches()) {
            throw new BusinessRuleException("Medical record number must look like AB-123456.");
        }
        return trimmed;
    }

    public static LocalDate requireBirthDate(LocalDate value) {
        if (value == null) {
            throw new BusinessRuleException("Date of birth is required.");
        }
        if (value.isAfter(LocalDate.now())) {
            throw new BusinessRuleException("Date of birth cannot be in the future.");
        }
        if (value.isBefore(LocalDate.now().minusYears(130))) {
            throw new BusinessRuleException("Date of birth is not plausible.");
        }
        return value;
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new BusinessRuleException(message);
        }
    }
}
