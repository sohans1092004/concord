package dev.concord.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** The shared HikariCP pool. One per process; board writers share it (SPEC §2.2, amendment 6). */
public final class Db {
    private Db() {}

    public static HikariDataSource pool(Config c) {
        HikariConfig h = new HikariConfig();
        h.setJdbcUrl(c.dbUrl());
        h.setUsername(c.dbUser());
        h.setPassword(c.dbPassword());
        h.setMaximumPoolSize(c.dbPoolSize());
        h.setMinimumIdle(1);
        // Waiting for a connection counts toward the 2s commit timeout (SPEC §6.1 step 5), so the
        // pool never waits longer than that; the writer checks the remaining budget itself.
        h.setConnectionTimeout(2_000);
        // Start even if Postgres is down: the write path answers RETRY_LATER until it returns.
        h.setInitializationFailTimeout(-1);
        h.setPoolName("concord");
        // Backstop for every statement: a black-holed network fails within 10s instead of hanging
        // a board actor. The write path sets its own, tighter, deadline (Store.commit).
        h.addDataSourceProperty("socketTimeout", "10");
        // A transaction left open by a dead client (a commit that timed out) must not hold the
        // board row forever.
        h.setConnectionInitSql("SET idle_in_transaction_session_timeout = '5s'");
        return new HikariDataSource(h);
    }
}
