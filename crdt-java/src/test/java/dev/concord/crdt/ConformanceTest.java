package dev.concord.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Runs every conformance/vectors/*.json exactly as SPEC §9.2.5 describes. */
class ConformanceTest {
    static final Path DIR = Path.of(System.getProperty("concord.vectors", "../conformance/vectors"));

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void vectorDirectoryExists() {
        assertTrue(Files.isDirectory(DIR), DIR.toAbsolutePath().toString());
    }

    @TestFactory
    Stream<DynamicTest> vectors() throws IOException {
        if (!Files.isDirectory(DIR)) return Stream.empty();
        try (Stream<Path> files = Files.list(DIR)) {
            List<Path> sorted = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            return sorted.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> run(Files.readString(p, StandardCharsets.UTF_8))));
        }
    }

    static void run(String text) {
        Map<?, ?> v = (Map<?, ?>) Json.parse(text);
        Board board = Board.decode(v.get("initial_state"));
        Map<String, Object> rejected = new LinkedHashMap<>();
        List<Op> accepted = new ArrayList<>();
        for (Object raw : (List<?>) v.get("ops")) {
            Op.Check c = Op.checkShape(raw);
            Op op = c.ok() ? Op.parse(raw) : null;
            if (c.ok() && v.containsKey("server_time_ms")) c = Op.checkClock(op, (Long) v.get("server_time_ms"));
            if (!c.ok()) rejected.put((String) ((Map<?, ?>) raw).get("op_id"), c.code());
            else accepted.add(op);
        }
        assertEquals(Canonical.encode(v.get("expected_rejected")), Canonical.encode(rejected), "rejected ops");
        int collisions = 0;
        for (Op o : accepted) collisions += board.apply(o);
        assertEquals(((Long) v.get("expected_collisions")).intValue(), collisions, "collisions");
        assertEquals(Canonical.encode(v.get("expected_view")), Canonical.encode(board.view()), "view");
        assertEquals(Canonical.encode(v.get("expected_state")), board.canonical(), "state");
        assertEquals(v.get("expected_state_sha256"), sha256(board.canonical()), "sha256");

        Board reversed = Board.decode(v.get("initial_state"));
        List<Op> rev = new ArrayList<>(accepted);
        Collections.reverse(rev);
        for (Op o : rev) reversed.apply(o);
        assertEquals(board.canonical(), reversed.canonical(), "reverse order");
    }

    /** SPEC §4.4 / Phase 1 exit: the core package has no networking or storage code. */
    @Test
    void corePackageImportsOnlyPureJdkApis() throws IOException {
        Pattern allowed = Pattern.compile("import (java\\.util\\.[A-Za-z.]+|java\\.text\\.Normalizer|java\\.nio\\.charset\\.StandardCharsets);");
        Path core = Path.of("src/main/java/dev/concord/crdt");
        try (Stream<Path> files = Files.list(core)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.startsWith("import ")) assertTrue(allowed.matcher(line).matches(), f.getFileName() + ": " + line);
                    assertTrue(!line.contains("java.net.") && !line.contains("java.io.") && !line.contains("java.nio.file") && !line.contains("java.sql"),
                            f.getFileName() + ": " + line);
                }
            }
        }
    }
}
