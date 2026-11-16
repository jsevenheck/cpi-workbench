package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import io.cpiwb.core.TextFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Encoder and Decoder steps (SAP: "Define an Encoder", "Define a Decoder", docs/plan.md F63): Base64, GZIP and
 * ZIP in both directions. SAP documents the schemes and one short Base64 example, not their details. The
 * details here are those of Camel 3.14.7's data formats with their defaults (docs/plan.md F64), on the
 * assumption that CPI's steps are those data formats; no tenant has confirmed it (Q-E1):
 * <ul>
 * <li>Base64 Encode: lines of 76 characters, each ended by CR LF, the last one too (camel-base64 with
 * commons-codec's chunking). Base64 Decode: characters that are not Base64 are skipped ({@code -} and
 * {@code _} count as Base64), the text ends at the first {@code =}, and an incomplete last group gives what
 * bits it has (commons-codec's lenient decoding).</li>
 * <li>GZIP Compress: no name, time 0 and operating system 255 in the header (camel-zip-deflater with
 * commons-compress; JDK 21 writes the same header). The compressed bytes depend on the JVM's zlib.</li>
 * <li>ZIP Compress: one entry named after the header {@code CamelFileName} (its file name part), or after
 * the message id; {@code CamelFileName} becomes that name with {@code .zip}. ZIP Decompress: the first
 * entry becomes the body and its name {@code CamelFileName}; an archive with a second entry fails
 * (camel-zipfile).</li>
 * </ul>
 * MIME Multipart is refused. A result that is no text is given as {@code bodyBase64}.
 */
public final class EncoderStep {

    private static final List<String> ENCODERS = List.of("base64Encode", "gzipCompress", "zipCompress");
    private static final List<String> DECODERS = List.of("base64Decode", "gzipDecompress", "zipDecompress");
    /** Camel's header for a file's name. */
    private static final String FILE_NAME = "CamelFileName";
    /** shortcut: one limit for what a decoder may unpack, since a result travels as JSON; Camel's own for ZIP is 1 GiB. */
    static final int MAX_DECOMPRESSED = 128 * 1024 * 1024;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /**
     * Request: {@code {"scheme": "base64Encode"|"gzipCompress"|"zipCompress" (encoder) or "base64Decode"|
     * "gzipDecompress"|"zipDecompress" (decoder), "input": {"body": "..." or "bodyBase64": "...", "headers": [...]}}}.
     * Result: {@code output}, the message ({@code body}, or {@code bodyBase64} with {@code binary: true}), and at
     * the top {@code body} or {@code binary}, {@code bodySize} and {@code preview} (a hex dump of the first bytes).
     */
    public Map<String, Object> run(Map<String, Object> req, boolean decoder) {
        long t0 = System.nanoTime();
        List<Object> diags = new ArrayList<>();
        Json.Obj result = Json.o();
        boolean ok = true;
        try {
            String scheme = Json.str(req, "scheme", decoder ? "base64Decode" : "base64Encode");
            if (!(decoder ? DECODERS : ENCODERS).contains(scheme)) {
                throw new IllegalArgumentException("UnsupportedInSimulator: the " + (decoder ? "decoding" : "encoding") + " scheme '" + scheme
                        + "'. Simulated: " + String.join(", ", decoder ? DECODERS : ENCODERS) + "; MIME Multipart is not (docs/plan.md W4).");
            }
            SimExchange message = SimExchange.fromJson(Json.obj(req, "input"));
            byte[] in = message.getBody();
            byte[] out;
            switch (scheme) {
                case "base64Encode":
                    out = base64Encode(in);
                    break;
                case "base64Decode":
                    out = base64Decode(in, diags);
                    break;
                case "gzipCompress":
                    out = gzip(in);
                    break;
                case "gzipDecompress":
                    out = unpack(new GZIPInputStream(new ByteArrayInputStream(in)), "GZIP");
                    break;
                case "zipCompress":
                    out = zip(in, message, diags);
                    break;
                default:
                    out = unzip(in, message);
            }
            Charset declared = TextFiles.declaredCharset(out);
            message.setBody(out, declared != null ? declared : StandardCharsets.UTF_8);
            Json.Obj output = message.toJsonOrBinary();
            result.with("output", output).with("bodySize", out.length);
            if (output.get("body") != null) {
                result.with("body", output.get("body"));
            } else {
                result.with("binary", true).with("preview", hexDump(out, 256));
            }
            diags.add(Diagnostic.info("The details of this scheme are those of Camel 3.14.7's data format with its defaults. "
                    + "That CPI's step behaves like it is not verified on a tenant (docs/plan.md F64, Q-E1).").toJson());
        } catch (Exception e) {
            ok = false;
            diags.add(Diagnostic.error(XsltStep.message(e)).toJson());
        }
        return result.with("ok", ok).with("diagnostics", diags).with("engine", "JDK " + Runtime.version().feature() + " (Base64, GZIP, ZIP)")
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    /** MIME lines, and a line end after the last line as well: commons-codec ends every line it wrote. */
    static byte[] base64Encode(byte[] in) {
        byte[] lines = Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encode(in);
        if (lines.length == 0) {
            return lines;
        }
        byte[] out = java.util.Arrays.copyOf(lines, lines.length + 2);
        out[lines.length] = '\r';
        out[lines.length + 1] = '\n';
        return out;
    }

    /** Lenient, as commons-codec decodes by default: nothing is an error. */
    static byte[] base64Decode(byte[] in, List<Object> diags) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 3 / 4 + 3);
        int bits = 0;
        int count = 0;
        int skipped = 0;
        int i = 0;
        for (; i < in.length && in[i] != '='; i++) {
            int v = value(in[i]);
            if (v < 0) {
                skipped += in[i] == '\r' || in[i] == '\n' || in[i] == ' ' || in[i] == '\t' ? 0 : 1;
                continue;
            }
            bits = (bits << 6) | v;
            if (++count == 4) {
                out.write(bits >> 16);
                out.write(bits >> 8);
                out.write(bits);
                bits = 0;
                count = 0;
            }
        }
        if (count == 2) {
            out.write(bits >> 4);
        } else if (count == 3) {
            out.write(bits >> 10);
            out.write(bits >> 2);
        }
        int rest = 0;
        for (int j = i; j < in.length; j++) {
            rest += value(in[j]) >= 0 ? 1 : 0;
        }
        if (skipped > 0 || count == 1 || rest > 0) {
            diags.add(Diagnostic.warning("The input is not clean Base64: " + skipped + " character(s) outside the alphabet skipped"
                    + (count == 1 ? ", one character left over at the end" : "") + (rest > 0 ? ", " + rest + " character(s) after the first '=' ignored" : "")
                    + ". Camel's decoder does not report this, so neither does the step fail.").toJson());
        }
        return out.toByteArray();
    }

    /** A Base64 character's six bits, -1 for any other; the URL-safe '-' and '_' count, as in commons-codec's table. */
    private static int value(byte b) {
        return b == '-' ? 62 : b == '_' ? 63 : ALPHABET.indexOf(b);
    }

    private static byte[] gzip(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(in);
        }
        return out.toByteArray();
    }

    /** camel-zipfile's marshal: the entry's name, the entry, and the header with the archive's name. */
    private static byte[] zip(byte[] in, SimExchange message, List<Object> diags) throws IOException {
        Object header = message.getHeaders().get(FILE_NAME);
        String name;
        if (header == null) {
            // Camel takes the message id, with the characters a file name should not have replaced. The id here is made up.
            name = "ID-cpi-workbench-" + System.currentTimeMillis();
            diags.add(Diagnostic.info("No header " + FILE_NAME + ": Camel names the entry after the message id. The simulator has no "
                    + "message id and made one up (" + name + ").").toJson());
        } else {
            Path file = Paths.get(header.toString()).getFileName();
            if (file == null) {
                throw new IllegalArgumentException("The header " + FILE_NAME + " '" + header + "' has no file name.");
            }
            name = file.toString();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(in);
        }
        message.getHeaders().put(FILE_NAME, name + ".zip");
        return out.toByteArray();
    }

    /** camel-zipfile's unmarshal without an iterator: one entry, and its name in the header. */
    private static byte[] unzip(byte[] in, SimExchange message) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(in))) {
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) {
                throw new IllegalStateException("Unable to unzip the file, it may be corrupted. (Camel's message for a body that is no ZIP archive or an empty one.)");
            }
            message.getHeaders().put(FILE_NAME, entry.getName());
            byte[] out = unpack(zis, "ZIP");
            if (zis.getNextEntry() != null) {
                throw new IllegalStateException("Zip file has more than 1 entry. (Camel's message; the ZIP Splitter is the step for such an archive, and it is not simulated.)");
            }
            return out;
        }
    }

    private static byte[] unpack(InputStream in, String what) throws IOException {
        byte[] out = in.readNBytes(MAX_DECOMPRESSED + 1);
        if (out.length > MAX_DECOMPRESSED) {
            throw new IllegalArgumentException("UnsupportedInSimulator: the " + what + " content unpacks to more than "
                    + MAX_DECOMPRESSED / (1024 * 1024) + " MB.");
        }
        return out;
    }

    /** The first bytes as a hex dump: offset, sixteen bytes, and their printable ASCII characters. */
    static String hexDump(byte[] bytes, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(bytes.length, max);
        for (int row = 0; row < n; row += 16) {
            sb.append(String.format("%08x ", row));
            StringBuilder ascii = new StringBuilder();
            for (int i = row; i < row + 16; i++) {
                sb.append(i < n ? String.format(" %02x", bytes[i]) : "   ");
                if (i < n) {
                    ascii.append(bytes[i] >= 0x20 && bytes[i] < 0x7f ? (char) bytes[i] : '.');
                }
            }
            sb.append("  |").append(ascii).append("|\n");
        }
        if (bytes.length > n) {
            sb.append("… ").append(bytes.length - n).append(" more byte(s)\n");
        }
        return sb.toString();
    }
}
