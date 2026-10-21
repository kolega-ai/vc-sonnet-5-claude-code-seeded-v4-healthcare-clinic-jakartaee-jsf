package com.clinic.security;

import com.clinic.domain.AppUser;
import com.clinic.domain.Patient;
import com.clinic.domain.Provider;
import com.clinic.domain.Role;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.security.enterprise.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;

import java.io.Serializable;
import java.security.Principal;

/**
 * The acting user for the current request.
 *
 * <p>The identity is always re-resolved from the container's authenticated
 * principal and then re-loaded from the database, so role, patient linkage and
 * provider linkage are authoritative on every request. Nothing about the caller
 * is ever taken from a header, query parameter, form field or hidden input.</p>
 */
@Named("currentUser")
@RequestScoped
public class CurrentUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private SecurityContext securityContext;

    @Inject
    private HttpServletRequest request;

    private boolean resolved;
    private AppUser user;
    private Patient patient;
    private Provider provider;

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        Principal principal = securityContext.getCallerPrincipal();
        if (principal == null || principal.getName() == null) {
            return;
        }
        try {
            user = em.createNamedQuery("AppUser.byEmail", AppUser.class)
                    .setParameter("email", principal.getName())
                    .getSingleResult();
        } catch (NoResultException e) {
            return;
        }
        if (!user.isActive()) {
            user = null;
            return;
        }
        if (user.getRole() == Role.PATIENT) {
            patient = em.createNamedQuery("Patient.byUser", Patient.class)
                    .setParameter("userId", user.getId())
                    .getResultStream().findFirst().orElse(null);
        } else if (user.getRole() == Role.CLINICIAN) {
            provider = em.createNamedQuery("Provider.byUser", Provider.class)
                    .setParameter("userId", user.getId())
                    .getResultStream().findFirst().orElse(null);
        }
    }

    public boolean isAuthenticated() {
        resolve();
        return user != null;
    }

    public AppUser getUser() {
        resolve();
        return user;
    }

    public AppUser requireUser() {
        resolve();
        if (user == null) {
            throw new AccessDeniedException("Not authenticated.");
        }
        return user;
    }

    public Long getUserId() {
        resolve();
        return user == null ? null : user.getId();
    }

    public String getEmail() {
        resolve();
        return user == null ? null : user.getEmail();
    }

    public String getDisplayName() {
        resolve();
        return user == null ? "" : user.getFullName();
    }

    public String getInitials() {
        resolve();
        return user == null ? "?" : user.getInitials();
    }

    public Role getRole() {
        resolve();
        return user == null ? null : user.getRole();
    }

    public String getRoleLabel() {
        Role role = getRole();
        return role == null ? "" : role.getLabel();
    }

    public boolean isPatient() {
        return getRole() == Role.PATIENT;
    }

    public boolean isClinician() {
        return getRole() == Role.CLINICIAN;
    }

    public boolean isFrontDesk() {
        return getRole() == Role.FRONT_DESK;
    }

    public boolean isClinicAdmin() {
        return getRole() == Role.CLINIC_ADMIN;
    }

    public boolean isStaff() {
        Role role = getRole();
        return role != null && role.isStaff();
    }

    /** The patient record linked to this account, or {@code null} for staff. */
    public Patient getPatientRecord() {
        resolve();
        return patient;
    }

    public Long getPatientId() {
        resolve();
        return patient == null ? null : patient.getId();
    }

    /** The provider record linked to this account, or {@code null} otherwise. */
    public Provider getProvider() {
        resolve();
        return provider;
    }

    public Long getProviderId() {
        resolve();
        return provider == null ? null : provider.getId();
    }

    public String getClientIp() {
        if (request == null) {
            return null;
        }
        // Deliberately the transport-level peer address: forwarded headers are
        // attacker-controlled and are not trusted for audit attribution.
        String remote = request.getRemoteAddr();
        return remote == null || remote.length() > 64 ? null : remote;
    }
}
