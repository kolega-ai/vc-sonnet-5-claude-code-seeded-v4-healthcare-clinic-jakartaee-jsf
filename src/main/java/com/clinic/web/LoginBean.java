package com.clinic.web;

import com.clinic.domain.Role;
import com.clinic.security.AuthenticationService;
import com.clinic.security.CurrentUser;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Sign-in and sign-out.
 *
 * <p>Authentication is delegated to the container's Jakarta Security mechanism.
 * On success the HTTP session identifier is regenerated to defeat session
 * fixation. Failures always produce the same generic message so the form cannot
 * be used to discover which email addresses exist.</p>
 */
@Named("loginBean")
@RequestScoped
public class LoginBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "Enter your email address.")
    @Size(max = 190)
    private String email;

    @NotBlank(message = "Enter your password.")
    @Size(max = 200)
    private String password;

    private boolean failed;

    @Inject
    private SecurityContext securityContext;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private AuthenticationService authenticationService;

    public String login() {
        FacesContext context = FacesContext.getCurrentInstance();
        HttpServletRequest request =
                (HttpServletRequest) context.getExternalContext().getRequest();
        HttpServletResponse response =
                (HttpServletResponse) context.getExternalContext().getResponse();

        // Invalidate any pre-authentication session before establishing a new one.
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }

        AuthenticationStatus status = securityContext.authenticate(request, response,
                AuthenticationParameters.withParams()
                        .credential(new UsernamePasswordCredential(email, password))
                        .newAuthentication(true));

        if (status == AuthenticationStatus.SUCCESS) {
            request.changeSessionId();
            String continueTo = Faces.param("continueTo");
            if (continueTo != null) {
                // Return the user to the page that sent them here.
                try {
                    context.getExternalContext().redirect(continueTo);
                    context.responseComplete();
                    return null;
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return landingPage() + "?faces-redirect=true";
        }
        if (status == AuthenticationStatus.SEND_CONTINUE) {
            context.responseComplete();
            return null;
        }
        failed = true;
        Faces.error("We could not sign you in. Check your email address and password.");
        return null;
    }

    /** Sends each role to the workspace it actually uses. */
    private String landingPage() {
        Role role = currentUser.getRole();
        if (role == null) {
            return "/index.xhtml";
        }
        return switch (role) {
            case PATIENT -> "/patient/dashboard.xhtml";
            case CLINICIAN -> "/clinician/dashboard.xhtml";
            case FRONT_DESK -> "/staff/dashboard.xhtml";
            case CLINIC_ADMIN -> "/admin/dashboard.xhtml";
        };
    }

    public String logout() {
        FacesContext context = FacesContext.getCurrentInstance();
        Long userId = currentUser.getUserId();
        String userEmail = currentUser.getEmail();
        if (userId != null) {
            authenticationService.recordLogout(userId, userEmail);
        }
        HttpServletRequest request =
                (HttpServletRequest) context.getExternalContext().getRequest();
        try {
            request.logout();
        } catch (jakarta.servlet.ServletException e) {
            // The session is invalidated below regardless.
            Faces.warn("You have been signed out.");
        }
        context.getExternalContext().invalidateSession();
        return "/login.xhtml?faces-redirect=true&signedOut=true";
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isFailed() {
        return failed;
    }

    /** Carried across the sign-in post so the user lands back where they were. */
    public String getContinueTarget() {
        return Faces.param("continueTo");
    }

    public boolean isSignedOut() {
        return "true".equals(Faces.param("signedOut"));
    }
}
