package com.clinic.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps a caller-supplied sort key onto a fixed, bounded set of JPQL order-by
 * fragments.
 *
 * <p>JPQL cannot bind an ORDER BY column as a parameter, so the only safe way
 * to offer sortable columns is to resolve the request against a closed
 * whitelist and fall back to a default. Nothing supplied by the caller ever
 * reaches the query text; only a constant chosen from this map does. Every
 * other part of the query still uses bound parameters.</p>
 */
public final class SortWhitelist {

    private final Map<String, String> allowed = new LinkedHashMap<>();
    private final String defaultKey;

    private SortWhitelist(String defaultKey) {
        this.defaultKey = defaultKey;
    }

    public static Builder builder(String defaultKey) {
        return new Builder(defaultKey);
    }

    /**
     * @param requestedKey an untrusted sort key from a query string or form
     * @return a constant JPQL ORDER BY fragment; never caller-controlled text
     */
    public String orderByFragment(String requestedKey) {
        String fragment = requestedKey == null ? null : allowed.get(requestedKey);
        return fragment != null ? fragment : allowed.get(defaultKey);
    }

    public boolean isAllowed(String requestedKey) {
        return requestedKey != null && allowed.containsKey(requestedKey);
    }

    public Set<String> keys() {
        return allowed.keySet();
    }

    public String getDefaultKey() {
        return defaultKey;
    }

    /** Fluent builder for the closed whitelist. */
    public static final class Builder {

        private final SortWhitelist target;

        private Builder(String defaultKey) {
            this.target = new SortWhitelist(defaultKey);
        }

        public Builder allow(String key, String jpqlFragment) {
            target.allowed.put(key, jpqlFragment);
            return this;
        }

        public SortWhitelist build() {
            if (!target.allowed.containsKey(target.defaultKey)) {
                throw new IllegalStateException("Default sort key is not whitelisted");
            }
            return target;
        }
    }
}
