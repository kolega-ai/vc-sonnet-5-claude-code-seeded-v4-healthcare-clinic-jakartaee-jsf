package com.clinic.web;

import com.clinic.domain.ApiKey;
import com.clinic.domain.ClinicSetting;
import com.clinic.domain.WebhookEndpoint;
import com.clinic.service.IntegrationService;
import com.clinic.service.SettingsService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Clinic settings, API keys and webhook endpoints. */
@Named("adminSettings")
@ViewScoped
public class AdminSettingsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private SettingsService settingsService;

    @EJB
    private IntegrationService integrationService;

    private List<ClinicSetting> settings = List.of();
    private List<ApiKey> apiKeys = List.of();
    private List<WebhookEndpoint> endpoints = List.of();
    private final Map<String, String> edits = new LinkedHashMap<>();

    private String newKeyLabel;
    private String newKeyScope = "availability:read";
    /** Shown exactly once, immediately after creation; never stored in clear. */
    private String issuedKey;

    private String newEndpointLabel;
    private String newEndpointUrl;
    private String newEndpointEvent = "appointment.status_changed";

    @PostConstruct
    public void load() {
        try {
            settings = settingsService.listAll();
            apiKeys = integrationService.listApiKeys();
            endpoints = integrationService.listEndpoints();
            edits.clear();
            for (ClinicSetting setting : settings) {
                edits.put(setting.getSettingKey(), setting.getSettingValue());
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String save(ClinicSetting setting) {
        try {
            settingsService.update(setting.getSettingKey(), edits.get(setting.getSettingKey()));
            Faces.success("Setting updated.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String createApiKey() {
        try {
            issuedKey = integrationService.createApiKey(newKeyLabel, newKeyScope);
            Faces.success("API key created. Copy it now — it is not shown again.");
            newKeyLabel = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String revoke(ApiKey key) {
        try {
            integrationService.revokeApiKey(key.getId());
            Faces.success("API key revoked.");
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String addEndpoint() {
        try {
            integrationService.registerEndpoint(newEndpointLabel, newEndpointUrl,
                    newEndpointEvent);
            Faces.success("Webhook endpoint registered.");
            newEndpointLabel = null;
            newEndpointUrl = null;
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public List<String> getEventTypes() {
        return List.of("appointment.status_changed", "refill.decided");
    }

    public List<String> getScopes() {
        return List.of("availability:read", "schedule:read", "results:write");
    }

    // ------------------------------------------------------------ accessors

    public List<ClinicSetting> getSettings() {
        return settings;
    }

    public Map<String, String> getEdits() {
        return edits;
    }

    public List<ApiKey> getApiKeys() {
        return apiKeys;
    }

    public List<WebhookEndpoint> getEndpoints() {
        return endpoints;
    }

    public String getNewKeyLabel() {
        return newKeyLabel;
    }

    public void setNewKeyLabel(String newKeyLabel) {
        this.newKeyLabel = newKeyLabel;
    }

    public String getNewKeyScope() {
        return newKeyScope;
    }

    public void setNewKeyScope(String newKeyScope) {
        this.newKeyScope = newKeyScope;
    }

    public String getIssuedKey() {
        return issuedKey;
    }

    public String getNewEndpointLabel() {
        return newEndpointLabel;
    }

    public void setNewEndpointLabel(String newEndpointLabel) {
        this.newEndpointLabel = newEndpointLabel;
    }

    public String getNewEndpointUrl() {
        return newEndpointUrl;
    }

    public void setNewEndpointUrl(String newEndpointUrl) {
        this.newEndpointUrl = newEndpointUrl;
    }

    public String getNewEndpointEvent() {
        return newEndpointEvent;
    }

    public void setNewEndpointEvent(String newEndpointEvent) {
        this.newEndpointEvent = newEndpointEvent;
    }
}
