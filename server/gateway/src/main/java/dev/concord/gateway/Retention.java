package dev.concord.gateway;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Op retention (SPEC §5.1, amendment 28): once a day, delete ops older than 35 days that are at
 * or below the version of the board's OLDEST kept snapshot. Every kept snapshot can then still
 * be replayed forward, so the corrupt-snapshot fallback (§8 row 13) stays sound, and a board
 * with no snapshot loses nothing. History covers 30 days, so it never shows a gap; a client
 * behind the oldest kept op gets a full snapshot on JOIN or SYNC (§7.3). Idempotent, so every
 * gateway may run it. Deletes in batches, so no statement holds board_ops for long.
 */
final class Retention {
    private static final Logger log = LoggerFactory.getLogger(Retention.class);
    static final int DAYS = 35;
    static final int BATCH = 5_000;

    private Retention() {}

    /** Deletes what retention allows; returns how many ops it deleted. */
    static long prune(DataSource db, int days) throws SQLException {
        long total = 0;
        try (Connection c = db.getConnection();
                PreparedStatement d = c.prepareStatement(
                        "DELETE FROM board_ops WHERE ctid IN ("
                                + "SELECT o.ctid FROM board_ops o"
                                + " JOIN (SELECT board_id, min(version) AS oldest FROM board_snapshots GROUP BY board_id) k ON k.board_id = o.board_id"
                                + " WHERE o.version <= k.oldest"
                                + " AND o.committed_at < now() - make_interval(days => ?)"
                                + " LIMIT ?)")) {
            d.setInt(1, days);
            d.setInt(2, BATCH);
            for (int n; (n = d.executeUpdate()) > 0; ) {
                total += n;
                if (n < BATCH) break;
            }
        }
        return total;
    }

    /** The daily run, from the gateway's cleanup thread. */
    static void daily(DataSource db) {
        long started = System.currentTimeMillis();
        try {
            long deleted = prune(db, DAYS);
            log.atInfo().addKeyValue("ops_deleted", deleted).addKeyValue("ms", System.currentTimeMillis() - started).log("op retention ran");
        } catch (SQLException | RuntimeException e) {
            log.atWarn().log("op retention failed, retried tomorrow: {}", e.toString());
        }
    }
}
