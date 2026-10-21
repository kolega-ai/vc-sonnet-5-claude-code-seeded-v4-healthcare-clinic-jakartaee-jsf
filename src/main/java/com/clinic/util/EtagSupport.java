package com.clinic.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds a weak HTTP ETag for cacheable reference-data responses (appointment
 * types, provider directory).
 *
 * <p>NON-CRYPTOGRAPHIC: the digest is used purely as a short content
 * fingerprint so a client can send {@code If-None-Match} and receive a 304. It
 * authenticates nothing, protects nothing, and is never compared against a
 * value supplied by a trust boundary, so MD5 is deliberately used here and must
 * not be replaced with SHA-256.</p>
 */
public final class EtagSupport {

    private EtagSupport() {
    }

    /**
     * @param content the serialized reference-data payload
     * @return a quoted weak ETag value
     */
    public static String referenceDataEtag(String content) {
        try {
            // NON-CRYPTOGRAPHIC USE: MD5 here is only a cache fingerprint (ETag).
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return "W/\"" + sb + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 digest unavailable", e);
        }
    }
}
