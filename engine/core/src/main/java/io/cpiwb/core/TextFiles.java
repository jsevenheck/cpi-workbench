package io.cpiwb.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text and bytes of XML that names its own encoding. A message or file that starts with
 * {@code <?xml version="1.0" encoding="ISO-8859-1"?>} is read by an XML parser in that encoding (XML 1.0,
 * 4.3.3), so its bytes must be in it: text from an editor is encoded with the declared charset, and a file
 * is decoded with it. Everything else is UTF-8.
 */
public final class TextFiles {

    private static final Pattern DECLARATION = Pattern.compile(
            "^\\uFEFF?<\\?xml\\s+version\\s*=\\s*[\"'][^\"']*[\"']\\s+encoding\\s*=\\s*[\"']([A-Za-z][A-Za-z0-9._-]*)[\"']");

    private TextFiles() {
    }

    /** The charset an XML declaration at the start of {@code text} names; null without one or for an unknown name. */
    public static Charset declaredCharset(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = DECLARATION.matcher(text.length() > 200 ? text.substring(0, 200) : text);
        if (!m.find()) {
            return null;
        }
        try {
            return Charset.forName(m.group(1));
        } catch (IllegalArgumentException e) {
            return null; // the parser reports the unknown encoding
        }
    }

    /** As {@link #declaredCharset(String)} for bytes in an ASCII-compatible encoding or UTF-16 with a byte order mark. */
    public static Charset declaredCharset(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        Charset bom = utf16Bom(bytes);
        if (bom != null) {
            return bom;
        }
        return declaredCharset(new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1));
    }

    private static Charset utf16Bom(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16;
        }
        return null;
    }

    /** The bytes of a text: in the charset its XML declaration names, else UTF-8. */
    public static byte[] encode(String text) {
        Charset cs = declaredCharset(text);
        requireEncodable(text, cs, "The text");
        return text.getBytes(cs == null ? StandardCharsets.UTF_8 : cs);
    }

    /**
     * Fails when {@code text} has a character that {@code cs} (its declared encoding) cannot hold: encoding it
     * anyway would turn the character into "?" without a word.
     */
    public static void requireEncodable(String text, Charset cs, String what) {
        if (text == null || cs == null) {
            return;
        }
        java.nio.charset.CharsetEncoder enc = cs.newEncoder();
        if (enc.canEncode(text)) {
            return;
        }
        for (int i = 0; i < text.length(); ) {
            String ch = new String(Character.toChars(text.codePointAt(i)));
            if (!enc.canEncode(ch)) {
                long line = text.substring(0, i).chars().filter(c -> c == '\n').count() + 1;
                throw new IllegalArgumentException(what + " has a character that the encoding its XML declaration names ("
                        + cs.name() + ") cannot hold: '" + ch + "' in line " + line
                        + ". Write it as a character reference (&#x...;) or change the declaration.");
            }
            i += ch.length();
        }
    }

    /** The text of bytes: in the charset their XML declaration (or UTF-16 byte order mark) names, else UTF-8. */
    public static String decode(byte[] bytes, String what) {
        Charset cs = declaredCharset(bytes);
        if (cs != null) {
            return new String(bytes, cs);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(what + " is not UTF-8 text and names no encoding in an XML declaration "
                    + "(<?xml version=\"1.0\" encoding=\"...\"?>), so it cannot be read as text.");
        }
    }

    public static String read(Path file) throws IOException {
        return decode(Files.readAllBytes(file), file.getFileName().toString());
    }
}
