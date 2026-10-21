package com.clinic.security;

import jakarta.ejb.ApplicationException;

/** Thrown when a request is well-formed but violates a domain/workflow rule. */
@ApplicationException(rollback = true)
public class BusinessRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BusinessRuleException(String message) {
        super(message);
    }
}
