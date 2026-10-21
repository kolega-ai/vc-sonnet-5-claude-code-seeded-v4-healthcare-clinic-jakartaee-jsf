package com.clinic.web;

import com.clinic.domain.Patient;
import com.clinic.domain.Provider;
import com.clinic.security.CurrentUser;
import com.clinic.service.PatientSearchCriteria;
import com.clinic.service.PatientService;
import com.clinic.service.ProviderService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.List;

/**
 * The patient directory with search, filters and the whitelisted sort control.
 * A clinician's results are automatically limited to their own panel by the
 * service, whatever filter is submitted.
 */
@Named("patientDirectory")
@ViewScoped
public class PatientDirectoryBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject
    private CurrentUser currentUser;

    @EJB
    private PatientService patientService;

    @EJB
    private ProviderService providerService;

    private final PatientSearchCriteria criteria = new PatientSearchCriteria();
    private List<Patient> results = List.of();
    private List<Provider> providers = List.of();
    private boolean searched;

    @PostConstruct
    public void load() {
        try {
            providers = providerService.listActiveProviders();
            results = patientService.search(criteria);
            searched = true;
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String search() {
        load();
        return null;
    }

    public String reset() {
        criteria.reset();
        load();
        return null;
    }

    public String chartLink(Patient patient) {
        String base = currentUser.isClinician() ? "/clinician/chart.xhtml" : "/staff/chart.xhtml";
        return base + "?patientId=" + patient.getId();
    }

    public List<String> getSortKeys() {
        return patientService.sortKeys();
    }

    public PatientSearchCriteria getCriteria() {
        return criteria;
    }

    public List<Patient> getResults() {
        return results;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public boolean isSearched() {
        return searched;
    }

    public boolean isEmptyResult() {
        return searched && results.isEmpty();
    }

    public int getResultCount() {
        return results.size();
    }
}
