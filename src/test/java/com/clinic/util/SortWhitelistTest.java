package com.clinic.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortWhitelistTest {

    private final SortWhitelist sort = SortWhitelist.builder("name")
            .allow("name", "p.lastName ASC")
            .allow("mrn", "p.mrn ASC")
            .build();

    @Test
    void resolvesAWhitelistedKey() {
        assertEquals("p.mrn ASC", sort.orderByFragment("mrn"));
        assertEquals("p.lastName ASC", sort.orderByFragment("name"));
    }

    @Test
    @DisplayName("anything not whitelisted falls back to the default fragment")
    void rejectsInjectionAttempts() {
        assertEquals("p.lastName ASC", sort.orderByFragment(null));
        assertEquals("p.lastName ASC", sort.orderByFragment(""));
        assertEquals("p.lastName ASC", sort.orderByFragment("p.mrn; DROP TABLE patient"));
        assertEquals("p.lastName ASC",
                sort.orderByFragment("name) OR 1=1 --"));
        assertFalse(sort.isAllowed("p.mrn; DROP TABLE patient"));
        assertTrue(sort.isAllowed("mrn"));
    }

    @Test
    void aDefaultKeyMustItselfBeWhitelisted() {
        assertThrows(IllegalStateException.class,
                () -> SortWhitelist.builder("missing").allow("name", "p.lastName").build());
    }
}
