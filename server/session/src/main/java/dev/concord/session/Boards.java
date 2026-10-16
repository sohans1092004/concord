package dev.concord.session;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The boards this instance is serving: one {@link BoardActor} per board, created on the first
 * request for it (which claims it), removed when it closes. Also renews every lease every 5s.
 */
public final class Boards implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Boards.class);
    private final BoardActor.Deps deps;
    private final Map<String, BoardActor> actors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("lease-renewer").daemon().factory());
    private volatile boolean stopping;

    public Boards(BoardActor.Deps deps) {
        this.deps = deps;
        renewer.scheduleWithFixedDelay(this::renewAll, RedisKeys.RENEW_MS, RedisKeys.RENEW_MS, TimeUnit.MILLISECONDS);
    }

    /** Routes an item to its board's actor, claiming the board if this instance has no actor. */
    public void dispatch(String board, BoardActor.Item item) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (stopping) {
                refuse(item);
                return;
            }
            BoardActor a = actors.computeIfAbsent(board, b -> BoardActor.start(b, deps, () -> actors.remove(b)));
            if (a.offer(item)) return;
            actors.remove(board, a); // closed between lookup and offer: try a fresh one
        }
        refuse(item);
    }

    private static void refuse(BoardActor.Item item) {
        BoardActor.Reply r = switch (item) {
            case BoardActor.Submit s -> s.reply();
            case BoardActor.Join j -> j.reply();
            case BoardActor.SyncRequest s -> s.reply();
            case BoardActor.Poll p -> p.reply();
            case BoardActor.Ensure e -> e.reply();
            default -> null;
        };
        if (r != null) r.send(Msg.error(Msg.NOT_OWNER, item instanceof BoardActor.Submit s && s.op() instanceof Map<?, ?> m
                && m.get("op_id") instanceof String x ? x : null, "this instance is not serving the board"));
    }

    public BoardActor actor(String board) {
        return actors.get(board);
    }

    /**
     * Compare-and-extend every lease (SPEC §6.2 step 5). Lost to another owner: drop the board.
     * Redis unreachable: keep serving, because Postgres still fences us (§6.3).
     */
    private void renewAll() {
        for (BoardActor a : List.copyOf(actors.values())) {
            long fence = a.fence();
            if (fence < 0) continue;
            try {
                long r = deps.ownership().renew(a.id(), fence);
                if (r == 0) a.offer(new BoardActor.Drop("lease held by another owner"));
                else if (r == 2) log.atInfo().addKeyValue("board_id", a.id()).log("lease was gone (expired or Redis wiped); re-taken");
            } catch (RuntimeException e) {
                deps.metrics().inc("lease_renew_failures");
            }
        }
    }

    /** Graceful shutdown (SPEC §2.4): refuse new work, then each actor flushes, snapshots, releases. */
    @Override
    public void close() {
        stopping = true;
        renewer.shutdownNow();
        List<CompletableFuture<Void>> done = actors.values().stream().map(a -> {
            CompletableFuture<Void> f = new CompletableFuture<>();
            if (!a.offer(new BoardActor.Release(f))) f.complete(null);
            return f;
        }).toList();
        try {
            CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("not every board released within 10s: {}", e.toString());
        }
    }
}
