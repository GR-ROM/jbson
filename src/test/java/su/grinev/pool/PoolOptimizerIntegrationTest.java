package su.grinev.pool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests: {@link PoolOptimizer} driving a real
 * {@link DisposablePool}&lt;{@link ArenaByteBuffer}&gt;, so the trim path actually
 * closes native arenas (deterministic free), not just a fake.
 */
public class PoolOptimizerIntegrationTest {

    private static DisposablePool<ArenaByteBuffer> arenaPool() {
        PoolFactory factory = PoolFactory.Builder.builder()
                .setMinPoolSize(0)      // no prefill — objects are created on demand
                .setMaxPoolSize(64)
                .setBlocking(false)
                .setOutOfPoolTimeout(0)
                .build();
        return factory.getDisposablePool(() -> new ArenaByteBuffer(64));
    }

    /**
     * Deterministic (manual ticks): sample a peak demand of 5 while 5 buffers are
     * in use, then let 10 sit idle — optimize() must trim the idle pool down to the
     * peak (5), closing exactly 5 arenas.
     */
    @Test
    void optimize_trimsRealArenaPool_downToPeakDemand() {
        DisposablePool<ArenaByteBuffer> pool = arenaPool();
        PoolOptimizer opt = new PoolOptimizer(List.<Trimmable>of(pool), 300, 0, false); // manual ticks

        List<ArenaByteBuffer> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(pool.get());
        }
        // release half: 5 in use, 5 idle, 10 owned
        for (int i = 0; i < 5; i++) {
            pool.release(all.get(i));
        }
        assertEquals(5, pool.getCount(), "5 in use at sampling time");

        opt.fillAggregateWindow(); // observed peak demand = 5

        // release the rest: 10 idle, 0 in use, 10 owned
        for (int i = 5; i < 10; i++) {
            pool.release(all.get(i));
        }
        assertEquals(10, pool.getIdle());
        assertEquals(10, pool.getCurrentPoolSize());

        opt.optimize(); // idle 10 > peak 5 -> trim 5, closing 5 arenas

        assertEquals(5, pool.getIdle(), "idle trimmed down to the peak demand");
        assertEquals(5, pool.getCurrentPoolSize(), "owned dropped by the 5 freed buffers");

        long alive = all.stream().filter(ArenaByteBuffer::isAlive).count();
        long closed = all.stream().filter(b -> !b.isAlive()).count();
        assertEquals(5, alive, "5 buffers remain pooled and alive");
        assertEquals(5, closed, "5 buffers had their native arena closed");
    }

    /**
     * Through the real scheduler: 8 buffers are created then released (so the pool
     * holds 8 idle arenas) before the optimizer starts. It then samples zero demand
     * and, on its idle tick, reclaims the idle arenas down to minPoolSize (3),
     * closing the other 5 — all on the background thread, on real time.
     */
    @Test
    void scheduledOptimizer_reclaimsIdleArenas_downToMinPoolSize() throws Exception {
        DisposablePool<ArenaByteBuffer> pool = arenaPool();

        List<ArenaByteBuffer> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            all.add(pool.get());
        }
        all.forEach(pool::release);
        assertEquals(8, pool.getIdle());
        assertEquals(8, pool.getCurrentPoolSize());

        // idlePeriodSeconds=1, minPoolSize floor=3, scheduler ON
        PoolOptimizer opt = new PoolOptimizer(List.<Trimmable>of(pool), 1, 3);
        try {
            long deadline = System.currentTimeMillis() + 15_000;
            while (pool.getCurrentPoolSize() > 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertEquals(3, pool.getCurrentPoolSize(), "scheduler trimmed idle arenas down to minPoolSize");
            assertEquals(3, pool.getIdle());
            assertEquals(0, pool.getCount());
            assertEquals(5, all.stream().filter(b -> !b.isAlive()).count(),
                    "the 5 reclaimed buffers had their arenas closed");
        } finally {
            opt.shutdown();
        }
    }
}
