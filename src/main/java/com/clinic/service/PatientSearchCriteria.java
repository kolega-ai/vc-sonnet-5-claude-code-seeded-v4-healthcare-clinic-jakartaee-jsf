package com.clinic.service;

import java.io.Serializable;
import java.time.LocalDate;

/** Filter values for the patient directory search. */
public class PatientSearchCriteria implements Serializable {

    private static final long serialVersionUID = 1L;

    private String text;
    private Long providerId;
    private Boolean active = Boolean.TRUE;
    private LocalDate bornAfter;
    private LocalDate bornBefore;
    private String sortKey = "name";
    private int maxResults = 100;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDate getBornAfter() {
        return bornAfter;
    }

    public void setBornAfter(LocalDate bornAfter) {
        this.bornAfter = bornAfter;
    }

    public LocalDate getBornBefore() {
        return bornBefore;
    }

    public void setBornBefore(LocalDate bornBefore) {
        this.bornBefore = bornBefore;
    }

    public String getSortKey() {
        return sortKey;
    }

    public void setSortKey(String sortKey) {
        this.sortKey = sortKey;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public void reset() {
        text = null;
        providerId = null;
        active = Boolean.TRUE;
        bornAfter = null;
        bornBefore = null;
        sortKey = "name";
        maxResults = 100;
    }

    public boolean isEmpty() {
        return (text == null || text.isBlank())
                && providerId == null
                && bornAfter == null
                && bornBefore == null;
    }
}
