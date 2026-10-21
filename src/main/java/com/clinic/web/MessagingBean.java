package com.clinic.web;

import com.clinic.domain.Message;
import com.clinic.domain.MessageThread;
import com.clinic.domain.Provider;
import com.clinic.domain.ThreadStatus;
import com.clinic.security.CurrentUser;
import com.clinic.service.MessageService;
import com.clinic.service.ProviderService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/**
 * Secure messaging for both sides of the conversation.
 *
 * <p>The bean never decides what a reader may see: the service returns exactly
 * the messages the caller is entitled to, so internal notes are already absent
 * from a patient's list before the view renders.</p>
 */
@Named("messaging")
@ViewScoped
public class MessagingBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private MessageService messageService;

    @EJB
    private ProviderService providerService;

    private List<MessageThread> threads = List.of();
    private List<Message> messages = List.of();
    private List<Provider> providers = List.of();
    private MessageThread selected;

    private String filterStatus;
    private String filterText;
    private boolean myPanelOnly = true;

    private String replyBody;
    private boolean replyInternal;

    private String newSubject;
    private String newCategory = "general";
    private String newBody;
    private Long newPatientId;
    private Long newProviderId;

    @PostConstruct
    public void load() {
        try {
            if (currentUser.isStaff()) {
                threads = messageService.inbox(
                        filterStatus == null || filterStatus.isBlank()
                                ? null : ThreadStatus.fromCode(filterStatus),
                        filterText, myPanelOnly);
                providers = providerService.listActiveProviders();
            } else if (currentUser.getPatientId() != null) {
                threads = messageService.threadsForPatient(currentUser.getPatientId());
                providers = providerService.listActiveProviders();
            }
            Long requested = Faces.longParam("threadId");
            if (requested != null) {
                openThread(requested);
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String search() {
        load();
        return null;
    }

    public String resetFilters() {
        filterStatus = null;
        filterText = null;
        myPanelOnly = true;
        load();
        return null;
    }

    public String openThread(Long threadId) {
        try {
            MessageService.ThreadView view = messageService.openThread(threadId);
            selected = view.thread();
            messages = view.messages();
        } catch (RuntimeException e) {
            selected = null;
            messages = List.of();
            Faces.handle(e);
        }
        return null;
    }

    public String reply() {
        if (selected == null) {
            Faces.error("Open a conversation first.");
            return null;
        }
        try {
            messageService.post(selected.getId(), replyBody,
                    replyInternal && currentUser.isStaff());
            replyBody = null;
            replyInternal = false;
            Faces.success("Message sent.");
            Long id = selected.getId();
            load();
            openThread(id);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String startThread() {
        try {
            Long patientId = currentUser.isStaff() ? newPatientId : currentUser.getPatientId();
            MessageThread thread = messageService.startThread(patientId, newSubject,
                    newCategory, newBody, newProviderId);
            newSubject = null;
            newBody = null;
            newPatientId = null;
            Faces.success("Your message has been sent to the clinic.");
            load();
            openThread(thread.getId());
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String changeStatus(String code) {
        if (selected == null) {
            return null;
        }
        try {
            messageService.changeStatus(selected.getId(), ThreadStatus.fromCode(code));
            Faces.success("Conversation updated.");
            Long id = selected.getId();
            load();
            openThread(id);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String archive() {
        if (selected == null) {
            Faces.error("Open a conversation first.");
            return null;
        }
        try {
            String name = messageService.archiveConversation(selected.getId());
            Faces.success("Conversation saved as " + name);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public boolean isFromMe(Message message) {
        return message != null && currentUser.getUserId() != null
                && message.getSender().getId().equals(currentUser.getUserId());
    }

    public ThreadStatus[] getStatuses() {
        return ThreadStatus.values();
    }

    public List<String> getCategories() {
        return List.of("general", "clinical", "billing", "prescription", "scheduling");
    }

    // ------------------------------------------------------------ accessors

    public List<MessageThread> getThreads() {
        return threads;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public MessageThread getSelected() {
        return selected;
    }

    public boolean isStaff() {
        return currentUser.isStaff();
    }

    public String getFilterStatus() {
        return filterStatus;
    }

    public void setFilterStatus(String filterStatus) {
        this.filterStatus = filterStatus;
    }

    public String getFilterText() {
        return filterText;
    }

    public void setFilterText(String filterText) {
        this.filterText = filterText;
    }

    public boolean isMyPanelOnly() {
        return myPanelOnly;
    }

    public void setMyPanelOnly(boolean myPanelOnly) {
        this.myPanelOnly = myPanelOnly;
    }

    public String getReplyBody() {
        return replyBody;
    }

    public void setReplyBody(String replyBody) {
        this.replyBody = replyBody;
    }

    public boolean isReplyInternal() {
        return replyInternal;
    }

    public void setReplyInternal(boolean replyInternal) {
        this.replyInternal = replyInternal;
    }

    public String getNewSubject() {
        return newSubject;
    }

    public void setNewSubject(String newSubject) {
        this.newSubject = newSubject;
    }

    public String getNewCategory() {
        return newCategory;
    }

    public void setNewCategory(String newCategory) {
        this.newCategory = newCategory;
    }

    public String getNewBody() {
        return newBody;
    }

    public void setNewBody(String newBody) {
        this.newBody = newBody;
    }

    public Long getNewPatientId() {
        return newPatientId;
    }

    public void setNewPatientId(Long newPatientId) {
        this.newPatientId = newPatientId;
    }

    public Long getNewProviderId() {
        return newProviderId;
    }

    public void setNewProviderId(Long newProviderId) {
        this.newProviderId = newProviderId;
    }
}
