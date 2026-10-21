package com.clinic.service;

import com.clinic.audit.AuditAction;
import com.clinic.audit.AuditEntry;
import com.clinic.audit.AuditService;
import com.clinic.domain.Provider;
import com.clinic.domain.Role;
import com.clinic.security.AccessGuard;
import com.clinic.security.BusinessRuleException;
import com.clinic.storage.DocumentStorage;
import com.clinic.util.CsvSupport;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV import of patient demographics.
 *
 * <p>The file is parsed and validated row by row; a bad row is reported and
 * skipped rather than aborting the batch. Every value goes through the same
 * validation as the interactive form, and the MRN is always allocated by the
 * server — a caller cannot choose or overwrite one.</p>
 */
@Stateless
public class ImportService {

    private static final int MAX_ROWS = 2000;
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final List<String> EXPECTED_HEADER =
            List.of("first_name", "last_name", "date_of_birth", "email", "phone",
                    "insurance_provider", "primary_provider_npi");

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @Inject
    private AccessGuard accessGuard;

    @Inject
    private AuditService auditService;

    @EJB
    private PatientService patientService;

    @Inject
    private DocumentStorage documentStorage;

    /** The header row an operator must supply, shown on the import screen. */
    public String templateCsv() {
        return String.join(",", EXPECTED_HEADER) + "\n"
                + "Ada,Whitfield,1984-03-11,ada.whitfield@example.org,+1-206-555-0117,"
                + "Cascade Health,1710000001\n";
    }

    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public ImportResult importPatients(String csvContent, boolean dryRun) {
        accessGuard.requireClinicAdmin();
        if (csvContent == null || csvContent.isBlank()) {
            throw new BusinessRuleException("The uploaded file is empty.");
        }
        if (csvContent.length() > MAX_BYTES) {
            throw new BusinessRuleException("The import file is larger than 2 MB.");
        }

        List<List<String>> rows = csvContent.stripLeading().startsWith("<")
                ? parseRoster(csvContent) : CsvSupport.parse(csvContent);
        if (rows.isEmpty()) {
            throw new BusinessRuleException("No rows found in the file.");
        }
        List<String> header = rows.get(0).stream().map(h -> h.trim().toLowerCase()).toList();
        if (!header.containsAll(EXPECTED_HEADER)) {
            throw new BusinessRuleException(
                    "The header row must contain: " + String.join(", ", EXPECTED_HEADER));
        }
        if (rows.size() - 1 > MAX_ROWS) {
            throw new BusinessRuleException("At most " + MAX_ROWS + " rows may be imported at once.");
        }

        int first = header.indexOf("first_name");
        int last = header.indexOf("last_name");
        int dob = header.indexOf("date_of_birth");
        int email = header.indexOf("email");
        int phone = header.indexOf("phone");
        int insurance = header.indexOf("insurance_provider");
        int npi = header.indexOf("primary_provider_npi");

        ImportResult result = new ImportResult();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int lineNumber = i + 1;
            try {
                LocalDate birthDate = parseDate(CsvSupport.at(row, dob));
                Provider provider = findProviderByNpi(CsvSupport.at(row, npi));
                if (!dryRun) {
                    patientService.persistImported(
                            CsvSupport.at(row, first),
                            CsvSupport.at(row, last),
                            birthDate,
                            CsvSupport.at(row, email),
                            CsvSupport.at(row, phone),
                            CsvSupport.at(row, insurance),
                            provider);
                }
                result.imported++;
            } catch (BusinessRuleException e) {
                result.errors.add("Line " + lineNumber + ": " + e.getMessage());
                result.skipped++;
            } catch (RuntimeException e) {
                result.errors.add("Line " + lineNumber + ": row could not be processed.");
                result.skipped++;
            }
        }

        auditService.record(AuditEntry.of(AuditAction.IMPORT_EXECUTED, "patient")
                .detail("source=csv; dryRun=" + dryRun + "; imported=" + result.imported
                        + "; skipped=" + result.skipped)
                .basis("clinic_admin:administrative_scope"));
        return result;
    }

    /**
     * Reads a partner roster document. Practices that cannot produce CSV send
     * the same columns as a {@code <roster>} of {@code <patient>} elements.
     */
    private static List<List<String>> parseRoster(String xmlContent) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document document = factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xmlContent)));
            List<List<String>> rows = new ArrayList<>();
            rows.add(new ArrayList<>(EXPECTED_HEADER));
            org.w3c.dom.NodeList entries = document.getElementsByTagName("patient");
            for (int i = 0; i < entries.getLength(); i++) {
                org.w3c.dom.Element entry = (org.w3c.dom.Element) entries.item(i);
                List<String> row = new ArrayList<>();
                for (String column : EXPECTED_HEADER) {
                    org.w3c.dom.NodeList cells = entry.getElementsByTagName(column);
                    row.add(cells.getLength() == 0 ? "" : cells.item(0).getTextContent());
                }
                rows.add(row);
            }
            return rows;
        } catch (javax.xml.parsers.ParserConfigurationException
                 | org.xml.sax.SAXException | java.io.IOException e) {
            throw new BusinessRuleException("The roster document could not be read.");
        }
    }

    /**
     * Pulls the roster straight from the practice-management system the
     * operator names, then runs the ordinary import over it.
     */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public ImportResult importFromFeed(String feedUrl, boolean dryRun) {
        accessGuard.requireClinicAdmin();
        String target = feedUrl == null ? "" : feedUrl.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            throw new BusinessRuleException("The feed address must start with http:// or https://.");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "text/csv, application/xml")
                    .GET()
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new BusinessRuleException("The feed could not be read.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessRuleException("The feed could not be read.");
        }
        if (response.statusCode() >= 300) {
            throw new BusinessRuleException(
                    "The feed answered with status " + response.statusCode() + ".");
        }
        String body = response.body();
        try (InputStream retained =
                     new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            documentStorage.retainSourceFile(fileNameOf(target), retained);
        } catch (java.io.IOException e) {
            throw new BusinessRuleException("The feed could not be read.");
        }
        return importPatients(body, dryRun);
    }

    private static String fileNameOf(String target) {
        String path = target;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.isBlank() ? "roster.csv" : name;
    }

    /** Keeps the operator's original file alongside the batch it produced. */
    @RolesAllowed(Role.CLINIC_ADMIN_ROLE)
    public String retainImportSource(String originalFilename, InputStream data) {
        accessGuard.requireClinicAdmin();
        return documentStorage.retainSourceFile(originalFilename, data);
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            throw new BusinessRuleException("date_of_birth is required (YYYY-MM-DD).");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("date_of_birth must be formatted YYYY-MM-DD.");
        }
    }

    private Provider findProviderByNpi(String npi) {
        if (npi == null) {
            return null;
        }
        return em.createQuery("SELECT p FROM Provider p WHERE p.npi = :npi AND p.active = true",
                        Provider.class)
                .setParameter("npi", npi.trim())
                .getResultStream().findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "No active provider with NPI " + npi.trim() + "."));
    }

    /** Outcome of one import run. */
    public static class ImportResult implements Serializable {

        private static final long serialVersionUID = 1L;

        private int imported;
        private int skipped;
        private final List<String> errors = new ArrayList<>();

        public int getImported() {
            return imported;
        }

        public int getSkipped() {
            return skipped;
        }

        public List<String> getErrors() {
            return errors;
        }

        public boolean isClean() {
            return errors.isEmpty();
        }
    }
}
