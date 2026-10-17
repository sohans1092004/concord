package dev.concord.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Config;
import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import dev.concord.session.Harness;
import dev.concord.session.Infra;
import dev.concord.session.Ops;
import dev.concord.session.SessionMain;
import dev.concord.session.Store;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** SPEC §2.1 REST API and §9.4 sign-in, sessions and WebSocket tickets, against a real gateway. */
class ApiTest {
    static SessionMain s;
    static GatewayMain g;

    @BeforeAll
    static void up() {
        s = Harness.start("api-s-" + Ops.uuid(), Store::new, false);
        g = new GatewayMain(Config.from(Infra.gatewayEnv()));
    }

    @AfterAll
    static void down() {
        if (g != null) g.close(); // null when the gateway failed to start: still stop the session
        if (s != null) s.close();
    }

    /** SPEC §9.4: a ticket opens one WebSocket, once, within 30s. Real time, no clock tricks. */
    @Test
    void aTicketWorksOnceAndOnlyWithin30Seconds() throws Exception {
        Rest u = new Rest(g.port());
        u.signUp(Rest.email(), "Ticket Tester");
        String once = u.ticket(), late = u.ticket(), justInTime = u.ticket();
        long issued = System.currentTimeMillis();

        try (WsClient c = new WsClient(g.port(), once, null)) {
            assertTrue(c.closed.getNow(null) == null, "the first use opens");
        }
        assertThrows(Exception.class, () -> new WsClient(g.port(), once, null), "a used ticket is refused");
        GatewayMain other = new GatewayMain(Config.from(Infra.gatewayEnv()));
        try {
            assertThrows(Exception.class, () -> new WsClient(other.port(), once, null), "and refused on every gateway");
        } finally {
            other.close();
        }

        Thread.sleep(Math.max(0, issued + 28_500 - System.currentTimeMillis()));
        try (WsClient c = new WsClient(g.port(), justInTime, null)) {
            assertTrue(System.currentTimeMillis() - issued < 30_000, "used before 30s");
            assertTrue(c.closed.getNow(null) == null);
        }
        Thread.sleep(Math.max(0, issued + 31_000 - System.currentTimeMillis()));
        assertThrows(Exception.class, () -> new WsClient(g.port(), late, null), "a ticket older than 30s is refused");
    }

    @Test
    void signUpSignInAndTheCookie() throws Exception {
        String email = Rest.email();
        Rest a = new Rest(g.port());
        Rest.Reply up = a.post("/api/auth/sign-up", Msg.obj("email", email.toUpperCase(), "name", "  Ada  ", "password", "correct horse battery"));
        assertEquals(201, up.status());
        assertEquals(email, up.body().get("email"), "emails are stored lowercased");
        assertEquals("Ada", up.body().get("name"));
        String setCookie = up.raw().headers().firstValue("Set-Cookie").orElseThrow();
        for (String flag : List.of("HttpOnly", "SameSite=Strict", "Secure", "Path=/")) assertTrue(setCookie.contains(flag), setCookie);
        assertEquals("Ada", a.get("/api/me").body().get("name"));

        assertEquals(409, new Rest(g.port()).post("/api/auth/sign-up", Msg.obj("email", email, "name", "B", "password", "another password")).status());
        assertEquals(400, new Rest(g.port()).post("/api/auth/sign-up", Msg.obj("email", Rest.email(), "name", "B", "password", "short")).status());
        assertEquals(400, new Rest(g.port()).post("/api/auth/sign-up", Msg.obj("email", "not-an-email", "name", "B", "password", "long enough!")).status());
        Rest b = new Rest(g.port());
        assertEquals(401, b.post("/api/auth/sign-in", Msg.obj("email", email, "password", "wrong password")).status());
        assertEquals(401, b.post("/api/auth/sign-in", Msg.obj("email", Rest.email(), "password", "correct horse battery")).status());
        assertEquals(200, b.post("/api/auth/sign-in", Msg.obj("email", email, "password", "correct horse battery")).status());
        assertEquals(200, b.get("/api/me").status());
        assertEquals(200, b.post("/api/auth/sign-out", Msg.obj()).status());
        assertNull(b.cookie);
        assertEquals(401, b.get("/api/me").status());
        assertEquals(401, new Rest(g.port()).get("/api/boards").status());
    }

