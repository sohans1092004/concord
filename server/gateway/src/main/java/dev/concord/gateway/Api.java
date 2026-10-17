package dev.concord.gateway;

import dev.concord.common.Config;
import dev.concord.common.Msg;
import dev.concord.common.Passwords;
import dev.concord.common.RedactingJsonEncoder;
import dev.concord.common.RedisKeys;
import dev.concord.common.Secrets;
import dev.concord.crdt.Hlc;
import dev.concord.crdt.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.lettuce.core.api.sync.RedisCommands;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The REST API (SPEC §2.1, §9.4), in the gateway process, over plain JDBC. People sign in with a
 * session cookie (HttpOnly, SameSite=Strict); scripts send the same session token as
 * {@code Authorization: Bearer}. Only the SHA-256 of a session or ticket is stored.
 *
 * <pre>
 *   POST   /api/auth/sign-up        {email, name, password, invite_code?}   sets the cookie
 *   POST   /api/auth/sign-in        {email, password}                       sets the cookie
 *   POST   /api/auth/sign-out
 *   POST   /api/auth/password       {current, password}   revokes the user's other sessions
 *   GET    /api/me
 *   POST   /api/ws-ticket           → {ticket}: single use, 30s, for the WebSocket URL
 *   GET    /api/boards              my boards and my role on each
 *   POST   /api/boards              {title} → {id}
 *   GET    /api/boards/{id}/members
 *   POST   /api/boards/{id}/members {email, role}   owners only
 *   PATCH  /api/boards/{id}/members/{user_id} {role}   owners only
 *   DELETE /api/boards/{id}/members/{user_id}   owners, or a member leaving
 *   GET    /api/boards/{id}/history?before_version=&amp;limit=   30 days, newest first
 *   POST   /api/bug-reports         {board_id?, description, diagnostics}
 * </pre>
 *
 * Every state-changing call must be JSON and must come from an allowed Origin; a request with
 * the session cookie and no Origin is refused, so no other site can drive these calls.
 */
final class Api {
    private static final Logger log = LoggerFactory.getLogger(Api.class);
    static final String COOKIE = "concord_session";
    static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{1,60}$");
    static final int HISTORY_DAYS = 30, HISTORY_DEFAULT = 50, HISTORY_MAX = 200;
    static final int MAX_DIAGNOSTICS = 256 * 1024;

    record User(String id, String email, String name, String sessionHash) {}

    private final DataSource db;
    private final RedisCommands<String, String> redis;
    private final Config config;
    private final LongSupplier clock;
    /** Sign-in limits, as Reliant's: per email 5 then 1/min, per IP 30 then 1 per 10s. In memory, like amendment 7. */
    private final Map<String, TokenBucket> limits = new ConcurrentHashMap<>();
    private final String dummyHash = Passwords.hash(Secrets.random());

    Api(Javalin app, DataSource db, RedisCommands<String, String> redis, Config config, LongSupplier clock) {
        this.db = db;
        this.redis = redis;
        this.config = config;
        this.clock = clock;
        app.before("/api/*", this::sameOrigin);
        app.exception(SQLException.class, (e, ctx) -> {
            log.warn("storage unavailable: {}", e.toString());
            json(ctx.status(503), Msg.obj("error", "storage unavailable, try again"));
        });
        app.exception(HttpResponseException.class, (e, ctx) -> json(ctx.status(e.getStatus()), Msg.obj("error", e.getMessage())));
        app.post("/api/auth/sign-up", this::signUp);
        app.post("/api/auth/sign-in", this::signIn);
        app.post("/api/auth/sign-out", this::signOut);
        app.post("/api/auth/password", this::changePassword);
        app.get("/api/me", ctx -> {
            User u = auth(ctx);
            json(ctx, Msg.obj("id", u.id(), "email", u.email(), "name", u.name()));
        });
        app.post("/api/ws-ticket", this::ticket);
        app.get("/api/boards", this::myBoards);
        app.post("/api/boards", this::createBoard);
        app.get("/api/boards/{board}/members", this::members);
        app.post("/api/boards/{board}/members", this::addMember);
        app.patch("/api/boards/{board}/members/{user}", this::changeRole);
        app.delete("/api/boards/{board}/members/{user}", this::removeMember);
        app.get("/api/boards/{board}/history", this::history);
        app.post("/api/bug-reports", this::bugReport);
    }

