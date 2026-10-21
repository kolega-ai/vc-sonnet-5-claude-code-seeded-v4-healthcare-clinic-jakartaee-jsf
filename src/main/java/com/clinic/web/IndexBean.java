package com.clinic.web;

import com.clinic.security.CurrentUser;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;

/** Landing page: sends an already-signed-in visitor to their own workspace. */
@Named("indexBean")
@RequestScoped
public class IndexBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    public void route() {
        if (!currentUser.isAuthenticated()) {
            return;
        }
        Faces.redirect(switch (currentUser.getRole()) {
            case PATIENT -> "/patient/dashboard.xhtml";
            case CLINICIAN -> "/clinician/dashboard.xhtml";
            case FRONT_DESK -> "/staff/dashboard.xhtml";
            case CLINIC_ADMIN -> "/admin/dashboard.xhtml";
        });
    }
}
