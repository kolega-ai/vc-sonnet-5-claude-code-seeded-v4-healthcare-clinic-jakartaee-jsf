package com.clinic.rest;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * JAX-RS root.
 *
 * <ul>
 *   <li>{@code /api/public/*} — API-key authenticated integration endpoints.</li>
 *   <li>{@code /api/reference/*} — cacheable reference data, API-key authenticated.</li>
 *   <li>{@code /api/secure/*} — session authenticated; covered by a
 *       {@code security-constraint} in {@code web.xml}.</li>
 * </ul>
 */
@ApplicationPath("/api")
public class RestApplication extends Application {
}