    // ---- guards ----------------------------------------------------------------------------

    /** State-changing calls: JSON only, from an allowed Origin; with the cookie, Origin is required. */
    private void sameOrigin(Context ctx) {
        String m = ctx.method().name();
        if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")) return;
        String origin = ctx.header("Origin");
        if (origin != null && !config.allowedOrigins().contains(origin)) throw new HttpResponseException(403, "origin not allowed");
        if (origin == null && ctx.cookie(COOKIE) != null) throw new HttpResponseException(403, "origin required");
        if (!m.equals("DELETE") && !String.valueOf(ctx.contentType()).startsWith("application/json"))
            throw new HttpResponseException(415, "JSON only");
    }

    private User auth(Context ctx) throws SQLException {
        String token = ctx.cookie(COOKIE);
        String header = ctx.header("Authorization");
        if (header != null && header.startsWith("Bearer ")) token = header.substring(7);
        if (token == null || token.isEmpty()) throw new HttpResponseException(401, "sign in first");
        String hash = Secrets.sha256(token);
        var rows = query("SELECT u.id, u.email, u.name FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > now()", hash);
        if (rows.isEmpty()) throw new HttpResponseException(401, "sign in first");
        var r = rows.get(0);
        return new User(r.get("id").toString(), (String) r.get("email"), (String) r.get("name"), hash);
    }

    /** The caller's role on the board, or 404 if not a member (a board's existence is not revealed). */
    private String role(String board, User u) throws SQLException {
        if (!Hlc.isUuid(board)) throw new HttpResponseException(404, "no such board");
        var rows = query("SELECT role FROM board_members WHERE board_id = ? AND user_id = ?", UUID.fromString(board), UUID.fromString(u.id()));
        if (rows.isEmpty()) throw new HttpResponseException(404, "no such board");
        return (String) rows.get(0).get("role");
    }

    private boolean allowed(String key, double perSecond, double burst) {
        if (limits.size() > 100_000) limits.clear(); // ponytail: crude bound; per-key expiry if it matters
        return limits.computeIfAbsent(key, k -> new TokenBucket(perSecond, burst, clock.getAsLong())).take(clock.getAsLong());
    }

    // ---- sign-in ---------------------------------------------------------------------------

    private void signUp(Context ctx) throws SQLException {
        Map<?, ?> b = body(ctx);
        if (!allowed("ip:" + ctx.ip(), 0.1, 30)) throw new HttpResponseException(429, "too many attempts, try again in a minute");
        if (!config.inviteCode().isEmpty() && !java.security.MessageDigest.isEqual(
                config.inviteCode().getBytes(java.nio.charset.StandardCharsets.UTF_8), str(b, "invite_code", 0, 200).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new HttpResponseException(403, "a valid invite code is required");
        String email = email(b);
        String name = name(b.get("name"));
        String password = password(b.get("password"));
        String id = UUID.randomUUID().toString();
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, ?, ?) ON CONFLICT (email) DO NOTHING")) {
            s.setObject(1, UUID.fromString(id));
            s.setString(2, email);
            s.setString(3, name);
            s.setString(4, Passwords.hash(password));
            if (s.executeUpdate() == 0) throw new HttpResponseException(409, "an account with this email exists");
        }
        startSession(ctx.status(201), id);
        log.atInfo().addKeyValue("user_id", id).log("signed up");
        json(ctx, Msg.obj("id", id, "email", email, "name", name));
    }

