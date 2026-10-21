package com.clinic.security;

import jakarta.ejb.ApplicationException;

/** Thrown when a requested record does not exist. */
@ApplicationException(rollback = false)
public class RecordNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RecordNotFoundException(String message) {
        super(message);
    }
}