    @Test
    void signInIsRateLimitedPerEmail() throws Exception {
        String email = Rest.email();
        new Rest(g.port()).signUp(email, "Limited");
        int refused = 0;
        for (int i = 0; i < 7; i++) {
            int st = new Rest(g.port()).post("/api/auth/sign-in", Msg.obj("email", email, "password", "wrong password")).status();
            if (st == 429) refused++;
        }
        assertEquals(2, refused, "5 attempts, then 1 a minute");
    }

    /** Every state-changing call checks Origin; with the cookie and no Origin it is refused. */
    @Test
    void stateChangingCallsRequireAnAllowedOrigin() throws Exception {
        Rest u = new Rest(g.port());
        u.signUp(Rest.email(), "Origin Tester");
        u.origin = "http://evil.test";
        assertEquals(403, u.post("/api/boards", Msg.obj("title", "x")).status());
        assertEquals(403, u.post("/api/ws-ticket", Msg.obj()).status());
        u.origin = null;
        assertEquals(403, u.post("/api/boards", Msg.obj("title", "x")).status(), "cookie without Origin");
        assertEquals(200, u.get("/api/boards").status(), "reads need no Origin");
        Rest script = new Rest(g.port());
        script.origin = null;
        script.bearer = u.cookie;
        assertEquals(201, script.post("/api/boards", Msg.obj("title", "from a script")).status(), "Bearer without Origin: no cookie, no CSRF");
        script.origin = "http://evil.test";
        assertEquals(403, script.post("/api/boards", Msg.obj("title", "x")).status());
        u.origin = "http://allowed.test";
        assertEquals(201, u.post("/api/boards", Msg.obj("title", "ok")).status());
        assertEquals(403, new Rest(g.port()) {{ origin = "http://evil.test"; }}.post("/api/auth/sign-in", Msg.obj("email", "a@b.cd", "password", "whatever1")).status(),
                "sign-in too (login CSRF)");
    }

    @Test
    void changingThePasswordEndsTheUsersOtherSessions() throws Exception {
        String email = Rest.email();
        Rest laptop = new Rest(g.port());
        laptop.signUp(email, "Two Devices");
        Rest phone = new Rest(g.port());
        assertEquals(200, phone.post("/api/auth/sign-in", Msg.obj("email", email, "password", "correct horse battery")).status());
        assertEquals(403, laptop.post("/api/auth/password", Msg.obj("current", "wrong one", "password", "a new password")).status());
        assertEquals(200, laptop.post("/api/auth/password", Msg.obj("current", "correct horse battery", "password", "a new password")).status());
        assertEquals(401, phone.get("/api/me").status(), "the other session is revoked");
        assertEquals(200, laptop.get("/api/me").status(), "the session that changed it stays");
        assertEquals(401, new Rest(g.port()).post("/api/auth/sign-in", Msg.obj("email", email, "password", "correct horse battery")).status());
        assertEquals(200, new Rest(g.port()).post("/api/auth/sign-in", Msg.obj("email", email, "password", "a new password")).status());
    }

