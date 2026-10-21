package com.clinic.web;

import com.clinic.domain.Provider;
import com.clinic.service.ImportService;
import com.clinic.service.ProviderService;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import jakarta.servlet.http.Part;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** The admin import/export workspace. */
@Named("importExport")
@ViewScoped
public class ImportExportBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;

    @EJB
    private ImportService importService;

    @EJB
    private ProviderService providerService;

    private transient Part csvFile;
    private String feedUrl;
    private boolean dryRun = true;
    private ImportService.ImportResult result;

    private LocalDate exportFrom = LocalDate.now().minusDays(7);
    private LocalDate exportTo = LocalDate.now().plusDays(30);
    private Long exportProviderId;
    private List<Provider> providers = List.of();

    @PostConstruct
    public void load() {
        try {
            providers = providerService.listActiveProviders();
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
    }

    public String runImport() {
        if (csvFile == null || csvFile.getSize() == 0) {
            Faces.error("Choose a CSV file to import.");
            return null;
        }
        if (csvFile.getSize() > MAX_UPLOAD_BYTES) {
            Faces.error("The import file is larger than 2 MB.");
            return null;
        }
        try (InputStream in = csvFile.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            importService.retainImportSource(csvFile.getSubmittedFileName(),
                    new ByteArrayInputStream(bytes));
            String content = new String(bytes, StandardCharsets.UTF_8);
            result = importService.importPatients(content, dryRun);
            if (result.isClean()) {
                Faces.success(dryRun
                        ? "Validation passed for " + result.getImported() + " rows. "
                        + "Untick 'validate only' to import them."
                        : result.getImported() + " patients imported.");
            } else {
                Faces.warn(result.getImported() + " rows accepted, "
                        + result.getSkipped() + " skipped. See the details below.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String runFeedImport() {
        if (feedUrl == null || feedUrl.isBlank()) {
            Faces.error("Enter the address of the roster feed.");
            return null;
        }
        try {
            result = importService.importFromFeed(feedUrl, dryRun);
            if (result.isClean()) {
                Faces.success(dryRun
                        ? "Validation passed for " + result.getImported() + " rows."
                        : result.getImported() + " patients imported from the feed.");
            } else {
                Faces.warn(result.getImported() + " rows accepted, "
                        + result.getSkipped() + " skipped. See the details below.");
            }
        } catch (RuntimeException e) {
            Faces.handle(e);
        }
        return null;
    }

    public String getTemplateCsv() {
        return importService.templateCsv();
    }

    public String scheduleExportUrl() {
        StringBuilder url = new StringBuilder(Faces.contextPath())
                .append("/api/secure/exports/schedule.csv?from=").append(exportFrom)
                .append("&to=").append(exportTo);
        if (exportProviderId != null) {
            url.append("&providerId=").append(exportProviderId);
        }
        return url.toString();
    }

    public String patientExportUrl() {
        return Faces.contextPath() + "/api/secure/exports/patients.csv";
    }

    public String auditExportUrl() {
        return Faces.contextPath() + "/api/secure/exports/audit-log.csv?from="
                + exportFrom + "&to=" + exportTo;
    }

    public String payerExportUrl() {
        return Faces.contextPath() + "/api/secure/exports/payer-summary.csv";
    }

    public String archiveUrl() {
        return Faces.contextPath() + "/intake-archive/";
    }

    // ------------------------------------------------------------ accessors

    public Part getCsvFile() {
        return csvFile;
    }

    public void setCsvFile(Part csvFile) {
        this.csvFile = csvFile;
    }

    public String getFeedUrl() {
        return feedUrl;
    }

    public void setFeedUrl(String feedUrl) {
        this.feedUrl = feedUrl;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public ImportService.ImportResult getResult() {
        return result;
    }

    public LocalDate getExportFrom() {
        return exportFrom;
    }

    public void setExportFrom(LocalDate exportFrom) {
        this.exportFrom = exportFrom;
    }

    public LocalDate getExportTo() {
        return exportTo;
    }

    public void setExportTo(LocalDate exportTo) {
        this.exportTo = exportTo;
    }

    public Long getExportProviderId() {
        return exportProviderId;
    }

    public void setExportProviderId(Long exportProviderId) {
        this.exportProviderId = exportProviderId;
    }

    public List<Provider> getProviders() {
        return providers;
    }
}
