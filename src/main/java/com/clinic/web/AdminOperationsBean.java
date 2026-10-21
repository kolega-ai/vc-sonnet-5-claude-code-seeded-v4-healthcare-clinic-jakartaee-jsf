package com.clinic.web;

import com.clinic.domain.JobRun;
import com.clinic.domain.OutboxEmail;
import com.clinic.domain.WebhookDelivery;
import com.clinic.service.IntegrationService;
import com.clinic.service.ScheduledJobs;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/**
 * Operations view: the local email outbox, webhook delivery attempts, and the
 * scheduled jobs (which can also be run on demand from here).
 */
@Named("adminOperations")
@ViewScoped
public class AdminOperationsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private IntegrationService integrationService;

    @EJB
    private ScheduledJobs scheduledJobs;

    private List<OutboxEmail> outbox = List.of();
    private List<WebhookDelivery> deliveries = List.of();
    private List<JobRun> runs = List.of();
    private OutboxEmail selectedEmail;

    @PostConstruct
    public void load() {
        try {
            outbox = integrationService.recentOutbox(50);
            deliveries = integrationService.recentDeliveries(50);
            runs = scheduledJobs.recentRuns(25);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String runJob(String jobName) {
        try {
            String detail = scheduledJobs.runNow(jobName);
            Faces.success(jobName + " completed: " + detail);
            load();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public void select(OutboxEmail email) {
        selectedEmail = email;
    }

    public List<String> getJobNames() {
        return List.of("appointment-reminders", "no-show-sweep", "nightly-housekeeping");
    }

    public List<OutboxEmail> getOutbox() {
        return outbox;
    }

    public List<WebhookDelivery> getDeliveries() {
        return deliveries;
    }

    public List<JobRun> getRuns() {
        return runs;
    }

    public OutboxEmail getSelectedEmail() {
        return selectedEmail;
    }
}
