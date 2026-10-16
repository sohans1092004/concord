package dev.concord.common;

import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Logback's JSON encoder with access tokens removed (amendment 10). Concord's own code never
 * logs a token (GatewayLogTest checks), but the token travels in the WebSocket URL's query
 * string, and a library at DEBUG may log request lines. Every line is scrubbed of
 * {@code access_token=...} values and of anything shaped like a JWT.
 */
public class RedactingJsonEncoder extends JsonEncoder {
    static final Pattern QUERY = Pattern.compile("(access_token=)[^&\\s\"\\\\]+");
    static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    public static String redact(String line) {
        return JWT.matcher(QUERY.matcher(line).replaceAll("$1REDACTED")).replaceAll("REDACTED-JWT");
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        return redact(new String(super.encode(event), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }
}