    private void signIn(Context ctx) throws SQLException {
        Map<?, ?> b = body(ctx);
        String email = String.valueOf(b.get("email")).trim().toLowerCase(java.util.Locale.ROOT);
        if (!allowed("email:" + email, 1 / 60.0, 5) || !allowed("ip:" + ctx.ip(), 0.1, 30))
            throw new HttpResponseException(429, "too many sign-in attempts, try again in a minute");
        var rows = query("SELECT id, name, password_hash FROM users WHERE email = ?", email);
        String hash = rows.isEmpty() ? dummyHash : (String) rows.get(0).get("password_hash");
        // An unknown email is checked against a dummy hash, so timing does not tell them apart.
        boolean ok = Passwords.verify(String.valueOf(b.get("password")), hash) && !rows.isEmpty();
        if (!ok) throw new HttpResponseException(401, "wrong email or password");
        String id = rows.get(0).get("id").toString();
        startSession(ctx, id);
        log.atInfo().addKeyValue("user_id", id).log("signed in");
        json(ctx, Msg.obj("id", id, "email", email, "name", rows.get(0).get("name")));
    }

    private void startSession(Context ctx, String userId) throws SQLException {
        String token = Secrets.createSession(db, userId);
        ctx.header("Set-Cookie", cookie(token, Secrets.SESSION_DAYS * 86_400));
        exec("DELETE FROM sessions WHERE user_id = ? AND expires_at <= now()", UUID.fromString(userId));
    }

    String cookie(String token, long maxAgeSeconds) {
        return COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + maxAgeSeconds + (config.cookieSecure() ? "; Secure" : "");
    }

    private void signOut(Context ctx) throws SQLException {
        User u = auth(ctx);
        exec("DELETE FROM sessions WHERE token_hash = ?", u.sessionHash());
        ctx.header("Set-Cookie", cookie("", 0));
        json(ctx, Msg.obj("signed_out", true));
    }

