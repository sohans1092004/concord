package dev.concord.session;

import dev.concord.common.RedisKeys;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fan-out of committed ops on Redis channel board:{id} (SPEC §2.3 step 6). */
public interface Publisher {
    void publish(String board, String message);

    /** Fire and forget: a board actor never waits on Redis. A lost message is a version gap (§6.4). */
    static Publisher redis(RedisAsyncCommands<String, String> redis) {
        Logger log = LoggerFactory.getLogger(Publisher.class);
        return (board, message) -> redis.publish(RedisKeys.channel(board), message).exceptionally(e -> {
            log.atWarn().addKeyValue("board_id", board).log("publish failed: {}", e.toString());
            return 0L;
        });
    }
}
