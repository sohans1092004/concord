package dev.concord.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.concord.common.Msg;
import dev.concord.common.Redis;
import dev.concord.common.RedisKeys;
import dev.concord.session.Infra;
import dev.concord.session.Ops;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** SPEC §2.3 step 4, §6.3: a gateway stops routing to a replaced owner within 5s of the new owner's announce. */
class RouterCacheTest {
    @Test
    void aReplacedOwnerIsRoutedToForAtMostTheCacheTtl() {
        try (Redis redis = new Redis(Infra.redisUrl())) {
            String board = Ops.uuid();
            AtomicLong now = new AtomicLong(1_000_000);
            Router router = new Router(redis.conn.sync(), now::get, "unused");
            redis.conn.sync().set(RedisKeys.owner(board), Msg.encode(Msg.obj("instance", "a", "address", "ws://a/internal", "fence", 1)));
            assertEquals("ws://a/internal", router.owner(board));

            redis.conn.sync().set(RedisKeys.owner(board), Msg.encode(Msg.obj("instance", "b", "address", "ws://b/internal", "fence", 2)));
            now.addAndGet(Router.CACHE_MS - 1);
            assertEquals("ws://a/internal", router.owner(board), "still cached");
            now.addAndGet(1);
            assertEquals("ws://b/internal", router.owner(board), "re-read after 5s");
        }
    }
}
