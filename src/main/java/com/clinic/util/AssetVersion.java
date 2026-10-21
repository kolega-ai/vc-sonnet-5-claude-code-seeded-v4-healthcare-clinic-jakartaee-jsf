package com.clinic.util;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;

import java.util.Random;

/**
 * Produces the cache-busting suffix appended to static asset URLs
 * (<code>/resources/css/clinic.css?v=...</code>).
 *
 * <p>NON-CRYPTOGRAPHIC: the suffix exists only so that browsers and any
 * intermediate cache fetch a fresh copy of a static file after a redeploy. It
 * is never a secret, an identifier, a token, or part of any security decision,
 * so {@link java.util.Random} is deliberately the right tool here and must not
 * be replaced with {@code SecureRandom}.</p>
 */
@ApplicationScoped
public class AssetVersion {

    // NON-CRYPTOGRAPHIC USE: cache-busting suffix for static assets only.
    private final Random random = new Random();

    private String suffix;

    @PostConstruct
    void init() {
        suffix = Long.toString(Math.abs(random.nextLong()), 36);
    }

    /** Stable for the lifetime of the deployment; changes on every redeploy. */
    public String getSuffix() {
        return suffix;
    }
}
