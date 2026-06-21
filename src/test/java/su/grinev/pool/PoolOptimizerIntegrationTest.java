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
    void optimize_halvesRealArenaPool_freeingArenas() {
        DisposablePool<ArenaByteBuffer> pool = arenaPool();
        PoolOptimizer opt = new PoolOptimizer(List.<Trimmable>of(pool), 300, 0, false); // manual ticks

        List<ArenaByteBuffer> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(pool.get());
        }
        all.forEach(pool::release);     // 10 idle, 0 in use, 10 owned
        assertEquals(10, pool.getIdle());
        assertEquals(10, pool.getCurrentPoolSize());

        opt.fillAggregateWindow(); // peak demand sampled at 0 (pool idle)
        opt.optimize();            // keep = 5 > peak 0 -> trim 5, closing 5 arenas

        assertEquals(5, pool.getIdle(), "idle halved");
        assertEquals(5, pool.getCurrentPoolSize(), "owned dropped by the 5 freed buffers");
        assertEquals(5, all.stream().filter(ArenaByteBuffer::isAlive).count(), "5 buffers remain pooled");
        assertEquals(5, all.stream().filter(b -> !b.isAlive()).count(), "5 buffers had their native arena closed");
    }

    /**
     * Through the real scheduler: 8 buffers are created then released (so the pool
     * holds 8 idle arenas) before the optimizer starts. It then samples zero demand
     * and, on its idle tick, reclaims the idle arenas down to minPoolSize (3),
     * closing the other 5 — all on the background thread, on real time.
     */
    @Test
    void scheduledOptimizer_halvesIdleArenas_convergingAboveFloor() throws Exception {
        DisposablePool<ArenaByteBuffer> pool = arenaPool();

        List<ArenaByteBuffer> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            all.add(pool.get());
        }
        all.forEach(pool::release);
        assertEquals(8, pool.getIdle());
        assertEquals(8, pool.getCurrentPoolSize());

        // idlePeriodSeconds=1, floor=3: 8 -> keep 4 (>3) -> trim to 4; then keep 2 (<3) -> stop at 4.
        PoolOptimizer opt = new PoolOptimizer(List.<Trimmable>of(pool), 1, 3);
        try {
            long deadline = System.currentTimeMillis() + 15_000;
            while (pool.getCurrentPoolSize() > 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertEquals(4, pool.getCurrentPoolSize(), "halving converges and stops above the floor");
            assertEquals(4, pool.getIdle());
            assertEquals(0, pool.getCount());
            assertEquals(4, all.stream().filter(b -> !b.isAlive()).count(),
                    "the 4 reclaimed buffers had their arenas closed");
        } finally {
            opt.shutdown();
        }
    }
}
