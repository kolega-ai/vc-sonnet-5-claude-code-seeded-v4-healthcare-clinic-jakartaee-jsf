package com.clinic.web;

import com.clinic.security.CurrentUser;
import com.clinic.service.UserAdminService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;

/** Self-service profile and password change for every signed-in role. */
@Named("profile")
@ViewScoped
public class ProfileBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private UserAdminService userAdminService;

    private String fullName;
    private String phone;

    private String currentPassword;
    private String newPassword;
    private String confirmPassword;

    @PostConstruct
    public void load() {
        if (currentUser.getUser() != null) {
            fullName = currentUser.getUser().getFullName();
            phone = currentUser.getUser().getPhone();
        }
    }

    public String saveProfile() {
        try {
            userAdminService.updateOwnProfile(fullName, phone);
            Faces.success("Your profile has been updated.");
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String changePassword() {
        try {
            userAdminService.changeOwnPassword(currentPassword, newPassword, confirmPassword);
            currentPassword = null;
            newPassword = null;
            confirmPassword = null;
            Faces.success("Your password has been changed.");
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public CurrentUser getUser() {
        return currentUser;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
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
}
