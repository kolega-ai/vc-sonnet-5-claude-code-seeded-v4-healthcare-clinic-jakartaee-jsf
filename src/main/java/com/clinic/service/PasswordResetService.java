package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.AppUser;
import com.clinic.domain.PasswordResetToken;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.PasswordHasher;

import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Single-use, time-bound password reset.
 *
 * <p>Only the SHA-256 digest of the token is stored, the token is invalidated
 * the moment it is consumed, and the "request a reset" endpoint always reports
 * the same outcome so it cannot be used to enumerate accounts. The reset link
 * is written to the local outbox table rather than emailed.</p>
 */
@Stateless
@PermitAll
public class PasswordResetService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private PasswordHasher passwordHasher;

    @Inject
    private AuditService auditService;

    @Inject
    private IntegrationService integrationService;

    @Inject
    private SettingsService settingsService;

    /**
     * Issues a reset token if the address matches an active account. The caller
     * is told nothing about whether it did.
     */
    public void requestReset(String email, String baseUrl) {
        String normalised = email == null ? "" : email.trim().toLowerCase();
        auditService.record(AuditEntry.of(AuditAction.PASSWORD_RESET_REQUESTED, "app_user")
                .anonymousActor(normalised)
                .basis("anonymous"));
        if (normalised.isEmpty()) {
            return;
        }
        Optional<AppUser> found = em.createNamedQuery("AppUser.byEmail", AppUser.class)
                .setParameter("email", normalised)
                .getResultStream().findFirst();
        if (found.isEmpty() || !found.get().isActive()) {
            return;
        }
        AppUser user = found.get();

        // Invalidate any outstanding token for this account.
        em.createQuery("UPDATE PasswordResetToken t SET t.usedAt = :now "
                        + "WHERE t.user.id = :userId AND t.usedAt IS NULL")
                .setParameter("now", LocalDateTime.now())
                .setParameter("userId", user.getId())
                .executeUpdate();

        String secret = passwordHasher.randomToken(32);
        int ttl = settingsService.intValue("password.reset_ttl_minutes", 30);
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(passwordHasher.sha256Hex(secret));
        token.setExpiresAt(LocalDateTime.now().plusMinutes(ttl));
        token.setCreatedAt(LocalDateTime.now());
        em.persist(token);

        String link = (baseUrl == null ? "" : baseUrl) + "/reset-password.xhtml?token=" + secret;
        integrationService.queueEmail(user.getEmail(),
                "Reset your Clinic Patient Portal password",
                "Use the link below within " + ttl + " minutes to choose a new password. "
                        + "It can only be used once.\n\n" + link
                        + "\n\nIf you did not request this, no action is needed.");
    }

    /** True when the token is well formed, unused and unexpired. */
    public boolean isTokenUsable(String presentedToken) {
        return lookup(presentedToken).isPresent();
    }

    /** Consumes the token and sets the new password. */
    public void completeReset(String presentedToken, String newPassword, String confirm) {
        PasswordResetToken token = lookup(presentedToken)
                .orElseThrow(() -> new BusinessRuleException(
                        "This reset link is no longer valid. Please request a new one."));
        if (!java.util.Objects.equals(newPassword, confirm)) {
            throw new BusinessRuleException("The two passwords do not match.");
        }
        String violation = passwordHasher.policyViolation(newPassword);
        if (violation != null) {
            throw new BusinessRuleException(violation);
        }
        AppUser user = token.getUser();
        user.setPasswordHash(passwordHasher.hash(newPassword));
        user.setFailedLogins(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(LocalDateTime.now());
        token.setUsedAt(LocalDateTime.now());

        auditService.record(AuditEntry.of(AuditAction.PASSWORD_RESET_COMPLETED, "app_user")
                .entity(user.getId())
                .anonymousActor(user.getEmail())
                .basis("reset_token"));
    }

    private Optional<PasswordResetToken> lookup(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank() || presentedToken.length() > 200) {
            return Optional.empty();
        }
        return em.createNamedQuery("PasswordResetToken.byHash", PasswordResetToken.class)
                .setParameter("hash", passwordHasher.sha256Hex(presentedToken))
                .getResultStream()
                .filter(PasswordResetToken::isUsable)
                .filter(t -> t.getUser().isActive())
                .findFirst();
    }

    /** Housekeeping for the scheduled job: drop tokens that can never be used. */
    public int purgeExpiredTokens() {
        return em.createQuery("DELETE FROM PasswordResetToken t "
                        + "WHERE t.expiresAt < :cutoff OR t.usedAt IS NOT NULL")
                .setParameter("cutoff", LocalDateTime.now().minusDays(7))
                .executeUpdate();
    }
}
