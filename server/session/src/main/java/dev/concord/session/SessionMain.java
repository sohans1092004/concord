package dev.concord.session;

import com.zaxxer.hikari.HikariDataSource;
import dev.concord.common.Config;
import dev.concord.common.Db;
import dev.concord.common.Redis;
import dev.concord.common.RedisKeys;
import dev.concord.common.Rendezvous;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A Session Service instance (SPEC §2.1): owns leased boards; never talks to clients. */
public final class SessionMain implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SessionMain.class);

    public final Config config;
    public final HikariDataSource pool;
    public final Redis redis;
    public final Store store;
    public final Metrics metrics = new Metrics();
    public final Boards boards;
    private final Registry registry;
    private final StatefulRedisPubSubConnection<String, String> membershipSub;
    private final InternalServer server;

    public SessionMain(Config config) {
        this(config, new Store(Db.pool(config)));
    }

    /** Tests pass a Store subclass to inject failures. */
    public SessionMain(Config config, Store store) {
        this.config = config.check();
        this.store = store;
        this.pool = store.dataSource() instanceof HikariDataSource h ? h : null;
        this.redis = new Redis(config.redisUrl());
        Membership membership = new Membership(store, System::currentTimeMillis);
        Ownership ownership = new Ownership(redis.conn.sync(), store, config.instanceId(), config.advertise(), metrics);
        this.boards = new Boards(new BoardActor.Deps(store, ownership, membership, Publisher.redis(redis.conn.async()),
                metrics, System::currentTimeMillis, config.batchMs()));
        membershipSub = redis.pubSub();
        membershipSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String board) {
                membership.invalidate(board);
            }
        });
        membershipSub.sync().subscribe(RedisKeys.MEMBERSHIP_CHANNEL);
        this.server = new InternalServer(boards, metrics, config.port(), config.internalSecret());
        this.registry = new Registry(redis.conn.sync(), new Rendezvous.Instance(config.instanceId(), config.advertise()), System::currentTimeMillis);
        log.atInfo().addKeyValue("instance", config.instanceId()).addKeyValue("address", config.advertise()).log("session instance up");
    }

    public int port() {
        return server.port();
    }

    /** Graceful shutdown (SPEC §2.4). */
    @Override
    public void close() {
        registry.close(); // gateways stop picking this instance
        boards.close(); // stop taking ops, flush, snapshot, release each lease
        server.close();
        membershipSub.close();
        redis.close();
        if (pool != null) pool.close();
    }

    public static void main(String[] args) {
        SessionMain s = new SessionMain(Config.fromEnv());
        Runtime.getRuntime().addShutdownHook(new Thread(s::close, "shutdown"));
    }
}
