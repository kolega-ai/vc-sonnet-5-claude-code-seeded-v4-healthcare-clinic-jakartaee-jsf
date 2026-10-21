package com.clinic.rest;

import com.clinic.security.AccessDeniedException;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.RecordNotFoundException;
import com.clinic.util.JsonWriter;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns application exceptions into JSON problem responses.
 *
 * <p>Only messages the application itself authored are returned; anything
 * unexpected is logged server-side and answered with a generic message so no
 * stack trace, SQL text or internal identifier reaches the caller.</p>
 */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class.getName());

    @Override
    public Response toResponse(Throwable exception) {
        Throwable cause = unwrap(exception);

        if (cause instanceof AccessDeniedException e) {
            LOG.log(Level.FINE, "API authorization failure");
            return problem(Response.Status.FORBIDDEN, e.getMessage());
        }
        if (cause instanceof RecordNotFoundException e) {
            return problem(Response.Status.NOT_FOUND, e.getMessage());
        }
        if (cause instanceof BusinessRuleException e) {
            return problem(Response.Status.BAD_REQUEST, e.getMessage());
        }
        if (cause instanceof WebApplicationException e) {
            int status = e.getResponse().getStatus();
            return problem(Response.Status.fromStatusCode(status), messageFor(status));
        }
        LOG.log(Level.SEVERE, "Unhandled API failure", cause);
        return problem(Response.Status.INTERNAL_SERVER_ERROR,
                "The request could not be completed.");
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

    private static String messageFor(int status) {
        return switch (status) {
            case 401 -> "Authentication is required.";
            case 403 -> "You are not permitted to perform this action.";
            case 404 -> "Not found.";
            case 405 -> "That method is not supported here.";
            default -> "The request could not be completed.";
        };
    }

    private static Response problem(Response.Status status, String message) {
        Response.Status resolved = status == null ? Response.Status.INTERNAL_SERVER_ERROR : status;
        String body = "{\"status\":" + resolved.getStatusCode()
                + ",\"error\":" + JsonWriter.string(message) + "}";
        return Response.status(resolved)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
