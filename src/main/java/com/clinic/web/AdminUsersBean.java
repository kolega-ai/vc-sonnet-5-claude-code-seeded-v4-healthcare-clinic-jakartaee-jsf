package com.clinic.web;

import com.clinic.domain.AppUser;
import com.clinic.domain.Patient;
import com.clinic.domain.Provider;
import com.clinic.domain.Role;
import com.clinic.service.PatientSearchCriteria;
import com.clinic.service.PatientService;
import com.clinic.service.ProviderService;
import com.clinic.service.UserAdminService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/** Staff and patient account administration: invite, role change, enable/disable. */
@Named("adminUsers")
@ViewScoped
public class AdminUsersBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private UserAdminService userAdminService;

    @EJB
    private ProviderService providerService;

    @EJB
    private PatientService patientService;

    private List<AppUser> users = List.of();
    private List<Provider> providers = List.of();
    private List<Patient> patients = List.of();

    private String inviteEmail;
    private String inviteName;
    private String inviteRole = Role.FRONT_DESK.getCode();
    private String invitePhone;
    private Long inviteProviderId;
    private Long invitePatientId;

    private String roleFilter;
    private String textFilter;

    @PostConstruct
    public void load() {
        try {
            users = userAdminService.listUsers();
            providers = providerService.listAllProviders();
            PatientSearchCriteria criteria = new PatientSearchCriteria();
            criteria.setMaxResults(300);
            patients = patientService.search(criteria);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public List<AppUser> getFilteredUsers() {
        return users.stream()
                .filter(u -> roleFilter == null || roleFilter.isBlank()
                        || u.getRole().getCode().equals(roleFilter))
                .filter(u -> textFilter == null || textFilter.isBlank()
                        || u.getEmail().toLowerCase().contains(textFilter.toLowerCase())
                        || u.getFullName().toLowerCase().contains(textFilter.toLowerCase()))
                .toList();
    }

    public String invite() {
        try {
            Role role = Role.fromCode(inviteRole);
            userAdminService.inviteUser(inviteEmail, inviteName, role, invitePhone,
                    role == Role.CLINICIAN ? inviteProviderId : null,
                    role == Role.PATIENT ? invitePatientId : null);
            Faces.success("Invitation created. The activation link is in the local outbox.");
            inviteEmail = null;
            inviteName = null;
            invitePhone = null;
            inviteProviderId = null;
            invitePatientId = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String changeRole(AppUser user, String roleCode) {
        try {
            userAdminService.changeRole(user.getId(), Role.fromCode(roleCode));
            Faces.success("Role updated.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String toggleEnabled(AppUser user) {
        try {
            userAdminService.setEnabled(user.getId(), !user.isActive());
            Faces.success(user.isActive() ? "Account disabled." : "Account enabled.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String clearHold(AppUser user) {
        try {
            userAdminService.clearAccountHold(user.getId());
            Faces.success("Account hold lifted.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public Role[] getRoles() {
        return Role.values();
    }

    // ------------------------------------------------------------ accessors

    public List<AppUser> getUsers() {
        return users;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public List<Patient> getPatients() {
        return patients;
    }

    public String getInviteEmail() {
        return inviteEmail;
    }

    public void setInviteEmail(String inviteEmail) {
        this.inviteEmail = inviteEmail;
    }

    public String getInviteName() {
        return inviteName;
    }

    public void setInviteName(String inviteName) {
        this.inviteName = inviteName;
    }

    public String getInviteRole() {
        return inviteRole;
    }

    public void setInviteRole(String inviteRole) {
        this.inviteRole = inviteRole;
    }

    public String getInvitePhone() {
        return invitePhone;
    }

    public void setInvitePhone(String invitePhone) {
        this.invitePhone = invitePhone;
    }

    public Long getInviteProviderId() {
        return inviteProviderId;
    }

    public void setInviteProviderId(Long inviteProviderId) {
        this.inviteProviderId = inviteProviderId;
    }

    public Long getInvitePatientId() {
        return invitePatientId;
    }

    public void setInvitePatientId(Long invitePatientId) {
        this.invitePatientId = invitePatientId;
    }

    public String getRoleFilter() {
        return roleFilter;
    }

    public void setRoleFilter(String roleFilter) {
        this.roleFilter = roleFilter;
    }

    public String getTextFilter() {
        return textFilter;
    }

    public void setTextFilter(String textFilter) {
        this.textFilter = textFilter;
    }
}
