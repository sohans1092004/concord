package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.concord.common.Config;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Amendment 8: only a caller holding the shared secret may open the internal socket. */
class InternalAuthTest {
    static WebSocket open(int port, String secret) throws Exception {
        WebSocket.Builder b = HttpClient.newHttpClient().newWebSocketBuilder();
        if (secret != null) b.header(Config.INTERNAL_HEADER, secret);
        return b.buildAsync(URI.create("ws://localhost:" + port + "/internal"), new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS);
    }

    @Test
    void internalSocketRequiresTheSharedSecret() throws Exception {
        try (Harness h = new Harness()) {
            int port = h.session.port();
            assertThrows(ExecutionException.class, () -> open(port, null));
            assertThrows(ExecutionException.class, () -> open(port, "wrong-secret"));
            assertEquals(2, h.session.metrics.get("internal_auth_rejections"));
            open(port, h.session.config.internalSecret()).abort();
        }
    }
}
