package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.AppUser;
import com.clinic.domain.Patient;
import com.clinic.domain.Provider;
import com.clinic.domain.Role;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.CurrentUser;
import com.clinic.security.PasswordHasher;
import com.clinic.security.RecordNotFoundException;
import com.clinic.util.Validations;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Staff and patient account administration, plus self-service profile updates.
 *
 * <p>There is deliberately no impersonation feature: support is performed by
 * reading the patient chart with an audited chart-view, never by assuming the
 * patient's identity.</p>
 */
@Stateless
public class UserAdminService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    @Inject
    private PasswordHasher passwordHasher;

    @Inject
    private IntegrationService integrationService;

    // ------------------------------------------------------------ user admin

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public List<AppUser> listUsers() {
        accessGuard.requireClinicAdmin();
        return em.createNamedQuery("AppUser.all", AppUser.class).getResultList();
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public AppUser requireUser(Long userId) {
        accessGuard.requireClinicAdmin();
        AppUser user = em.find(AppUser.class, userId);
        if (user == null) {
            throw new RecordNotFoundException("User not found.");
        }
        return user;
    }

    /**
     * Invite-based onboarding: the account is created with an unusable random
     * password and a single-use reset link is written to the local outbox.
     */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public AppUser inviteUser(String email, String fullName, Role role, String phone,
                              Long linkProviderId, Long linkPatientId) {
        accessGuard.requireClinicAdmin();
        String normalised = Validations.requireEmail(email);
        if (findByEmail(normalised) != null) {
            throw new BusinessRuleException("An account with that email address already exists.");
        }
        if (role == null) {
            throw new BusinessRuleException("Choose a role.");
        }

        AppUser user = new AppUser();
        user.setEmail(normalised);
        user.setFullName(Validations.requireText(fullName, "Full name", 160));
        user.setRole(role);
        user.setPhone(Validations.optionalPhone(phone));
        user.setActive(true);
        // Unusable until the invitee completes the reset flow.
        user.setPasswordHash(passwordHasher.hash(passwordHasher.randomToken(32)));
        em.persist(user);
        em.flush();

        if (role == Role.CLINICIAN && linkProviderId != null) {
            Provider provider = em.find(Provider.class, linkProviderId);
            if (provider == null) {
                throw new RecordNotFoundException("Provider not found.");
            }
            if (provider.getUser() != null) {
                throw new BusinessRuleException("That provider already has a linked account.");
            }
            provider.setUser(user);
        }
        if (role == Role.PATIENT && linkPatientId != null) {
            Patient patient = em.find(Patient.class, linkPatientId);
            if (patient == null) {
                throw new RecordNotFoundException("Patient not found.");
            }
            if (patient.getUser() != null) {
                throw new BusinessRuleException("That patient already has a portal account.");
            }
            patient.setUser(user);
        }

        auditService.record(AuditEntry.of(AuditAction.USER_CREATED, "app_user")
                .entity(user.getId())
                .after("email=" + normalised + "; role=" + role.getCode())
                .basis("clinic_admin:administrative_scope"));

        integrationService.queueEmail(normalised, "Your Clinic Patient Portal invitation",
                "An account has been created for you. Use the 'Forgot password' link on the "
                        + "sign-in page to choose a password and activate your account.");
        return user;
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public void changeRole(Long userId, Role newRole) {
        accessGuard.requireClinicAdmin();
        AppUser user = requireUser(userId);
        if (newRole == null) {
            throw new BusinessRuleException("Choose a role.");
        }
        if (user.getId().equals(currentUser.getUserId())) {
            throw new BusinessRuleException("You cannot change your own role.");
        }
        if (user.getRole() == Role.CLINIC_ADMIN && newRole != Role.CLINIC_ADMIN
                && countActiveAdmins() <= 1) {
            throw new BusinessRuleException("At least one active clinic administrator is required.");
        }
        Role before = user.getRole();
        user.setRole(newRole);
        user.setUpdatedAt(LocalDateTime.now());
        auditService.record(AuditEntry.of(AuditAction.USER_ROLE_CHANGED, "app_user")
                .entity(user.getId())
                .before("role=" + before.getCode())
                .after("role=" + newRole.getCode())
                .basis("clinic_admin:administrative_scope"));
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public void setEnabled(Long userId, boolean enabled) {
        accessGuard.requireClinicAdmin();
        AppUser user = requireUser(userId);
        if (user.getId().equals(currentUser.getUserId())) {
            throw new BusinessRuleException("You cannot disable your own account.");
        }
        if (!enabled && user.getRole() == Role.CLINIC_ADMIN && countActiveAdmins() <= 1) {
            throw new BusinessRuleException("At least one active clinic administrator is required.");
        }
        boolean before = user.isActive();
        user.setActive(enabled);
        user.setUpdatedAt(LocalDateTime.now());
        auditService.record(AuditEntry.of(
                        enabled ? AuditAction.USER_ENABLED : AuditAction.USER_DISABLED, "app_user")
                .entity(user.getId())
                .before("active=" + before)
                .after("active=" + enabled)
                .basis("clinic_admin:administrative_scope"));
    }

    /**
     * Lifts the temporary hold placed on an account after repeated failed
     * sign-ins so the account holder can try again immediately.
     */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public void clearAccountHold(Long userId) {
        accessGuard.requireClinicAdmin();
        AppUser user = em.find(AppUser.class, userId);
        if (user == null) {
            throw new RecordNotFoundException("User not found.");
        }
        user.setLockedUntil(null);
        user.setFailedLogins(0);
        em.merge(user);
    }

    private long countActiveAdmins() {
        return em.createQuery(
                        "SELECT COUNT(u) FROM AppUser u WHERE u.role = :role AND u.active = true",
                        Long.class)
                .setParameter("role", Role.CLINIC_ADMIN)
                .getSingleResult();
    }

    public AppUser findByEmail(String email) {
        return em.createNamedQuery("AppUser.byEmail", AppUser.class)
                .setParameter("email", email)
                .getResultStream().findFirst().orElse(null);
    }

    // -------------------------------------------------------------- profile

    /** Self-service profile update. Role and status are not editable here. */
    @PermitAll
    public AppUser updateOwnProfile(String fullName, String phone) {
        AppUser user = currentUser.requireUser();
        String before = "name=" + user.getFullName() + "; phone=" + user.getPhone();
        user.setFullName(Validations.requireText(fullName, "Full name", 160));
        user.setPhone(Validations.optionalPhone(phone));
        user.setUpdatedAt(LocalDateTime.now());
        auditService.record(AuditEntry.of(AuditAction.PROFILE_UPDATED, "app_user")
                .entity(user.getId())
                .before(before)
                .after("name=" + user.getFullName() + "; phone=" + user.getPhone())
                .basis("self"));
        return user;
    }

    /** Password change for the signed-in user; the current password is required. */
    @PermitAll
    public void changeOwnPassword(String currentPassword, String newPassword, String confirm) {
        AppUser user = currentUser.requireUser();
        if (!passwordHasher.verify(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("Your current password is not correct.");
        }
        if (!java.util.Objects.equals(newPassword, confirm)) {
            throw new BusinessRuleException("The two new passwords do not match.");
        }
        String violation = passwordHasher.policyViolation(newPassword);
        if (violation != null) {
            throw new BusinessRuleException(violation);
        }
        if (passwordHasher.verify(newPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("Choose a password you have not used here before.");
        }
        user.setPasswordHash(passwordHasher.hash(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        auditService.record(AuditEntry.of(AuditAction.PASSWORD_CHANGED, "app_user")
                .entity(user.getId())
                .basis("self"));
    }

    public long countActiveUsers() {
        return em.createQuery("SELECT COUNT(u) FROM AppUser u WHERE u.active = true", Long.class)
                .getSingleResult();
    }
}
