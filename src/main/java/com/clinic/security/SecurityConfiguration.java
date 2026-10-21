package com.clinic.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.security.enterprise.authentication.mechanism.http.CustomFormAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.LoginToContinue;

/**
 * Activates Jakarta Security custom form authentication.
 *
 * <p>The login page posts through {@code SecurityContext.authenticate(...)} in
 * {@code LoginBean}, which lets the application own the error messaging and the
 * session-fixation defence (the HTTP session id is regenerated on successful
 * login) while the container still owns the constraint enforcement declared in
 * {@code web.xml}.</p>
 */
@CustomFormAuthenticationMechanismDefinition(
        loginToContinue = @LoginToContinue(
                loginPage = "/login.xhtml",
                errorPage = "",
                useForwardToLogin = false))
@ApplicationScoped
public class SecurityConfiguration {
}
