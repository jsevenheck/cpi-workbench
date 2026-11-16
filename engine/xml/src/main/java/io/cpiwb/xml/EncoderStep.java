package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Encoder step (SAP: "Define an Encoder", docs/plan.md F63). Simulated: Base64 Encode, the Base64 text of the
 * body's bytes, as in SAP's example. Whether CPI wraps long output into lines and ends it with a line break
 * is not documented (Q-E1); here it is one line without a line break, an assumption.
 * <p>
 * GZIP Compress, ZIP Compress and MIME Multipart Encode are refused: their result is binary, and a step's
 * result body is text so far (docs/plan.md W4).
 */
public final class EncoderStep {

    /** Camel's and MIME's line length for Base64; up to it, wrapped and unwrapped output differ by a line end at most. */
    private static final int MIME_LINE = 76;

    /**
     * Request: {@code {"scheme": "base64Encode", "input": {"body": "..."}}}. Result: {@code body}; headers and
     * properties stay as they are.
     */
    public Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        List<Object> diags = new ArrayList<>();
        Json.Obj result = Json.o();
        boolean ok = true;
        try {
            String scheme = Json.str(req, "scheme", "base64Encode");
            if (!"base64Encode".equals(scheme)) {
                throw new IllegalArgumentException("UnsupportedInSimulator: the encoding scheme '" + scheme + "'. Only Base64 Encode "
                        + "(base64Encode) is simulated; GZIP, ZIP and MIME Multipart results are binary (docs/plan.md W4).");
            }
            String body = Base64.getEncoder().encodeToString(SimExchange.fromJson(Json.obj(req, "input")).getBody());
            result.with("body", body);
            String assumption = "The Base64 text is one line without a line break at its end. Whether CPI wraps long output "
                    + "or ends it with a line break is not verified on a tenant (docs/plan.md Q-E1).";
            diags.add((body.length() > MIME_LINE ? Diagnostic.warning(assumption) : Diagnostic.info(assumption)).toJson());
        } catch (Exception e) {
            ok = false;
            diags.add(Diagnostic.error(XsltStep.message(e)).toJson());
        }
        return result.with("ok", ok).with("diagnostics", diags).with("engine", "JDK " + Runtime.version().feature() + " (Base64)")
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }
}
