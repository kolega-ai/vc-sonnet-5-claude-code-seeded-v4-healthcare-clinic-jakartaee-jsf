package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.AppUser;
import com.clinic.domain.Message;
import com.clinic.domain.MessageReceipt;
import com.clinic.domain.MessageThread;
import com.clinic.domain.Patient;
import com.clinic.domain.Provider;
import com.clinic.domain.Role;
import com.clinic.domain.ThreadStatus;
import com.clinic.security.AccessDecision;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.security.CurrentUser;
import com.clinic.security.RecordNotFoundException;
import com.clinic.storage.DocumentStorage;
import com.clinic.util.Validations;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;

/**
 * Secure messaging between patients and clinic staff.
 *
 * <p>A message may be flagged {@code internal_only}. Internal notes are staff
 * co-ordination and are filtered out of every patient-facing read <em>in the
 * query</em>, not in the view layer, so no template mistake can expose them.
 * Patients can never author an internal note.</p>
 */
@Stateless
public class MessageService {

    private static final Logger LOG = Logger.getLogger(MessageService.class.getName());

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private AuditService auditService;

    @Inject
    private NotificationService notificationService;

    @Inject
    private DocumentStorage storage;

    @Inject
    private IntegrationService integrationService;

    // --------------------------------------------------------------- threads

    @PermitAll
    public List<MessageThread> threadsForPatient(Long patientId) {
        accessGuard.requirePatientAccess(patientId);
        return em.createNamedQuery("MessageThread.byPatient", MessageThread.class)
                .setParameter("patientId", patientId)
                .getResultList();
    }

    /** The staff inbox, with optional filters. */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public List<MessageThread> inbox(ThreadStatus status, String text, boolean minePanelOnly) {
        accessGuard.requireStaff();
        StringBuilder jpql = new StringBuilder("SELECT t FROM MessageThread t WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null) {
            jpql.append(" AND t.status = :status");
            params.put("status", status);
        }
        if (text != null && !text.isBlank()) {
            jpql.append(" AND (lower(t.subject) LIKE :text")
                    .append(" OR lower(t.patient.lastName) LIKE :text")
                    .append(" OR lower(t.patient.mrn) LIKE :text)");
            params.put("text", "%" + text.trim().toLowerCase() + "%");
        }
        if (currentUser.getRole() == Role.CLINICIAN) {
            Provider provider = currentUser.getProvider();
            if (provider == null) {
                return List.of();
            }
            if (minePanelOnly) {
                jpql.append(" AND (t.assignedProvider.id = :providerId")
                        .append(" OR t.patient.primaryProvider.id = :providerId)");
                params.put("providerId", provider.getId());
            }
        }
        jpql.append(" ORDER BY t.lastMessageAt DESC");
        TypedQuery<MessageThread> query = em.createQuery(jpql.toString(), MessageThread.class);
        params.forEach(query::setParameter);
        query.setMaxResults(200);
        List<MessageThread> results = query.getResultList();
        auditService.recordRead("message.inbox_viewed", "message_thread", null,
                results.size(), "role:" + currentUser.getRole().getCode());
        return results;
    }

    /**
     * Opens a thread and returns the messages the caller may actually see.
     * Message access is audited once per thread read.
     */
    @PermitAll
    public ThreadView openThread(Long threadId) {
        MessageThread thread = em.find(MessageThread.class, threadId);
        AccessDecision decision = accessGuard.requireThreadAccess(thread);
        List<Message> messages;
        if (accessGuard.canSeeInternalNotes()) {
            messages = em.createNamedQuery("Message.byThread", Message.class)
                    .setParameter("threadId", threadId)
                    .getResultList();
        } else {
            messages = em.createNamedQuery("Message.patientVisibleByThread", Message.class)
                    .setParameter("threadId", threadId)
                    .getResultList();
        }
        auditService.recordIndependently(
                AuditEntry.of(AuditAction.MESSAGE_THREAD_VIEWED, "message_thread")
                        .entity(threadId)
                        .patient(thread.getPatient().getId())
                        .detail("messages=" + messages.size())
                        .basis(decision.basis()));
        markRead(messages);
        return new ThreadView(thread, messages);
    }

