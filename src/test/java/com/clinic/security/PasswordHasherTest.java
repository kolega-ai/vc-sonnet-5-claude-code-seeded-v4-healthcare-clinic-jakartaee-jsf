package com.clinic.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashesAreSaltedSoTwoHashesOfOnePasswordDiffer() {
        String first = hasher.hash("Passw0rd!2026");
        String second = hasher.hash("Passw0rd!2026");
        assertNotEquals(first, second);
        assertTrue(first.startsWith("pbkdf2_sha256$210000$"));
    }

    @Test
    void verifyAcceptsTheRightPasswordAndRejectsEverythingElse() {
        String encoded = hasher.hash("Passw0rd!2026");
        assertTrue(hasher.verify("Passw0rd!2026", encoded));
        assertFalse(hasher.verify("passw0rd!2026", encoded));
        assertFalse(hasher.verify("", encoded));
        assertFalse(hasher.verify(null, encoded));
        assertFalse(hasher.verify("Passw0rd!2026", null));
        assertFalse(hasher.verify("Passw0rd!2026", "not-an-encoded-hash"));
        assertFalse(hasher.verify("Passw0rd!2026", "md5$1$aa$bb"));
    }

    @Test
    void policyRejectsWeakPasswords() {
        assertNull(hasher.policyViolation("Passw0rd!2026"));
        assertNull(hasher.policyViolation("correct horse Battery 9!"));

        assertTrue(hasher.policyViolation("short1!A").contains("12 characters"));
        assertTrue(hasher.policyViolation("alllowercase123!").contains("upper case"));
        assertTrue(hasher.policyViolation("NoSymbolsHere123").contains("symbol"));
        assertTrue(hasher.policyViolation(null).contains("12 characters"));
    }

    @Test
    void sha256IsStableAndHexEncoded() {
        String digest = hasher.sha256Hex("clinic");
        assertEquals(64, digest.length());
        assertEquals(digest, hasher.sha256Hex("clinic"));
        assertNotEquals(digest, hasher.sha256Hex("clinic "));
        assertTrue(digest.matches("[0-9a-f]{64}"));
    }

    @Test
    void randomTokensAreUrlSafeAndUnique() {
        String a = hasher.randomToken(32);
        String b = hasher.randomToken(32);
        assertNotEquals(a, b);
        assertTrue(a.matches("[A-Za-z0-9_-]+"));
        assertTrue(a.length() >= 40);
    }
}
