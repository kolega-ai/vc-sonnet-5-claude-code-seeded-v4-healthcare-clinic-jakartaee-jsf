package com.clinic.web;

import com.clinic.domain.Notification;
import com.clinic.security.CurrentUser;
import com.clinic.service.NotificationService;
import com.clinic.service.SettingsService;
import com.clinic.util.AssetVersion;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Backs the application shell: clinic branding, role-aware navigation, the
 * notification bell and the static-asset cache-busting suffix.
 */
@Named("layout")
@RequestScoped
public class LayoutBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AssetVersion assetVersion;

    @EJB
    private SettingsService settingsService;

    @EJB
    private NotificationService notificationService;

    private List<Notification> recentNotifications;
    private Long unreadCount;

    public String getClinicName() {
        return settingsService.clinicName();
    }

    public String getClinicPhone() {
        return settingsService.value("clinic.phone", "");
    }

    public String getClinicAddress() {
        return settingsService.value("clinic.address", "");
    }

    /**
     * Cache-busting suffix for static assets.
     *
     * <p><strong>Precision-baseline primitive #2.</strong> The value comes from
     * a {@link java.util.Random} in {@link AssetVersion}. It is a cache key for
     * CSS and JavaScript, never a token, identifier or secret — see the class
     * comment there for why {@code SecureRandom} would be the wrong tool.</p>
     */
    public String getAssetSuffix() {
        return assetVersion.getSuffix();
    }

    public String getToday() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"));
    }

    public CurrentUser getUser() {
        return currentUser;
    }

    public long getUnreadNotifications() {
        if (unreadCount == null) {
            unreadCount = currentUser.isAuthenticated() ? notificationService.unreadCount() : 0L;
        }
        return unreadCount;
    }

    public List<Notification> getRecentNotifications() {
        if (recentNotifications == null) {
            recentNotifications = currentUser.isAuthenticated()
                    ? notificationService.listForCurrentUser(8) : List.of();
        }
        return recentNotifications;
    }

    public String markAllRead() {
        try {
            notificationService.markAllRead();
            Faces.success("All notifications marked as read.");
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    /** The anchor form of "mark read", for clients without scripting. */
    public String seenUrl(Notification notification) {
        return Faces.contextPath() + "/api/secure/notifications/"
                + notification.getId() + "/seen";
    }

    public String markRead(Long notificationId) {
        try {
            notificationService.markRead(notificationId);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    /** Root path of the workspace for the signed-in role, used by the logo link. */
    public String getHomePath() {
        if (!currentUser.isAuthenticated()) {
            return "/index.xhtml";
        }
        return switch (currentUser.getRole()) {
            case PATIENT -> "/patient/dashboard.xhtml";
            case CLINICIAN -> "/clinician/dashboard.xhtml";
            case FRONT_DESK -> "/staff/dashboard.xhtml";
            case CLINIC_ADMIN -> "/admin/dashboard.xhtml";
        };
    }
}
