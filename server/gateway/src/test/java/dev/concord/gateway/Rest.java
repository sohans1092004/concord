package dev.concord.gateway;

import dev.concord.common.Msg;
import dev.concord.crdt.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** A REST client for tests: one session, kept as a cookie (like a browser) or sent as Bearer. */
class Rest {
    record Reply(int status, Map<?, ?> body, HttpResponse<String> raw) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    final int port;
    String cookie; // the concord_session value, from Set-Cookie
    String bearer;
    String origin = "http://allowed.test";

    Rest(int port) {
        this.port = port;
    }

    Reply get(String path) throws Exception {
        return send("GET", path, null);
    }

    Reply post(String path, Map<String, Object> body) throws Exception {
        return send("POST", path, body);
    }

    Reply send(String method, String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json");
        if (origin != null) b.header("Origin", origin);
        if (cookie != null) b.header("Cookie", Api.COOKIE + "=" + cookie);
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Msg.encode(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        r.headers().firstValue("Set-Cookie").ifPresent(c -> {
            String v = c.substring(c.indexOf('=') + 1, c.indexOf(';'));
            cookie = v.isEmpty() ? null : v;
        });
        Map<?, ?> parsed = r.body().isEmpty() ? Map.of() : (Map<?, ?>) Json.parse(r.body());
        return new Reply(r.statusCode(), parsed, r);
    }

    /** Signs up a fresh user; returns its ID. The session cookie is kept. */
    Map<?, ?> signUp(String email, String name) throws Exception {
        Reply r = post("/api/auth/sign-up", Msg.obj("email", email, "name", name, "password", "correct horse battery"));
        if (r.status() != 201) throw new AssertionError("sign-up: " + r.status() + " " + r.body());
        return r.body();
    }

    String ticket() throws Exception {
        Reply r = post("/api/ws-ticket", Msg.obj());
        if (r.status() != 200) throw new AssertionError("ticket: " + r.status() + " " + r.body());
        return (String) r.body().get("ticket");
    }

    static String email() {
        return "u" + java.util.UUID.randomUUID() + "@test.example";
    }
}
