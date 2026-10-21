package com.clinic.storage;

import com.clinic.security.BusinessRuleException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the upload validation rules and the canonicalised-base-directory path
 * resolution (precision-baseline primitive #5).
 */
class DocumentStorageTest {

    @TempDir
    Path baseDir;

    private DocumentStorage storage;

    @BeforeEach
    void setUp() {
        System.setProperty("clinic.document.dir", baseDir.toString());
        storage = new DocumentStorage();
        storage.init();
    }

    @Test
    @DisplayName("a valid PDF is stored under an opaque server-generated name")
    void storesAValidPdf() {
        byte[] pdf = "%PDF-1.4\nhello\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        DocumentStorage.StoredFile stored = storage.store("Consent Form.pdf",
                "application/pdf", pdf.length, new ByteArrayInputStream(pdf));

        assertNotNull(stored.storedFilename());
        assertTrue(stored.storedFilename().endsWith(".pdf"));
        // The caller's file name is never used as a path segment.
        assertTrue(stored.storedFilename().matches("[0-9a-f-]{36}\\.pdf"));
        assertEquals(pdf.length, stored.size());
        assertEquals(64, stored.sha256().length());
        assertTrue(Files.exists(baseDir.resolve(stored.storedFilename())));
    }

    @Test
    void rejectsUnsupportedExtensionsAndContentTypes() {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(BusinessRuleException.class, () -> storage.store("evil.jsp",
                "application/pdf", payload.length, new ByteArrayInputStream(payload)));
        assertThrows(BusinessRuleException.class, () -> storage.store("evil.exe",
                "application/octet-stream", payload.length, new ByteArrayInputStream(payload)));
        assertThrows(BusinessRuleException.class, () -> storage.store("note.txt",
                "application/x-httpd-php", payload.length, new ByteArrayInputStream(payload)));
    }

    @Test
    void rejectsAnEmptyFileAndAFakePdf() {
        assertThrows(BusinessRuleException.class, () -> storage.store("empty.txt",
                "text/plain", 0, new ByteArrayInputStream(new byte[0])));
        byte[] notAPdf = "<html>nope</html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(BusinessRuleException.class, () -> storage.store("fake.pdf",
                "application/pdf", notAPdf.length, new ByteArrayInputStream(notAPdf)));
    }

    @Test
    @DisplayName("no reference can escape the canonicalised base directory")
    void resolutionIsConfinedToTheBaseDirectory() {
        assertThrows(BusinessRuleException.class,
                () -> storage.resolveInsideBase("../../etc/passwd"));
        assertThrows(BusinessRuleException.class,
                () -> storage.resolveInsideBase("..%2F..%2Fetc%2Fpasswd/../x"));
        assertThrows(BusinessRuleException.class,
                () -> storage.resolveInsideBase("/etc/passwd"));
        assertThrows(BusinessRuleException.class,
                () -> storage.resolveInsideBase("sub/dir/file.pdf"));
        assertThrows(BusinessRuleException.class,
                () -> storage.resolveInsideBase("..\\windows\\win.ini"));
        assertThrows(BusinessRuleException.class, () -> storage.resolveInsideBase(""));
        assertThrows(BusinessRuleException.class, () -> storage.resolveInsideBase(null));

        Path resolved = storage.resolveInsideBase("abc.pdf");
        assertTrue(resolved.startsWith(storage.getBaseDirectory()));
    }

    @Test
    void downloadNamesAreSanitised() {
        assertEquals("passwd", DocumentStorage.safeDownloadName("../../etc/passwd"));
        assertEquals("win.ini", DocumentStorage.safeDownloadName("..\\windows\\win.ini"));
        assertEquals("report_2024.pdf",
                DocumentStorage.safeDownloadName("report\"2024.pdf"));
        assertEquals("document", DocumentStorage.safeDownloadName(null));
        assertEquals("document", DocumentStorage.safeDownloadName(".."));
    }

    @Test
    void extensionParsingIgnoresDirectoryTricks() {
        assertEquals("pdf", DocumentStorage.extensionOf("../../x/report.PDF"));
        assertEquals("", DocumentStorage.extensionOf("noextension"));
        assertEquals("", DocumentStorage.extensionOf("trailing."));
    }
}
