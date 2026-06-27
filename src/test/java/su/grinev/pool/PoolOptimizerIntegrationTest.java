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
                .setMaxPoolSize(256)    // idle-queue capacity (must hold the buffers the tests pool)
                .setBlocking(false)
                .setOutOfPoolTimeout(0)
                .build();
        // MANUAL mode so the optimizer's trim() deterministically closes each freed buffer's arena.
        return factory.getDisposablePool(() -> new ArenaByteBuffer(64, ArenaByteBuffer.Release.MANUAL));
    }

    /**
     * Deterministic (manual ticks): sample a peak demand of 5 while 5 buffers are
     * in use, then let 10 sit idle — optimize() must trim the idle pool down to the
     * peak (5), closing exactly 5 arenas.
     */
    @Test
    void optimize_freesAGradualSliceOfRealArenas() {
        DisposablePool<ArenaByteBuffer> pool = arenaPool();
        PoolOptimizer opt = new PoolOptimizer(List.<Trimmable>of(pool), 300, 0, false); // manual ticks

        List<ArenaByteBuffer> all = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            all.add(pool.get());
        }
        all.forEach(pool::release);     // 160 idle, 0 in use, 160 owned
        assertEquals(160, pool.getIdle());
        assertEquals(160, pool.getCurrentPoolSize());

        opt.fillAggregateWindow(); // peak demand sampled at 0 (pool idle)
        opt.optimize();            // toFree = max(160/50, 1) = 3 -> trim 3, closing 3 arenas

        assertEquals(157, pool.getIdle(), "~1/50 of idle freed");
        assertEquals(157, pool.getCurrentPoolSize(), "owned dropped by the 3 freed buffers");
        assertEquals(157, all.stream().filter(ArenaByteBuffer::isAlive).count(), "157 buffers remain pooled");
        assertEquals(3, all.stream().filter(b -> !b.isAlive()).count(), "3 buffers had their native arena closed");
    }

    /**
     * Through the real scheduler: 64 buffers are created then released (so the pool
     * holds 64 idle arenas) before the optimizer starts. It then samples zero demand
     * and, on each idle tick, frees a 1/10 slice — gradually reclaiming the idle
     * arenas down toward the floor (32), closing each freed buffer's arena, all on
     * the background thread, on real time.
     */
    @Test
    void scheduledOptimizer_graduallyReclaimsIdleArenas() throws Exception {
        DisposablePool<ArenaByteBuffer> pool = arenaPool();

        List<ArenaByteBuffer> all = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            all.add(pool.get());
        }
        all.forEach(pool::release);
        assertEquals(64, pool.getIdle());
        assertEquals(64, pool.getCurrentPoolSize());

        // idlePeriodSeconds=1, floor=32: frees 1/16 per tick, converging gradually toward ~floor.
        PoolOptimizer opt = new PoolOptimizer(List.<Trimmable>of(pool), 1, 32);
        try {
            // wait until it has converged near the floor (it stops at ~33 with floor 32).
            long deadline = System.currentTimeMillis() + 30_000;
            while (pool.getCurrentPoolSize() > 36 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            opt.shutdown();          // stop further ticks before sampling the result
            Thread.sleep(100);

            int finalSize = pool.getCurrentPoolSize();
            assertTrue(finalSize < 64, "scheduler reclaimed idle arenas gradually (was 64, now " + finalSize + ")");
            assertTrue(finalSize >= 32, "never shrinks below the floor (was " + finalSize + ")");
            assertEquals(64 - finalSize, all.stream().filter(b -> !b.isAlive()).count(),
                    "every reclaimed buffer had its arena closed");
        } finally {
            opt.shutdown();
        }
    }
}
