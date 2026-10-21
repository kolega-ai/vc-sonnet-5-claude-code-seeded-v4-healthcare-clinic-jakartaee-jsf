package com.clinic.util;

import com.clinic.security.BusinessRuleException;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationsTest {

    @Test
    void requiredTextIsTrimmedAndLengthChecked() {
        assertEquals("Ada", Validations.requireText("  Ada  ", "First name", 80));
        assertThrows(BusinessRuleException.class,
                () -> Validations.requireText("   ", "First name", 80));
        assertThrows(BusinessRuleException.class,
                () -> Validations.requireText(null, "First name", 80));
        assertThrows(BusinessRuleException.class,
                () -> Validations.requireText("abcdef", "First name", 3));
    }

    @Test
    void optionalTextCollapsesBlanksToNull() {
        assertNull(Validations.optionalText(null, "Note", 10));
        assertNull(Validations.optionalText("   ", "Note", 10));
        assertEquals("hi", Validations.optionalText(" hi ", "Note", 10));
    }

    @Test
    void emailIsNormalisedAndValidated() {
        assertEquals("ada@example.org", Validations.requireEmail("  Ada@Example.ORG "));
        assertThrows(BusinessRuleException.class, () -> Validations.requireEmail("not-an-email"));
        assertThrows(BusinessRuleException.class, () -> Validations.requireEmail("a@b"));
        assertThrows(BusinessRuleException.class, () -> Validations.requireEmail("@example.org"));
    }

    @Test
    void phoneAcceptsCommonFormatsOnly() {
        assertEquals("+1-206-555-0142", Validations.optionalPhone("+1-206-555-0142"));
        assertNull(Validations.optionalPhone(null));
        assertThrows(BusinessRuleException.class,
                () -> Validations.optionalPhone("call me <script>"));
    }

    @Test
    void mrnFormatIsEnforced() {
        assertEquals("NG-100001", Validations.requireMrn("ng-100001"));
        assertThrows(BusinessRuleException.class, () -> Validations.requireMrn("100001"));
        assertThrows(BusinessRuleException.class, () -> Validations.requireMrn("NGX-1"));
    }

    @Test
    void birthDatesMustBePlausible() {
        LocalDate ok = LocalDate.now().minusYears(30);
        assertEquals(ok, Validations.requireBirthDate(ok));
        assertThrows(BusinessRuleException.class, () -> Validations.requireBirthDate(null));
        assertThrows(BusinessRuleException.class,
                () -> Validations.requireBirthDate(LocalDate.now().plusDays(1)));
        assertThrows(BusinessRuleException.class,
                () -> Validations.requireBirthDate(LocalDate.now().minusYears(140)));
    }

    @Test
    void requireThrowsWithTheGivenMessage() {
        Validations.require(true, "never thrown");
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> Validations.require(false, "Choose a day of the week."));
        assertEquals("Choose a day of the week.", e.getMessage());
    }
}
