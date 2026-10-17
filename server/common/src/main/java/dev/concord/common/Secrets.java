package dev.concord.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Bearer secrets (SPEC §9.4): sign-in session tokens and WebSocket tickets. Each is 32 random
 * bytes; Postgres stores only its SHA-256, so a leaked table cannot be replayed.
 */
public final class Secrets {
    private Secrets() {}

    public static final long SESSION_DAYS = 30;
    public static final long TICKET_SECONDS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String random() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new sign-in session for {@code userId}; returns the token (only its hash is stored). */
    public static String createSession(DataSource ds, String userId) throws SQLException {
        String token = random();
        try (Connection c = ds.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, now() + make_interval(days => ?))")) {
            s.setString(1, sha256(token));
            s.setObject(2, UUID.fromString(userId));
            s.setInt(3, (int) SESSION_DAYS);
            s.executeUpdate();
        }
        return token;
    }
}
