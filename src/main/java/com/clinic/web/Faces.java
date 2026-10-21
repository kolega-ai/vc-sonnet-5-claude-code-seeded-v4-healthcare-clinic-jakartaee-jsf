package com.clinic.web;

import com.clinic.security.AccessDeniedException;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.RecordNotFoundException;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small helpers shared by the backing beans: user feedback and a single place
 * that decides what an end user is told when an operation fails.
 */
public final class Faces {

    private static final Logger LOG = Logger.getLogger(Faces.class.getName());

    private Faces() {
    }

    public static void success(String summary) {
        add(FacesMessage.SEVERITY_INFO, summary);
    }

    public static void warn(String summary) {
        add(FacesMessage.SEVERITY_WARN, summary);
    }

    public static void error(String summary) {
        add(FacesMessage.SEVERITY_ERROR, summary);
    }

    private static void add(FacesMessage.Severity severity, String summary) {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context != null) {
            context.addMessage(null, new FacesMessage(severity, summary, null));
            context.getExternalContext().getFlash().setKeepMessages(true);
        }
    }

    /**
     * Converts an exception into a message the user can act on.
     *
     * <p>Only messages the application authored are shown. Anything else is
     * logged with its stack trace server-side and replaced with a generic
     * sentence, so no internal detail is disclosed in the UI.</p>
     */
    public static void handle(RuntimeException exception) {
        Throwable cause = unwrap(exception);
        if (cause instanceof BusinessRuleException e) {
            error(e.getMessage());
        } else if (cause instanceof AccessDeniedException e) {
            LOG.log(Level.FINE, "Authorization failure surfaced to the UI");
            error(e.getMessage());
        } else if (cause instanceof RecordNotFoundException e) {
            warn(e.getMessage());
        } else if (cause instanceof UncheckedIOException) {
            LOG.log(Level.SEVERE, "Storage failure", cause);
            error("A stored file could not be read. Please contact support.");
        } else {
            LOG.log(Level.SEVERE, "Unhandled failure in the web tier", cause);
            error("Something went wrong. Please try again, or contact support if it continues.");
        }
    }

    private static Throwable unwrap(Throwable exception) {
        Throwable current = exception;
        int depth = 0;
        while (current.getCause() != null && depth++ < 8
                && (current instanceof jakarta.ejb.EJBException
                || current instanceof jakarta.ejb.EJBTransactionRolledbackException)) {
            current = current.getCause();
        }
        return current;
    }

    /** Reads a request parameter, or {@code null} when absent or blank. */
    public static String param(String name) {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return null;
        }
        String value = context.getExternalContext().getRequestParameterMap().get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Reads a numeric request parameter, or {@code null} when absent/invalid. */
    public static Long longParam(String name) {
        String raw = param(name);
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void redirect(String path) {
        FacesContext context = FacesContext.getCurrentInstance();
        try {
            context.getExternalContext()
                    .redirect(context.getExternalContext().getRequestContextPath() + path);
            context.responseComplete();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String contextPath() {
        FacesContext context = FacesContext.getCurrentInstance();
        return context == null ? "" : context.getExternalContext().getRequestContextPath();
    }
}
