package com.clinic.web;

import com.clinic.service.PasswordResetService;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;

import java.io.Serializable;

/**
 * The "forgot password" and "choose a new password" screens.
 *
 * <p>The request step always reports the same outcome, whether or not the
 * address is registered. The completion step consumes a single-use, time-bound
 * token.</p>
 */
@Named("passwordResetBean")
@RequestScoped
public class PasswordResetBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private PasswordResetService passwordResetService;

    private String email;
    private String token;
    private String newPassword;
    private String confirmPassword;
    private boolean requestSubmitted;
    private boolean completed;
    private Boolean tokenUsable;

    public void loadToken() {
        if (token == null) {
            token = Faces.param("token");
        }
        tokenUsable = token != null && passwordResetService.isTokenUsable(token);
        if (Boolean.FALSE.equals(tokenUsable)) {
            Faces.warn("This reset link has expired or has already been used. "
                    + "Please request a new one.");
        }
    }

    public String requestReset() {
        try {
            passwordResetService.requestReset(email, baseUrl());
        } catch (RuntimeException e) {
            Faces.handle(e);
            return null;
        }
        requestSubmitted = true;
        Faces.success("If that address matches an account, a reset link is on its way. "
                + "The link is valid for a short time and can be used once.");
        return null;
    }

    public String completeReset() {
        try {
            passwordResetService.completeReset(token, newPassword, confirmPassword);
        } catch (RuntimeException e) {
            Faces.handle(e);
            return null;
        }
        completed = true;
        Faces.success("Your password has been changed. You can now sign in.");
        return "/login.xhtml?faces-redirect=true";
    }

    private static String baseUrl() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return "";
        }
        HttpServletRequest request =
                (HttpServletRequest) context.getExternalContext().getRequest();
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName()
                + (defaultPort ? "" : ":" + port)
                + request.getContextPath();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    public boolean isRequestSubmitted() {
        return requestSubmitted;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isTokenUsable() {
        return Boolean.TRUE.equals(tokenUsable);
    }
}
