package io.cpiwb.xml;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@code xsl:include}, {@code xsl:import}, {@code document()} and XSD includes inside a
 * project, like resources inside an iFlow. Lookup order: unsaved editor buffers ({@code resources}),
 * then files below {@code baseDir}. Everything else (network, files outside the project) is blocked,
 * so a run never reaches outside the project folder.
 */
public final class ProjectResolver implements URIResolver, LSResourceResolver {

    /** Scheme used for documents that exist only in memory (no project folder). */
    public static final String MEMORY_BASE = "cpiwb:///";

    private final Path baseDir;
    private final Map<String, String> resources = new LinkedHashMap<>();
    /** Files read from disk with their modification time, for cache validation. */
    private final Map<Path, Long> filesRead = new LinkedHashMap<>();

    /**
     * @param baseDir   project folder, may be null
     * @param resources relative path (e.g. {@code mappings/common.xsl}) to content
     */
    public ProjectResolver(Path baseDir, Map<String, String> resources) {
        this.baseDir = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
        if (resources != null) {
            resources.forEach((k, v) -> this.resources.put(systemIdFor(k), v));
        }
    }

    /** System id for a project-relative path. */
    public String systemIdFor(String relativePath) {
        String rel = relativePath.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (baseDir != null) {
            return baseDir.resolve(rel).normalize().toUri().toString();
        }
        return URI.create(MEMORY_BASE).resolve(encodePath(rel)).toString();
    }

    private static String encodePath(String rel) {
        return rel.replace(" ", "%20");
    }

    public Map<Path, Long> getFilesRead() {
        return filesRead;
    }

    /** Returns the content for an absolute system id, or throws if it is outside the project. */
    public String load(String systemId) throws TransformerException {
        String normalized = normalize(systemId);
        String mem = resources.get(normalized);
        if (mem != null) {
            return mem;
        }
        URI uri = URI.create(normalized);
        if ("file".equals(uri.getScheme()) && baseDir != null) {
            Path p = Paths.get(uri).normalize();
            if (!p.startsWith(baseDir)) {
                throw new TransformerException("Access outside the project folder is blocked: " + p);
            }
            try {
                filesRead.put(p, Files.getLastModifiedTime(p).toMillis());
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new TransformerException("Cannot read " + p + ": " + e.getMessage());
            }
        }
        if ("file".equals(uri.getScheme())) {
            throw new TransformerException("No project folder: cannot load " + normalized
                    + ". Open a project or add the file as a resource.");
        }
        if (normalized.startsWith(MEMORY_BASE)) {
            throw new TransformerException("Resource not found: " + normalized.substring(MEMORY_BASE.length()));
        }
        throw new TransformerException("Access to '" + normalized + "' is blocked (the simulator works offline)");
    }

    private static String normalize(String systemId) {
        try {
            URI u = URI.create(systemId.replace(" ", "%20")).normalize();
            if ("file".equals(u.getScheme())) {
                return Paths.get(u).normalize().toUri().toString();
            }
            return u.toString();
        } catch (IllegalArgumentException e) {
            return systemId;
        }
    }

    public String resolveAgainst(String href, String base) {
        URI b = URI.create((base == null || base.isEmpty() ? (baseDir != null ? baseDir.toUri().toString() : MEMORY_BASE) : base)
                .replace(" ", "%20"));
        return b.resolve(href.replace(" ", "%20")).toString();
    }

    // ---------------------------------------------------------------- javax.xml.transform

    @Override
    public Source resolve(String href, String base) throws TransformerException {
        String abs = resolveAgainst(href, base);
        String content = load(abs);
        StreamSource s = new StreamSource(new StringReader(content));
        s.setSystemId(abs);
        return s;
    }

    // ---------------------------------------------------------------- XSD includes/imports

    @Override
    public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
        if (systemId == null) {
            return null;
        }
        String abs = resolveAgainst(systemId, baseURI);
        try {
            return new StringInput(abs, publicId, load(abs));
        } catch (TransformerException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static final class StringInput implements LSInput {
        private String systemId;
        private String publicId;
        private String data;

        StringInput(String systemId, String publicId, String data) {
            this.systemId = systemId;
            this.publicId = publicId;
            this.data = data;
        }

        @Override public Reader getCharacterStream() { return new StringReader(data); }
        @Override public void setCharacterStream(Reader r) { }
        @Override public InputStream getByteStream() { return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)); }
        @Override public void setByteStream(InputStream b) { }
        @Override public String getStringData() { return data; }
        @Override public void setStringData(String s) { data = s; }
        @Override public String getSystemId() { return systemId; }
        @Override public void setSystemId(String s) { systemId = s; }
        @Override public String getPublicId() { return publicId; }
        @Override public void setPublicId(String p) { publicId = p; }
        @Override public String getBaseURI() { return systemId; }
        @Override public void setBaseURI(String b) { }
        @Override public String getEncoding() { return null; }
        @Override public void setEncoding(String e) { }
        @Override public boolean getCertifiedText() { return false; }
        @Override public void setCertifiedText(boolean c) { }
    }
}