    private void markRead(List<Message> messages) {
        AppUser me = currentUser.getUser();
        if (me == null) {
            return;
        }
        for (Message message : messages) {
            if (message.getSender().getId().equals(me.getId())) {
                continue;
            }
            Long existing = em.createQuery(
                            "SELECT COUNT(r) FROM MessageReceipt r "
                                    + "WHERE r.message.id = :messageId AND r.user.id = :userId",
                            Long.class)
                    .setParameter("messageId", message.getId())
                    .setParameter("userId", me.getId())
                    .getSingleResult();
            if (existing == 0) {
                MessageReceipt receipt = new MessageReceipt();
                receipt.setMessage(message);
                receipt.setUser(me);
                receipt.setReadAt(LocalDateTime.now());
                em.persist(receipt);
            }
        }
    }

    @PermitAll
    public MessageThread startThread(Long patientId, String subject, String category,
                                     String body, Long assignedProviderId) {
        AccessDecision decision = accessGuard.requirePatientAccess(patientId);
        Patient patient = em.find(Patient.class, patientId);
        if (patient == null) {
            throw new RecordNotFoundException("Patient not found.");
        }
        MessageThread thread = new MessageThread();
        thread.setPatient(patient);
        thread.setSubject(Validations.requireText(subject, "Subject", 200));
        thread.setCategory(categoryOrDefault(category));
        thread.setStatus(ThreadStatus.OPEN);
        thread.setCreatedBy(currentUser.getUser());
        thread.setCreatedAt(LocalDateTime.now());
        thread.setLastMessageAt(LocalDateTime.now());
        if (assignedProviderId != null) {
            thread.setAssignedProvider(em.find(Provider.class, assignedProviderId));
        } else {
            thread.setAssignedProvider(patient.getPrimaryProvider());
        }
        em.persist(thread);
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.MESSAGE_THREAD_CREATED, "message_thread")
                .entity(thread.getId())
                .patient(patientId)
                .after("subject=" + thread.getSubject())
                .basis(decision.basis()));

        post(thread.getId(), body, false);
        return thread;
    }

    private static String categoryOrDefault(String category) {
        List<String> allowed = List.of("general", "clinical", "billing", "prescription", "scheduling");
        return category != null && allowed.contains(category) ? category : "general";
    }

    /**
     * Adds a message to a thread.
     *
     * @param internalOnly staff-only note; a patient requesting this is rejected
     */
    @PermitAll
    public Message post(Long threadId, String body, boolean internalOnly) {
        MessageThread thread = em.find(MessageThread.class, threadId);
        AccessDecision decision = accessGuard.requireThreadAccess(thread);
        if (internalOnly && !currentUser.isStaff()) {
            throw new BusinessRuleException("Only staff may add an internal note.");
        }
        if (thread.getStatus() == ThreadStatus.CLOSED) {
            throw new BusinessRuleException("This conversation is closed. Please start a new one.");
        }

        Message message = new Message();
        message.setThread(thread);
        message.setSender(currentUser.requireUser());
        message.setBody(Validations.requireText(body, "Message", 8000));
        message.setInternalOnly(internalOnly);
        message.setCreatedAt(LocalDateTime.now());
        em.persist(message);

        LOG.log(Level.INFO, "Message accepted on thread {0} from {1}: {2}",
                new Object[]{thread.getId(), currentUser.getEmail(), message.getBody()});

        thread.setLastMessageAt(LocalDateTime.now());
        if (!internalOnly) {
            thread.setStatus(currentUser.isStaff() ? ThreadStatus.AWAITING_PATIENT : ThreadStatus.OPEN);
        }
        em.flush();

        auditService.record(AuditEntry.of(AuditAction.MESSAGE_SENT, "message")
                .entity(message.getId())
                .patient(thread.getPatient().getId())
                .after("internal=" + internalOnly + "; chars=" + message.getBody().length())
                .basis(decision.basis()));

        if (!internalOnly && !currentUser.isStaff()
                && thread.getAssignedProvider() != null
                && thread.getAssignedProvider().getUser() != null) {
            // The message assistant drafts the clinic's reply; it is posted back
            // to the conversation so the patient gets an answer immediately.
            String draft = integrationService.draftClinicalReply(message.getBody());
            if (draft != null && !draft.isBlank()) {
                Message reply = new Message();
                reply.setThread(thread);
                reply.setSender(thread.getAssignedProvider().getUser());
                reply.setBody(draft);
                reply.setInternalOnly(false);
                reply.setCreatedAt(LocalDateTime.now());
                em.persist(reply);
            }
        }

        if (!internalOnly) {
            if (currentUser.isStaff()) {
                if (thread.getPatient().getUser() != null) {
                    notificationService.notifyUser(thread.getPatient().getUser(),
                            "message.received", "New message from the clinic",
                            thread.getSubject(), "/patient/messages.xhtml");
                }
            } else if (thread.getAssignedProvider() != null
                    && thread.getAssignedProvider().getUser() != null) {
                notificationService.notifyUser(thread.getAssignedProvider().getUser(),
                        "message.received", "New patient message",
                        thread.getPatient().getFullName() + ": " + thread.getSubject(),
                        "/staff/messages.xhtml");
            } else {
                notificationService.notifyRole(Role.FRONT_DESK, "message.received",
                        "New patient message", thread.getSubject(), "/staff/messages.xhtml");
            }
        }
        return message;
    }

    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public MessageThread changeStatus(Long threadId, ThreadStatus target) {
        accessGuard.requireStaff();
        MessageThread thread = em.find(MessageThread.class, threadId);
        AccessDecision decision = accessGuard.requireThreadAccess(thread);
        ThreadStatus current = thread.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new BusinessRuleException("A " + current.getLabel().toLowerCase()
                    + " conversation cannot become " + target.getLabel().toLowerCase() + ".");
        }
        thread.setStatus(target);
        auditService.record(
                AuditEntry.of(AuditAction.MESSAGE_THREAD_STATUS_CHANGED, "message_thread")
                        .entity(threadId)
                        .patient(thread.getPatient().getId())
                        .before("status=" + current.getCode())
                        .after("status=" + target.getCode())
                        .basis(decision.basis()));
        return thread;
    }

    /**
     * Saves a whole conversation as a plain-text transcript under the messaging
     * spool directory that the after-hours answering service reads.
     */
    @RolesAllowed({Role.CLINIC_ADMIN_ROLE, Role.CLINICIAN_ROLE, Role.FRONT_DESK_ROLE})
    public String archiveConversation(Long threadId) {
        accessGuard.requireStaff();
        MessageThread thread = em.find(MessageThread.class, threadId);
        if (thread == null) {
            throw new RecordNotFoundException("Message thread not found.");
        }
        AccessDecision decision = accessGuard.requireThreadAccess(thread);
        List<Message> messages = em.createNamedQuery("Message.byThread", Message.class)
                .setParameter("threadId", threadId)
                .getResultList();
        StringBuilder transcript = new StringBuilder();
        for (Message message : messages) {
            transcript.append(message.getSender().getFullName())
                    .append(": ")
                    .append(message.getBody())
                    .append(System.lineSeparator());
        }
        File spool = new File(storage.getBaseDirectory().toFile(), "spool");
        spool.mkdirs();
        try (FileWriter writer = new FileWriter(new File(spool, thread.getSubject() + ".txt"))) {
            writer.write(transcript.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to archive the conversation", e);
        }
        auditService.record(AuditEntry.of(AuditAction.EXPORT_GENERATED, "message_thread")
                .entity(threadId)
                .patient(thread.getPatient().getId())
                .detail("transcript=" + thread.getSubject() + ".txt")
                .basis(decision.basis()));
        return thread.getSubject() + ".txt";
    }

    // ----------------------------------------------------------------- counts

    public long unreadForPatient(Long patientId) {
        Long userId = currentUser.getUserId();
        if (userId == null) {
            return 0;
        }
        return em.createQuery(
                        "SELECT COUNT(m) FROM Message m WHERE m.thread.patient.id = :patientId "
                                + "AND m.internalOnly = false AND m.sender.id <> :userId "
                                + "AND NOT EXISTS (SELECT r FROM MessageReceipt r "
                                + "WHERE r.message = m AND r.user.id = :userId)", Long.class)
                .setParameter("patientId", patientId)
                .setParameter("userId", userId)
                .getSingleResult();
    }

    public long openThreadCount() {
        return em.createQuery(
                        "SELECT COUNT(t) FROM MessageThread t WHERE t.status IN (:open)", Long.class)
                .setParameter("open", List.of(ThreadStatus.OPEN, ThreadStatus.AWAITING_PATIENT))
                .getSingleResult();
    }

    /** A thread plus exactly the messages the caller is entitled to read. */
    public record ThreadView(MessageThread thread, List<Message> messages) {
    }
}
