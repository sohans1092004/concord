package dev.concord.session;

import dev.concord.common.Redis;
import dev.concord.common.RedisKeys;
import dev.concord.crdt.Json;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Captures what a board actor sends: replies to one requester, and everything on the channel. */
public final class Probe implements AutoCloseable {
    public final BlockingQueue<Map<String, Object>> replies = new LinkedBlockingQueue<>();
    public final List<Map<String, Object>> channel = new CopyOnWriteArrayList<>();
    private final Redis redis;
    private final StatefulRedisPubSubConnection<String, String> sub;
    private final String board;

    public Probe(String board) {
        this.board = board;
        redis = Infra.redis();
        sub = redis.pubSub();
        sub.addListener(new RedisPubSubAdapter<>() {
            @Override
            @SuppressWarnings("unchecked")
            public void message(String ch, String message) {
                channel.add((Map<String, Object>) Json.parse(message));
            }
        });
        sub.sync().subscribe(RedisKeys.channel(board));
    }

    /** Stops listening on the channel (replies still arrive): the board then has no subscriber. */
    public void unsubscribe() {
        sub.sync().unsubscribe(RedisKeys.channel(board));
    }

    public BoardActor.Reply reply() {
        return replies::add;
    }

    /** The next reply, failing the test after 10s. */
    public Map<String, Object> next() throws InterruptedException {
        Map<String, Object> m = replies.poll(10, TimeUnit.SECONDS);
        if (m == null) throw new AssertionError("no reply within 10s");
        return m;
    }

    /** No reply arrives within {@code ms}. */
    public boolean quiet(long ms) throws InterruptedException {
        return replies.poll(ms, TimeUnit.MILLISECONDS) == null;
    }

    /** OP_BROADCASTs seen on the channel for this op_id. */
    public List<Map<String, Object>> broadcastsOf(String opId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : channel)
            if ("OP_BROADCAST".equals(m.get("type")) && opId.equals(((Map<?, ?>) m.get("op")).get("op_id"))) out.add(m);
        return out;
    }

    @Override
    public void close() {
        sub.close();
        redis.close();
    }
}
