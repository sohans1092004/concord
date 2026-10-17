package dev.concord.common;

import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Logback's JSON encoder with bearer secrets removed (SPEC §9.4). Concord's own code never logs
 * a secret (GatewayLogTest checks), but the WebSocket ticket travels in the URL's query string
 * and the session in a cookie or an Authorization header, and a library at DEBUG may log
 * request lines or headers. Every line is scrubbed of {@code ticket=...} and
 * {@code access_token=...} values, the session cookie's value and {@code Bearer} credentials.
 * Bug-report diagnostics go through {@link #redact} too.
 */
public class RedactingJsonEncoder extends JsonEncoder {
    static final Pattern SECRET = Pattern.compile("((?:ticket|access_token|concord_session)=|Bearer )[^&\\s\"';,\\\\]+");

    public static String redact(String line) {
        return SECRET.matcher(line).replaceAll("$1REDACTED");
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        return redact(new String(super.encode(event), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }
}