    /** A new password ends every other session of this user (a stolen cookie, a forgotten laptop). */
    private void changePassword(Context ctx) throws SQLException {
        User u = auth(ctx);
        Map<?, ?> b = body(ctx);
        String stored = (String) query("SELECT password_hash FROM users WHERE id = ?", UUID.fromString(u.id())).get(0).get("password_hash");
        if (!allowed("email:" + u.email(), 1 / 60.0, 5)) throw new HttpResponseException(429, "too many attempts, try again in a minute");
        if (!Passwords.verify(String.valueOf(b.get("current")), stored)) throw new HttpResponseException(403, "the current password is wrong");
        String fresh = password(b.get("password"));
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?");
                 PreparedStatement d = c.prepareStatement("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?")) {
                s.setString(1, Passwords.hash(fresh));
                s.setObject(2, UUID.fromString(u.id()));
                s.executeUpdate();
                d.setObject(1, UUID.fromString(u.id()));
                d.setString(2, u.sessionHash());
                d.executeUpdate();
            }
            c.commit();
        }
        log.atInfo().addKeyValue("user_id", u.id()).log("password changed; other sessions revoked");
        json(ctx, Msg.obj("changed", true));
    }

    /** SPEC §9.4: a single-use ticket valid for 30s, for the WebSocket URL (amendment 14). */
    private void ticket(Context ctx) throws SQLException {
        User u = auth(ctx);
        String ticket = Secrets.random();
        exec("INSERT INTO ws_tickets (token_hash, user_id, expires_at) VALUES (?, ?, now() + make_interval(secs => ?))",
                Secrets.sha256(ticket), UUID.fromString(u.id()), (double) Secrets.TICKET_SECONDS);
        json(ctx, Msg.obj("ticket", ticket, "expires_in_s", Secrets.TICKET_SECONDS));
    }

    /** Uses a ticket: deleted in the same statement that reads it, so it works once, on any gateway. */
    static String consumeTicket(DataSource db, String ticket) throws SQLException {
        if (ticket == null || ticket.isEmpty()) return null;
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(
                "DELETE FROM ws_tickets WHERE token_hash = ? AND expires_at > now() RETURNING user_id")) {
            s.setString(1, Secrets.sha256(ticket));
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getObject(1).toString() : null;
            }
        }
    }

    /** Expired tickets and sessions; every gateway runs this each minute (harmless if they overlap). */
    void cleanup() {
        try {
            exec("DELETE FROM ws_tickets WHERE expires_at <= now()");
            exec("DELETE FROM sessions WHERE expires_at <= now()");
        } catch (SQLException e) {
            log.warn("cleanup skipped: {}", e.toString());
        }
    }

    // ---- boards and members ----------------------------------------------------------------

    private void myBoards(Context ctx) throws SQLException {
        User u = auth(ctx);
        json(ctx, Msg.obj("boards", query("SELECT b.id, b.title, m.role FROM board_members m JOIN boards b ON b.id = m.board_id "
                + "WHERE m.user_id = ? ORDER BY b.created_at, b.id", UUID.fromString(u.id()))));
    }

    private void createBoard(Context ctx) throws SQLException {
        User u = auth(ctx);
        String title = str(body(ctx), "title", 1, 200).strip();
        if (title.isEmpty()) throw new HttpResponseException(400, "title is required");
        UUID id = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement b = c.prepareStatement("INSERT INTO boards (id, title, created_by) VALUES (?, ?, ?)");
                 PreparedStatement m = c.prepareStatement("INSERT INTO board_members (board_id, user_id, role) VALUES (?, ?, 'owner')")) {
                b.setObject(1, id);
                b.setString(2, title);
                b.setObject(3, UUID.fromString(u.id()));
                b.executeUpdate();
                m.setObject(1, id);
                m.setObject(2, UUID.fromString(u.id()));
                m.executeUpdate();
            }
            c.commit();
        }
        json(ctx.status(201), Msg.obj("id", id.toString(), "title", title, "role", "owner"));
    }

    private void members(Context ctx) throws SQLException {
        User u = auth(ctx);
        String board = ctx.pathParam("board");
        role(board, u);
        json(ctx, Msg.obj("members", membersOf(db, board)));
    }

    static List<Map<String, Object>> membersOf(DataSource db, String board) throws SQLException {
        return query(db, "SELECT u.id AS user_id, u.name, u.email, m.role FROM board_members m JOIN users u ON u.id = m.user_id "
                + "WHERE m.board_id = ? ORDER BY u.name, u.id", UUID.fromString(board));
    }

    private void addMember(Context ctx) throws SQLException {
        User u = auth(ctx);
        String board = ctx.pathParam("board");
        if (!role(board, u).equals("owner")) throw new HttpResponseException(403, "only owners manage members");
        Map<?, ?> b = body(ctx);
        String role = role(b.get("role"));
        String email = String.valueOf(b.get("email")).trim().toLowerCase(java.util.Locale.ROOT);
        var found = query("SELECT id FROM users WHERE email = ?", email);
        if (found.isEmpty()) throw new HttpResponseException(404, "no user with that email");
        UUID user = UUID.fromString((String) found.get(0).get("id"));
        int n = exec("INSERT INTO board_members (board_id, user_id, role) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", UUID.fromString(board), user, role);
        if (n == 0) throw new HttpResponseException(409, "already a member");
        membershipChanged(board);
        json(ctx.status(201), Msg.obj("user_id", user.toString(), "role", role));
    }

    private void changeRole(Context ctx) throws SQLException {
        User u = auth(ctx);
        String board = ctx.pathParam("board"), target = ctx.pathParam("user");
        if (!role(board, u).equals("owner")) throw new HttpResponseException(403, "only owners manage members");
        String role = role(body(ctx).get("role"));
        changeMember(board, target, role);
        json(ctx, Msg.obj("user_id", target, "role", role));
    }

    private void removeMember(Context ctx) throws SQLException {
        User u = auth(ctx);
        String board = ctx.pathParam("board"), target = ctx.pathParam("user");
        if (!role(board, u).equals("owner") && !target.equals(u.id())) throw new HttpResponseException(403, "only owners manage members");
        changeMember(board, target, null);
        json(ctx, Msg.obj("removed", target));
    }

    /**
     * Sets {@code target}'s role, or removes them ({@code role == null}). The owners' rows are
     * locked first, so two owners demoting each other at once cannot leave a board with none.
     */
    private void changeMember(String board, String target, String role) throws SQLException {
        if (!Hlc.isUuid(target)) throw new HttpResponseException(404, "not a member");
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                List<String> owners = new ArrayList<>();
                try (PreparedStatement s = c.prepareStatement("SELECT user_id FROM board_members WHERE board_id = ? AND role = 'owner' FOR UPDATE")) {
                    s.setObject(1, UUID.fromString(board));
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) owners.add(r.getObject(1).toString());
                    }
                }
                if (owners.equals(List.of(target)) && !"owner".equals(role))
                    throw new HttpResponseException(409, "a board must keep at least one owner");
                try (PreparedStatement s = c.prepareStatement(role == null
                        ? "DELETE FROM board_members WHERE board_id = ? AND user_id = ?"
                        : "UPDATE board_members SET role = ? WHERE board_id = ? AND user_id = ?")) {
                    int i = 1;
                    if (role != null) s.setString(i++, role);
                    s.setObject(i++, UUID.fromString(board));
                    s.setObject(i, UUID.fromString(target));
                    if (s.executeUpdate() == 0) throw new HttpResponseException(404, "not a member");
                }
                c.commit();
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            }
        }
        membershipChanged(board);
    }

    /** SPEC §5.2: every owner drops the board from its 30s membership cache. A lost message only delays that to the TTL. */
    private void membershipChanged(String board) {
        try {
            redis.publish(RedisKeys.MEMBERSHIP_CHANNEL, board);
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("board_id", board).log("membership publish failed; owners catch up within 30s: {}", e.toString());
        }
    }

    // ---- history ---------------------------------------------------------------------------

    /** SPEC §1.1: who changed what, when, for 30 days. Newest first, paged by version. */
    private void history(Context ctx) throws SQLException {
        User u = auth(ctx);
        String board = ctx.pathParam("board");
        role(board, u);
        long before = parseLong(ctx.queryParam("before_version"), Long.MAX_VALUE);
        int limit = (int) Math.max(1, Math.min(HISTORY_MAX, parseLong(ctx.queryParam("limit"), HISTORY_DEFAULT)));
        var rows = query("SELECT o.version, o.op_id, o.type, o.payload::text AS payload, o.user_id, u.name AS user_name, o.committed_at "
                + "FROM board_ops o LEFT JOIN users u ON u.id = o.user_id WHERE o.board_id = ? AND o.version < ? "
                + "AND o.committed_at > now() - make_interval(days => ?) ORDER BY o.version DESC LIMIT ?",
                UUID.fromString(board), before, HISTORY_DAYS, limit);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("version", r.get("version"));
            e.put("op_id", r.get("op_id"));
            e.put("type", r.get("type"));
            e.put("op", Json.parse((String) r.get("payload")));
            e.put("user_id", r.get("user_id"));
            e.put("user_name", r.get("user_name"));
            e.put("committed_at", ((java.sql.Timestamp) r.get("committed_at")).toInstant().toString());
            entries.add(e);
        }
        Object next = entries.size() == limit ? entries.get(entries.size() - 1).get("version") : null;
        json(ctx, Msg.obj("entries", entries, "next_before_version", next));
    }

    // ---- bug reports -----------------------------------------------------------------------

    /** The tester chose what to attach (the form shows it all); secrets are redacted again here. */
    private void bugReport(Context ctx) throws SQLException {
        User u = auth(ctx);
        Map<?, ?> b = body(ctx);
        String description = RedactingJsonEncoder.redact(str(b, "description", 1, 10_000));
        Object board = b.get("board_id");
        if (board != null && !(board instanceof String s && Hlc.isUuid(s))) throw new HttpResponseException(400, "board_id must be a UUID or null");
        if (board != null) role((String) board, u);
        if (!(b.get("diagnostics") instanceof Map<?, ?> diag)) throw new HttpResponseException(400, "diagnostics must be an object");
        String redacted;
        try {
            redacted = RedactingJsonEncoder.redact(dev.concord.crdt.Canonical.encode(diag));
        } catch (RuntimeException e) {
            throw new HttpResponseException(400, "diagnostics must be JSON with integer numbers");
        }
        if (redacted.length() > MAX_DIAGNOSTICS) throw new HttpResponseException(413, "diagnostics over 256 KB");
        UUID id = UUID.randomUUID();
        exec("INSERT INTO bug_reports (id, user_id, board_id, description, diagnostics) VALUES (?, ?, ?, ?, ?::jsonb)",
                id, UUID.fromString(u.id()), board == null ? null : UUID.fromString((String) board), description, redacted);
        log.atInfo().addKeyValue("user_id", u.id()).addKeyValue("report_id", id.toString()).log("bug report received");
        json(ctx.status(201), Msg.obj("id", id.toString()));
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static Map<?, ?> body(Context ctx) {
        try {
            if (Json.parse(ctx.body()) instanceof Map<?, ?> m) return m;
        } catch (RuntimeException e) {
            // fall through
        }
        throw new HttpResponseException(400, "body must be a JSON object");
    }

    private static String str(Map<?, ?> b, String key, int min, int max) {
        Object v = b.get(key);
        if (v == null && min == 0) return "";
        if (!(v instanceof String s)) throw new HttpResponseException(400, key + " must be a string");
        int n = s.codePointCount(0, s.length());
        if (n < min || n > max) throw new HttpResponseException(400, key + " must be " + min + "-" + max + " characters");
        return s;
    }

    static String email(Map<?, ?> b) {
        String e = str(b, "email", 3, 320).trim().toLowerCase(java.util.Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) throw new HttpResponseException(400, "that is not an email address");
        return e;
    }

    /** 1-80 code points after trimming, no control characters, well-formed Unicode. */
    static String name(Object v) {
        if (!(v instanceof String s)) throw new HttpResponseException(400, "name must be a string");
        String n = s.strip();
        int count = n.codePointCount(0, n.length());
        if (count < 1 || count > 80 || n.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.SURROGATE))
            throw new HttpResponseException(400, "name must be 1-80 characters");
        return n;
    }

    static String password(Object v) {
        if (!(v instanceof String s) || s.codePointCount(0, s.length()) < 8 || s.length() > 200)
            throw new HttpResponseException(400, "password must be 8-200 characters");
        return s;
    }

    private static String role(Object v) {
        if (!(v instanceof String s) || !List.of("owner", "editor", "viewer").contains(s))
            throw new HttpResponseException(400, "role must be owner, editor or viewer");
        return s;
    }

    private static long parseLong(String s, long dflt) {
        if (s == null || s.isEmpty()) return dflt;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new HttpResponseException(400, "not a number: " + s);
        }
    }

    private static void json(Context ctx, Map<String, Object> body) {
        ctx.contentType("application/json").result(Msg.encode(body));
    }

    private List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        return query(db, sql, args);
    }

    static List<Map<String, Object>> query(DataSource db, String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (ResultSet r = s.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                int n = r.getMetaData().getColumnCount();
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        Object o = r.getObject(i);
                        row.put(r.getMetaData().getColumnLabel(i), o instanceof UUID x ? x.toString() : o);
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }

    private int exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s.executeUpdate();
        }
    }
}
