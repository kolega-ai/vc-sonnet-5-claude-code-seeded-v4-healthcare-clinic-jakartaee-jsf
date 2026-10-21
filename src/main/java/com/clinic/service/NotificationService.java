package com.clinic.service;

import com.clinic.domain.AppUser;
import com.clinic.domain.Notification;
import com.clinic.domain.Role;
import com.clinic.security.AccessDeniedException;
import com.clinic.security.CurrentUser;

import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * In-app notifications for workflow events.
 *
 * <p>Reads are always scoped to the calling user: a notification can only ever
 * be listed or marked read by its own recipient.</p>
 */
@Stateless
@PermitAll
public class NotificationService {

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private CurrentUser currentUser;

    /** Creates a notification for one user. Called by the workflow services. */
    public void notifyUser(AppUser recipient, String type, String title, String body, String link) {
        if (recipient == null || !recipient.isActive()) {
            return;
        }
        Notification notification = new Notification();
        notification.setUser(recipient);
        notification.setType(type);
        notification.setTitle(trim(title, 200));
        notification.setBody(trim(body, 600));
        notification.setLink(trim(link, 300));
        notification.setCreatedAt(LocalDateTime.now());
        em.persist(notification);
    }

    /** Fan-out to every active user holding a role, e.g. the refill queue owners. */
    public void notifyRole(Role role, String type, String title, String body, String link) {
        List<AppUser> recipients = em.createNamedQuery("AppUser.byRole", AppUser.class)
                .setParameter("role", role)
                .getResultList();
        for (AppUser recipient : recipients) {
            notifyUser(recipient, type, title, body, link);
        }
    }

    public List<Notification> listForCurrentUser(int limit) {
        Long userId = requireUserId();
        return em.createNamedQuery("Notification.byUser", Notification.class)
                .setParameter("userId", userId)
                .setMaxResults(Math.min(Math.max(limit, 1), 200))
                .getResultList();
    }

    public List<Notification> listUnreadForCurrentUser(int limit) {
        Long userId = requireUserId();
        return em.createNamedQuery("Notification.unreadByUser", Notification.class)
                .setParameter("userId", userId)
                .setMaxResults(Math.min(Math.max(limit, 1), 200))
                .getResultList();
    }

    public long unreadCount() {
        if (!currentUser.isAuthenticated()) {
            return 0;
        }
        return em.createQuery(
                        "SELECT COUNT(n) FROM Notification n "
                                + "WHERE n.user.id = :userId AND n.readAt IS NULL", Long.class)
                .setParameter("userId", currentUser.getUserId())
                .getSingleResult();
    }

    /** Ownership check: a notification may only be dismissed by its recipient. */
    public void markRead(Long notificationId) {
        Long userId = requireUserId();
        Notification notification = em.find(Notification.class, notificationId);
        if (notification == null) {
            return;
        }
        if (!notification.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("You may only update your own notifications.",
                    "notification:" + notificationId);
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
        }
    }

    public int markAllRead() {
        Long userId = requireUserId();
        return em.createQuery(
                        "UPDATE Notification n SET n.readAt = :now "
                                + "WHERE n.user.id = :userId AND n.readAt IS NULL")
                .setParameter("now", LocalDateTime.now())
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private Long requireUserId() {
        Long userId = currentUser.getUserId();
        if (userId == null) {
            throw new AccessDeniedException("Not authenticated.");
        }
        return userId;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
