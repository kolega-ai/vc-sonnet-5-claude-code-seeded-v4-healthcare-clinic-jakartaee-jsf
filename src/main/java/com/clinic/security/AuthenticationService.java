package com.clinic.security;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.AppUser;

import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Credential verification and the account-lockout policy.
 *
 * <p>Failures are deliberately indistinguishable to the caller: an unknown
 * email address, a wrong password, a disabled account and a locked account all
 * produce the same empty result and the same generic message in the UI.</p>
 */
@Stateless
@PermitAll
public class AuthenticationService {

    private static final int MAX_FAILED_LOGINS = 8;
    private static final int LOCK_MINUTES = 15;

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private PasswordHasher passwordHasher;

    @Inject
    private AuditService auditService;

    /**
     * Verifies a credential.
     *
     * @return the authenticated user, or empty for any failure reason
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Optional<AppUser> authenticate(String email, String password) {
        String normalised = email == null ? "" : email.trim().toLowerCase();
        if (normalised.isEmpty() || password == null || password.isEmpty()) {
            return Optional.empty();
        }

        Optional<AppUser> found = em.createNamedQuery("AppUser.byEmail", AppUser.class)
                .setParameter("email", normalised)
                .getResultStream().findFirst();

        if (found.isEmpty()) {
            // Spend comparable time so a missing account is not observably faster.
            passwordHasher.verify(password,
                    "pbkdf2_sha256$210000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            auditService.record(AuditEntry.of(AuditAction.LOGIN_FAILED, "session")
                    .anonymousActor(normalised)
                    .detail("No matching account")
                    .basis("credential"));
            return Optional.empty();
        }

        AppUser user = found.get();
        if (!user.isActive()) {
            auditService.record(AuditEntry.of(AuditAction.LOGIN_FAILED, "session")
                    .entity(user.getId())
                    .anonymousActor(normalised)
                    .detail("Account disabled")
                    .basis("credential"));
            return Optional.empty();
        }
        if (user.isLocked()) {
            auditService.record(AuditEntry.of(AuditAction.LOGIN_FAILED, "session")
                    .entity(user.getId())
                    .anonymousActor(normalised)
                    .detail("Account temporarily locked")
                    .basis("credential"));
            return Optional.empty();
        }

        if (!passwordHasher.verify(password, user.getPasswordHash())) {
            user.setFailedLogins(user.getFailedLogins() + 1);
            if (user.getFailedLogins() >= MAX_FAILED_LOGINS) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                user.setFailedLogins(0);
            }
            auditService.record(AuditEntry.of(AuditAction.LOGIN_FAILED, "session")
                    .entity(user.getId())
                    .anonymousActor(normalised)
                    .detail("Invalid password")
                    .basis("credential"));
            return Optional.empty();
        }

        user.setFailedLogins(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        auditService.record(AuditEntry.of(AuditAction.LOGIN_SUCCEEDED, "session")
                .entity(user.getId())
                .anonymousActor(normalised)
                .detail("Role " + user.getRole().getCode())
                .basis("credential"));
        return Optional.of(user);
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void recordLogout(Long userId, String email) {
        auditService.record(AuditEntry.of(AuditAction.LOGOUT, "session")
                .entity(userId)
                .anonymousActor(email)
                .basis("session"));
    }
}
