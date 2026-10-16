package dev.concord.session;

import dev.concord.common.Config;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;

/** One in-process Session instance with a seeded board, a user and a probe on its channel. */
public final class Harness implements AutoCloseable {
    public final DataSource db = Infra.db();
    public final String owner = Seed.user(db);
    public final String board = Seed.board(db, owner);
    public final Ops ops = new Ops(board);
    public final Probe probe = new Probe(board);
    public SessionMain session;

    public Harness() {
        this(Store::new);
    }

    /** {@code store} builds the instance's Store from its pool, so tests can inject failures. */
    public Harness(Function<DataSource, Store> store) {
        this(store, false);
    }

    /** {@code viaProxy}: the instance reaches Postgres through Toxiproxy. */
    public Harness(Function<DataSource, Store> store, boolean viaProxy) {
        session = start("s-" + Ops.uuid(), store, viaProxy);
    }

    public static SessionMain start(String instanceId, Function<DataSource, Store> store, boolean viaProxy) {
        Config c = Infra.config(instanceId, Infra.freePort(), viaProxy);
        return new SessionMain(c, store.apply(dev.concord.common.Db.pool(c)));
    }

    public Map<String, Object> submit(Map<String, Object> op) throws InterruptedException {
        return submit(session, op, owner);
    }

    public Map<String, Object> submit(SessionMain s, Map<String, Object> op, String user) throws InterruptedException {
        s.boards.dispatch(board, new BoardActor.Submit(op, user, probe.reply()));
        return probe.next();
    }

    public Map<String, Object> join(String user, Long since) throws InterruptedException {
        session.boards.dispatch(board, new BoardActor.Join(user, since, probe.reply()));
        return probe.next();
    }

    /** {"version", "state"} of the actor's memory, read on its own thread. */
    public Map<String, Object> inspect(SessionMain s) throws Exception {
        BoardActor a = s.boards.actor(board);
        if (a == null) throw new AssertionError("no actor for the board on this instance");
        return a.inspect().get(10, TimeUnit.SECONDS);
    }

    public long rows(String sql) {
        return Seed.count(db, sql, java.util.UUID.fromString(board));
    }

    @Override
    public void close() {
        if (session != null) session.close();
        probe.close();
    }
}