    /** Amendment 23: X-Forwarded-For counts only from the trusted proxy's address. */
    @Test
    void forwardedForIsBelievedOnlyFromTheTrustedProxy() throws Exception {
        // Not from the proxy: a forged header is ignored, so every attempt counts against our own address.
        Map<String, String> env = Infra.gatewayEnv();
        env.put("CONCORD_SIGNIN_IP_BURST", "3");
        env.put("CONCORD_TRUSTED_PROXY", "192.0.2.1"); // TEST-NET-1: never this test's address
        try (GatewayMain gw = new GatewayMain(Config.from(env))) {
            int refused = 0;
            for (int i = 0; i < 5; i++) {
                Rest r = new Rest(gw.port());
                r.headers.put("X-Forwarded-For", "203.0.113." + i); // a different "client" every time
                if (r.post("/api/auth/sign-in", Msg.obj("email", Rest.email(), "password", "whatever1")).status() == 429) refused++;
            }
            assertEquals(2, refused, "a forged X-Forwarded-For from a non-proxy must not dodge the per-IP limit");
        }
        // From the proxy (here, this test's own address): each forwarded client has its own bucket.
        env = Infra.gatewayEnv();
        env.put("CONCORD_SIGNIN_IP_BURST", "3");
        env.put("CONCORD_TRUSTED_PROXY", "localhost");
        try (GatewayMain gw = new GatewayMain(Config.from(env))) {
            for (int i = 0; i < 5; i++) {
                Rest r = new Rest(gw.port());
                r.headers.put("X-Forwarded-For", "198.51.100.7, 203.0.113." + i); // the proxy appends the real client last
                assertEquals(401, r.post("/api/auth/sign-in", Msg.obj("email", Rest.email(), "password", "whatever1")).status());
            }
            int refused = 0;
            for (int i = 0; i < 4; i++) {
                Rest r = new Rest(gw.port());
                r.headers.put("X-Forwarded-For", "203.0.113.99");
                if (r.post("/api/auth/sign-in", Msg.obj("email", Rest.email(), "password", "whatever1")).status() == 429) refused++;
            }
            assertEquals(1, refused, "the same forwarded client is limited");
        }
    }

    @Test
    void anInviteCodeGatesSignUpWhenSet() throws Exception {
        Map<String, String> env = Infra.gatewayEnv();
        env.put("CONCORD_INVITE_CODE", "club-2026");
        try (GatewayMain gated = new GatewayMain(Config.from(env))) {
            Rest r = new Rest(gated.port());
            assertEquals(403, r.post("/api/auth/sign-up", Msg.obj("email", Rest.email(), "name", "N", "password", "long enough!")).status());
            assertEquals(403, r.post("/api/auth/sign-up", Msg.obj("email", Rest.email(), "name", "N", "password", "long enough!", "invite_code", "nope")).status());
            assertEquals(201, r.post("/api/auth/sign-up", Msg.obj("email", Rest.email(), "name", "N", "password", "long enough!", "invite_code", "club-2026")).status());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void boardsMembersAndRoles() throws Exception {
        Rest owner = new Rest(g.port()), editor = new Rest(g.port()), stranger = new Rest(g.port());
        owner.signUp(Rest.email(), "Owner");
        String editorEmail = Rest.email();
        String editorId = (String) editor.signUp(editorEmail, "Editor").get("id");
        stranger.signUp(Rest.email(), "Stranger");
        String board = (String) owner.post("/api/boards", Msg.obj("title", "Club board")).body().get("id");
        assertEquals(List.of(Msg.obj("id", board, "title", "Club board", "role", "owner")), owner.get("/api/boards").body().get("boards"));

        var heard = new LinkedBlockingQueue<String>();
        try (var redis = Infra.redis(); var sub = redis.pubSub()) {
            sub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    heard.add(message);
                }
            });
            sub.sync().subscribe(RedisKeys.MEMBERSHIP_CHANNEL);
            assertEquals(404, owner.post("/api/boards/" + board + "/members", Msg.obj("email", Rest.email(), "role", "editor")).status(), "no such user");
            assertEquals(201, owner.post("/api/boards/" + board + "/members", Msg.obj("email", editorEmail.toUpperCase(), "role", "editor")).status());
            assertEquals(board, heard.poll(5, TimeUnit.SECONDS), "SPEC §5.2: owners clear their membership cache");
            assertEquals(409, owner.post("/api/boards/" + board + "/members", Msg.obj("email", editorEmail, "role", "viewer")).status());
        }
        var members = (List<Map<?, ?>>) editor.get("/api/boards/" + board + "/members").body().get("members");
        assertEquals(2, members.size());
        assertEquals(403, editor.post("/api/boards/" + board + "/members", Msg.obj("email", Rest.email(), "role", "editor")).status());
        assertEquals(403, editor.send("PATCH", "/api/boards/" + board + "/members/" + editorId, Msg.obj("role", "owner")).status(), "no self-promotion");
        assertEquals(404, stranger.get("/api/boards/" + board + "/members").status(), "a non-member cannot tell the board exists");
        assertEquals(404, stranger.get("/api/boards/" + board + "/history").status());
        assertEquals(404, stranger.get("/api/boards/" + UUID.randomUUID() + "/members").status());

