package com.clinic.security;

import jakarta.ejb.ApplicationException;

/**
 * Thrown when an authenticated caller holds the right role but is not entitled
 * to the specific record being acted upon. Declarative role checks are never
 * sufficient on their own; this exception is the result of the ownership /
 * tenancy check that runs inside the business tier.
 */
@ApplicationException(rollback = true)
public class AccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String resource;

    public AccessDeniedException(String message) {
        this(message, null);
    }

    public AccessDeniedException(String message, String resource) {
        super(message);
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }
}
