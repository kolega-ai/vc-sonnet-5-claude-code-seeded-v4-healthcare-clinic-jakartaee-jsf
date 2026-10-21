package com.clinic.storage;

import com.clinic.security.BusinessRuleException;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local-disk storage for uploaded clinical documents. Only opaque,
 * server-generated file names are ever written to disk; the caller's original
 * file name is kept in Postgres as metadata and never used as a path segment.
 */
@ApplicationScoped
public class DocumentStorage {

    private static final Logger LOG = Logger.getLogger(DocumentStorage.class.getName());

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/webp",
            "text/plain",
            "text/csv");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "png", "jpg", "jpeg", "webp", "txt", "csv");

    /** Magic-number prefixes, keyed by the content type we will accept them for. */
    private static final List<byte[]> PDF_MAGIC = List.of("%PDF".getBytes(StandardCharsets.US_ASCII));

    private Path baseDirectory;

    @PostConstruct
    void init() {
        String configured = System.getenv("CLINIC_DOCUMENT_DIR");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("clinic.document.dir", "/var/clinic/documents");
        }
        try {
            Path candidate = Paths.get(configured);
            Files.createDirectories(candidate);
            // The canonical (symlink-resolved, absolute) base directory. Every
            // read and write is resolved against this and re-checked below.
            this.baseDirectory = candidate.toRealPath();
            LOG.log(Level.INFO, "Document storage base directory ready");
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to prepare document storage directory", e);
        }
    }

    public Path getBaseDirectory() {
        return baseDirectory;
    }

    public long getMaxBytes() {
        return MAX_BYTES;
    }

    /**
     * Validates and stores an upload.
     *
     * @return the opaque stored file name to persist alongside the metadata
     */
    public StoredFile store(String originalFilename, String declaredContentType,
                            long declaredSize, InputStream data) {
        String extension = extensionOf(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessRuleException(
                    "Unsupported file type. Allowed: PDF, PNG, JPG, WEBP, TXT, CSV.");
        }
        String contentType = declaredContentType == null
                ? "application/octet-stream" : declaredContentType.toLowerCase(Locale.ROOT).trim();
        int semi = contentType.indexOf(';');
        if (semi > 0) {
            contentType = contentType.substring(0, semi).trim();
        }
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessRuleException("Unsupported content type: " + contentType);
        }
        if (declaredSize > MAX_BYTES) {
            throw new BusinessRuleException("File is larger than the 10 MB limit.");
        }

        String storedName = UUID.randomUUID() + "." + extension;
        Path target = resolveInsideBase(storedName);
        Path temp = null;
        try {
            temp = Files.createTempFile(baseDirectory, "upload-", ".part");
            long written;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = data;
                 java.io.OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) {
                        throw new BusinessRuleException("File is larger than the 10 MB limit.");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                written = total;
            }
            if (written == 0) {
                throw new BusinessRuleException("The uploaded file is empty.");
            }
            if ("pdf".equals(extension) && !hasMagic(temp)) {
                throw new BusinessRuleException("The file does not look like a valid PDF.");
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
            return new StoredFile(storedName, contentType, written, hex(digest.digest()));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to store uploaded document", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    LOG.log(Level.FINE, "Could not remove temporary upload file");
                }
            }
        }
    }

    /**
     * Keeps the operator's original import file next to the documents it
     * produced, so a disputed batch can be re-read exactly as it arrived.
     *
     * @return the name the file was retained under
     */
    public String retainSourceFile(String originalFilename, InputStream data) {
        String name = originalFilename == null || originalFilename.isBlank()
                ? "roster" : originalFilename.trim();
        Path target = baseDirectory.resolve("imports").resolve(name);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to retain the import source file", e);
        }
        return name;
    }

    /** Opens a stored document for streaming to an authorised caller. */
    public InputStream open(String storedFilename) {
        Path path = resolveInsideBase(storedFilename);
        if (!Files.isRegularFile(path)) {
            throw new BusinessRuleException("The stored file is no longer available.");
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read stored document", e);
        }
    }

    public long sizeOf(String storedFilename) {
        try {
            return Files.size(resolveInsideBase(storedFilename));
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * Resolves a stored file name against the canonicalised base directory and
     * refuses anything that would escape it.
     *
     * <p>The name always comes from our own database, but the resolution is
     * still performed against the canonical base and re-verified so that no
     * value — however it got into the column — can address a path outside the
     * document root.</p>
     */
    Path resolveInsideBase(String storedFilename) {
        if (storedFilename == null || storedFilename.isBlank()) {
            throw new BusinessRuleException("Missing document reference.");
        }
        // Reject anything that is not a bare file name before touching the FS.
        if (storedFilename.contains("/") || storedFilename.contains("\\")
                || storedFilename.contains("..") || storedFilename.startsWith(".")) {
            throw new BusinessRuleException("Invalid document reference.");
        }
        Path resolved = baseDirectory.resolve(storedFilename).normalize().toAbsolutePath();
        if (!resolved.startsWith(baseDirectory)) {
            throw new BusinessRuleException("Invalid document reference.");
        }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // Re-check after symlink resolution.
                Path real = resolved.toRealPath();
                if (!real.startsWith(baseDirectory)) {
                    throw new BusinessRuleException("Invalid document reference.");
                }
                return real;
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to canonicalise document path", e);
            }
        }
        return resolved;
    }

    private static boolean hasMagic(Path file) throws IOException {
        byte[] head = new byte[8];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(head);
            if (read < 4) {
                return false;
            }
        }
        for (byte[] magic : PDF_MAGIC) {
            boolean match = true;
            for (int i = 0; i < magic.length; i++) {
                if (head[i] != magic[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return "";
        }
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Sanitises an original file name for use in a Content-Disposition header. */
    public static String safeDownloadName(String originalFilename) {
        String base = originalFilename == null ? "document" : originalFilename;
        base = base.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._\\- ]", "_").trim();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            base = "document";
        }
        return base.length() > 120 ? base.substring(0, 120) : base;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Result of a successful upload. */
    public record StoredFile(String storedFilename, String contentType, long size, String sha256) {
    }
}
