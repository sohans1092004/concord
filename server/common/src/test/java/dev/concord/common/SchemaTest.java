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
        assertThrows(IllegalStateException.class,
                () -> Config.from(Map.of("CONCORD_ENV", "prod", "CONCORD_JWT_SECRET", Config.DEV_JWT_SECRET)).check());
        assertThrows(IllegalStateException.class, () -> Config.from(Map.of("CONCORD_ENV", "staging")).check());
        assertThrows(IllegalStateException.class, () -> Config.from(Map.of("CONCORD_ENV", "prod", "CONCORD_JWT_SECRET", "x".repeat(32))).check());
        assertThrows(IllegalStateException.class, () -> Config.from(Map.of("CONCORD_ENV", "prod", "CONCORD_JWT_SECRET", "x".repeat(32),
                "CONCORD_INTERNAL_SECRET", Config.DEV_INTERNAL_SECRET)).check());
        Config.from(Map.of("CONCORD_ENV", "prod", "CONCORD_JWT_SECRET", "x".repeat(32), "CONCORD_INTERNAL_SECRET", "y".repeat(32))).check();
        assertEquals(Config.DEV_JWT_SECRET, Config.from(Map.of("CONCORD_ENV", "dev")).check().jwtSecret());
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
