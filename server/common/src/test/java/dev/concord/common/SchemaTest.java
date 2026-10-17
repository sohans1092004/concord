package dev.concord.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SchemaTest {
    /** schema.sql must be exactly the SQL block of SPEC §5.1, so the two never drift. */
    @Test
    void schemaIsExactlyTheSpec() throws Exception {
        String spec = Files.readString(Path.of("../../docs/SPEC.md"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("### 5\\.1 Postgres schema\\n\\n```sql\\n(.*?)```", Pattern.DOTALL).matcher(spec);
        assertTrue(m.find(), "SPEC §5.1 SQL block not found");
        String sql;
        try (InputStream in = Migrate.class.getResourceAsStream("/schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.endsWith(m.group(1)), "schema.sql differs from SPEC §5.1");
    }

    @Test
    void devSecretAndShortSecretsAreRefusedOutsideDev() {
        assertThrows(IllegalStateException.class, () -> Config.from(Map.of("CONCORD_ENV", "prod")).check());
        assertThrows(IllegalStateException.class, () -> Config.from(Map.of("CONCORD_ENV", "staging")).check());
        String db = "a-strong-database-password";
        assertThrows(IllegalStateException.class, () -> Config.from(Map.of("CONCORD_ENV", "prod", "CONCORD_INTERNAL_SECRET", "y".repeat(31), "CONCORD_DB_PASSWORD", db)).check());
        assertThrows(IllegalStateException.class,
                () -> Config.from(Map.of("CONCORD_ENV", "prod", "CONCORD_INTERNAL_SECRET", Config.DEV_INTERNAL_SECRET, "CONCORD_DB_PASSWORD", db)).check());
        assertThrows(IllegalStateException.class, () -> Config.from(Map.of("CONCORD_ENV", "prod", "CONCORD_INTERNAL_SECRET", "y".repeat(32))).check(),
                "the dev database password is refused");
        Config.from(Map.of("CONCORD_ENV", "prod", "CONCORD_INTERNAL_SECRET", "y".repeat(32), "CONCORD_DB_PASSWORD", db)).check();
        assertEquals(Config.DEV_INTERNAL_SECRET, Config.from(Map.of("CONCORD_ENV", "dev")).check().internalSecret());
        assertEquals("", Config.from(Map.of("CONCORD_ENV", "dev")).inviteCode(), "sign-up is open unless a code is set");
    }

    /** Phase 4a: a production gateway refuses every dev setting. */
    @Test
    void aProductionGatewayRefusesEveryDevSetting() {
        java.util.Map<String, String> good = new java.util.HashMap<>(Map.of("CONCORD_ENV", "prod", "CONCORD_INTERNAL_SECRET", "y".repeat(32),
                "CONCORD_DB_PASSWORD", "a-strong-database-password", "CONCORD_PUBLIC_ORIGIN", "https://concord-x.duckdns.org",
                "CONCORD_ALLOWED_ORIGINS", "https://concord-x.duckdns.org", "CONCORD_INVITE_CODE", "twelve-chars-x", "CONCORD_TRUSTED_PROXY", "caddy"));
        Config.from(good).checkPublic();
        for (String[] bad : new String[][] {
                {"CONCORD_PUBLIC_ORIGIN", "http://concord-x.duckdns.org"},
                {"CONCORD_PUBLIC_ORIGIN", "https://localhost"},
                {"CONCORD_ALLOWED_ORIGINS", "https://concord-x.duckdns.org,http://localhost:5173"},
                {"CONCORD_COOKIE_SECURE", "false"},
                {"CONCORD_INVITE_CODE", ""},
                {"CONCORD_SIGNIN_IP_BURST", "1000"},
                {"CONCORD_TRUSTED_PROXY", ""}}) {
            java.util.Map<String, String> env = new java.util.HashMap<>(good);
            env.put(bad[0], bad[1]);
            assertThrows(IllegalStateException.class, () -> Config.from(env).checkPublic(), bad[0] + "=" + bad[1] + " must be refused");
        }
    }

    @Test
    void passwordsAreArgon2idAndVerify() {
        String h = Passwords.hash("correct horse");
        assertTrue(h.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), h);
        assertTrue(Passwords.verify("correct horse", h));
        assertTrue(!Passwords.verify("correct horsf", h));
        assertTrue(!Passwords.hash("correct horse").equals(h), "salted");
        assertTrue(!Passwords.verify("x", "dev-no-login"), "a non-PHC hash never verifies");
    }

    @Test
    void redactorRemovesEverySecretShape() {
        String line = "GET /ws?ticket=abc_DEF-123&x=1 Cookie: concord_session=s3cr3t; Authorization: Bearer tok-en \"access_token=zzz\"";
        String r = RedactingJsonEncoder.redact(line);
        for (String secret : new String[] {"abc_DEF-123", "s3cr3t", "tok-en", "zzz"}) assertTrue(!r.contains(secret), r);
        assertTrue(r.contains("ticket=REDACTED&x=1"), r);
    }

    @Test
    void rendezvousIsDeterministicAndSpreadsBoards() {
        var live = List.of(new Rendezvous.Instance("s1", "a"), new Rendezvous.Instance("s2", "b"), new Rendezvous.Instance("s3", "c"));
        int[] firsts = new int[3];
        for (int i = 0; i < 3_000; i++) {
            String board = java.util.UUID.nameUUIDFromBytes(("b" + i).getBytes(StandardCharsets.UTF_8)).toString();
            var order = Rendezvous.order(board, live);
            assertEquals(order, Rendezvous.order(board, List.of(live.get(2), live.get(0), live.get(1))));
            firsts[Integer.parseInt(order.get(0).id().substring(1)) - 1]++;
            // Removing an instance never reorders the others (only its boards move).
            var without = Rendezvous.order(board, List.of(live.get(0), live.get(1)));
            assertEquals(order.stream().filter(x -> !x.id().equals("s3")).toList(), without);
        }
        for (int n : firsts) assertTrue(n > 800 && n < 1_200, "uneven spread: " + java.util.Arrays.toString(firsts));
    }
}
