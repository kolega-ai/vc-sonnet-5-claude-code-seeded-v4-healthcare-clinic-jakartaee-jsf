package com.clinic.security;

import com.clinic.domain.AppUser;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStore;

import java.util.Optional;
import java.util.Set;

/**
 * Jakarta Security identity store backed by the {@code app_user} table.
 *
 * <p>The caller principal is the user's email address and the single group is
 * the user's role code, which is what {@code web.xml} security constraints and
 * {@code @RolesAllowed} match on.</p>
 */
@ApplicationScoped
public class ClinicIdentityStore implements IdentityStore {

    @Inject
    private AuthenticationService authenticationService;

    @Override
    public CredentialValidationResult validate(Credential credential) {
        if (!(credential instanceof UsernamePasswordCredential upc)) {
            return CredentialValidationResult.NOT_VALIDATED_RESULT;
        }
        Optional<AppUser> user = authenticationService.authenticate(
                upc.getCaller(), upc.getPasswordAsString());
        return user
                .map(u -> new CredentialValidationResult(u.getEmail(), Set.of(u.getRole().getCode())))
                .orElse(CredentialValidationResult.INVALID_RESULT);
    }

    @Override
    public Set<ValidationType> validationTypes() {
        return Set.of(ValidationType.VALIDATE, ValidationType.PROVIDE_GROUPS);
    }
}