        String ownerId = (String) owner.get("/api/me").body().get("id");
        assertEquals(409, owner.send("PATCH", "/api/boards/" + board + "/members/" + ownerId, Msg.obj("role", "editor")).status(), "the last owner stays");
        assertEquals(409, owner.send("DELETE", "/api/boards/" + board + "/members/" + ownerId, null).status());
        assertEquals(200, owner.send("PATCH", "/api/boards/" + board + "/members/" + editorId, Msg.obj("role", "owner")).status());
        assertEquals(200, owner.send("PATCH", "/api/boards/" + board + "/members/" + ownerId, Msg.obj("role", "viewer")).status(), "now another owner exists");
        assertEquals(403, owner.post("/api/boards/" + board + "/members", Msg.obj("email", Rest.email(), "role", "editor")).status(), "a viewer now");
        assertEquals(200, owner.send("DELETE", "/api/boards/" + board + "/members/" + ownerId, null).status(), "a member may leave");
        assertEquals(List.of(), owner.get("/api/boards").body().get("boards"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void historyIsNewestFirstPagedAndLimitedTo30Days() throws Exception {
        Rest u = new Rest(g.port());
        u.signUp(Rest.email(), "Historian");
        String board = (String) u.post("/api/boards", Msg.obj("title", "H")).body().get("id");
        Ops ops = new Ops(board);
        String list = Ops.uuid();
        try (WsClient c = new WsClient(g.port(), u.ticket(), null)) {
            assertEquals("JOINED", c.join(board, null).get("type"));
            assertEquals("OP_ACK", c.op(ops.addList(list, "a0", "L")).get("type"));
            for (int i = 0; i < 4; i++) assertEquals("OP_ACK", c.op(ops.setField(list, "title", "t" + i)).get("type"));
        }
        var page1 = u.get("/api/boards/" + board + "/history?limit=3").body();
        var entries = (List<Map<?, ?>>) page1.get("entries");
        assertEquals(List.of(5L, 4L, 3L), entries.stream().map(e -> e.get("version")).toList());
        assertEquals("Historian", entries.get(0).get("user_name"));
        assertEquals("SET_FIELD", entries.get(0).get("type"));
        assertEquals("t3", ((Map<?, ?>) entries.get(0).get("op")).get("value"));
        assertEquals(3L, page1.get("next_before_version"));
        var page2 = u.get("/api/boards/" + board + "/history?limit=3&before_version=3").body();
        assertEquals(List.of(2L, 1L), ((List<Map<?, ?>>) page2.get("entries")).stream().map(e -> e.get("version")).toList());
        assertNull(page2.get("next_before_version"));

        dev.concord.session.Seed.count(Infra.db(), "WITH x AS (UPDATE board_ops SET committed_at = now() - interval '31 days' WHERE board_id = ? AND version = 1 RETURNING 1) SELECT count(*) FROM x",
                UUID.fromString(board));
        var all = (List<Map<?, ?>>) u.get("/api/boards/" + board + "/history").body().get("entries");
        assertEquals(List.of(5L, 4L, 3L, 2L), all.stream().map(e -> e.get("version")).toList(), "older than 30 days is not shown");
    }

    /** Phase 4a step 8: deleting an account removes its data, keeps shared boards whole. */
    @Test
    @SuppressWarnings("unchecked")
    void deletingAnAccountRemovesItsDataAndKeepsSharedBoards() throws Exception {
        Rest a = new Rest(g.port()), b = new Rest(g.port());
        String aId = (String) a.signUp(Rest.email(), "Leaving Lee").get("id");
        String bEmail = Rest.email();
        String bId = (String) b.signUp(bEmail, "Staying Sam").get("id");
        String solo = (String) a.post("/api/boards", Msg.obj("title", "Only mine")).body().get("id");
        String shared = (String) a.post("/api/boards", Msg.obj("title", "Ours")).body().get("id");
        assertEquals(201, a.post("/api/boards/" + shared + "/members", Msg.obj("email", bEmail, "role", "editor")).status());
        Ops ops = new Ops(shared);
        try (WsClient c = new WsClient(g.port(), a.ticket(), null)) {
            c.join(shared, null);
            assertEquals("OP_ACK", c.op(ops.addList(Ops.uuid(), "a0", "Lee's list")).get("type"));
        }
        assertEquals(201, a.post("/api/bug-reports", Msg.obj("board_id", shared, "description", "x", "diagnostics", Msg.obj())).status());
        String aSession = a.cookie;

        assertEquals(403, a.send("DELETE", "/api/me", Msg.obj("password", "not it")).status());
        Rest.Reply r = a.send("DELETE", "/api/me", Msg.obj("password", "correct horse battery"));
        assertEquals(200, r.status(), () -> String.valueOf(r.body()));
        assertEquals(List.of(solo), r.body().get("boards_deleted"));
        assertEquals(List.of(shared), r.body().get("boards_left"));

        var db = Infra.db();
        java.util.function.BiFunction<String, String, Long> rows = (sql, id) -> dev.concord.session.Seed.count(db, sql, UUID.fromString(id));
        assertEquals(0L, rows.apply("SELECT count(*) FROM boards WHERE id = ?", solo));
        assertEquals(1L, rows.apply("SELECT count(*) FROM boards WHERE id = ?", shared));
        assertEquals(1L, rows.apply("SELECT count(*) FROM board_ops WHERE board_id = ?", shared), "the shared board keeps its history");
        assertEquals(0L, rows.apply("SELECT count(*) FROM users WHERE id = ?", aId));
        assertEquals(0L, rows.apply("SELECT count(*) FROM sessions WHERE user_id = ?", aId));
        assertEquals(0L, rows.apply("SELECT count(*) FROM bug_reports WHERE user_id = ?", aId));
        Rest stale = new Rest(g.port());
        stale.bearer = aSession;
        assertEquals(401, stale.get("/api/me").status(), "the deleted user's session is gone");
        var members = (List<Map<?, ?>>) b.get("/api/boards/" + shared + "/members").body().get("members");
        assertEquals(List.of(Map.of("user_id", bId, "name", "Staying Sam", "email", bEmail, "role", "owner")), members, "Sam is now the owner");
        var history = (List<Map<?, ?>>) b.get("/api/boards/" + shared + "/history").body().get("entries");
        assertEquals(java.util.Arrays.asList((Object) null), history.stream().map(e -> e.get("user_name")).toList(), "the edit stays, without a name");
    }

    @Test
    void bugReportsAreStoredWithSecretsRedacted() throws Exception {
        Rest u = new Rest(g.port());
        u.signUp(Rest.email(), "Tester");
        String ticket = u.ticket();
        Map<String, Object> diagnostics = Msg.obj("log", List.of("connect ws://x/ws?ticket=" + ticket, "Authorization: Bearer " + u.cookie), "pending", 3L);
        Rest.Reply r = u.post("/api/bug-reports", Msg.obj("board_id", null, "description", "cards jumped; my session concord_session=" + u.cookie, "diagnostics", diagnostics));
        assertEquals(201, r.status(), () -> String.valueOf(r.body()));
        var row = dev.concord.gateway.Api.query(Infra.db(), "SELECT description, diagnostics::text AS d FROM bug_reports WHERE id = ?", UUID.fromString((String) r.body().get("id"))).get(0);
        String stored = row.get("description") + " " + row.get("d");
        assertFalse(stored.contains(ticket), stored);
        assertFalse(stored.contains(u.cookie), stored);
        assertTrue(stored.contains("ticket=REDACTED"), stored);
        assertTrue(stored.contains("\"pending\": 3"), stored);
    }
}
