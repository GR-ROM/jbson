package su.grinev.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * Periodically samples each monitored pool's in-use count into a rolling
 * {@link AggregateWindow} of {@code windowSeconds} samples (one per second) and,
 * every {@code idlePeriodSeconds}, frees a ~1/50 slice (at least one) of any pool
 * whose retained remainder would still exceed both the peak (max) demand seen
 * anywhere in the window and the per-pool floor — releasing the excess gradually
 * (and, for arena-backed pools,
 * native memory) only once demand drops. The peak is taken at full strength (no
 * outlier trimming): a pool is held at its highest observed in-use count until
 * that peak ages out of the window, so with the default 1-hour window a pool
 * sized by a spike stays sized for an hour after it. A shorter window forgets
 * peaks faster, so pools reclaim sooner.
 */
public class PoolOptimizer {
    private static final Logger log = LoggerFactory.getLogger(PoolOptimizer.class);
    private static final int DEFAULT_WINDOW_SEC = 3600;
    private static final int SAMPLE_PERIOD_SEC = 1;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final List<MonitoredPool> pools;
    private final ToIntFunction<Trimmable> minPoolSize;

    /** Uniform floor, default 1-hour window. */
    public PoolOptimizer(Collection<Trimmable> pools, int idlePeriodSeconds, int minPoolSize) {
        this(pools, DEFAULT_WINDOW_SEC, idlePeriodSeconds, pool -> minPoolSize, true);
    }

    /** Per-pool floor, default 1-hour window. */
    public PoolOptimizer(Collection<Trimmable> pools, int idlePeriodSeconds, ToIntFunction<Trimmable> minPoolSize) {
        this(pools, DEFAULT_WINDOW_SEC, idlePeriodSeconds, minPoolSize, true);
    }

    /** Per-pool floor with a configurable peak-demand window (seconds). */
    public PoolOptimizer(Collection<Trimmable> pools, int windowSeconds, int idlePeriodSeconds, ToIntFunction<Trimmable> minPoolSize) {
        this(pools, windowSeconds, idlePeriodSeconds, minPoolSize, true);
    }

    /** Package-private: tests build the optimizer without starting the background scheduler. */
    PoolOptimizer(Collection<Trimmable> pools, int idlePeriodSeconds, int minPoolSize, boolean start) {
        this(pools, DEFAULT_WINDOW_SEC, idlePeriodSeconds, p -> minPoolSize, start);
    }

    PoolOptimizer(Collection<Trimmable> pools, int idlePeriodSeconds, ToIntFunction<Trimmable> minPoolSize, boolean start) {
        this(pools, DEFAULT_WINDOW_SEC, idlePeriodSeconds, minPoolSize, start);
    }

    PoolOptimizer(Collection<Trimmable> pools, int windowSeconds, int idlePeriodSeconds, ToIntFunction<Trimmable> minPoolSize, boolean start) {
        this.minPoolSize = minPoolSize;
        this.pools = new ArrayList<>(pools.size());
        pools.forEach(pool ->
                this.pools.add(new MonitoredPool(new AggregateWindow(Math.max(windowSeconds, 1)), pool)));

        if (start) {
            log.info("PoolOptimizer started: {} pools, {}s peak window, optimize every {}s",
                    this.pools.size(), windowSeconds, idlePeriodSeconds);
            executor.scheduleAtFixedRate(this::fillAggregateWindow, SAMPLE_PERIOD_SEC, SAMPLE_PERIOD_SEC, TimeUnit.SECONDS);
            executor.scheduleAtFixedRate(this::optimize, idlePeriodSeconds, idlePeriodSeconds, TimeUnit.SECONDS);
        }
    }

    void fillAggregateWindow() {
        pools.forEach(pool -> pool.aggregateWindow.put(pool.trimmablePool.getCountInUse()));
    }

    void optimize() {
        pools.forEach(pool -> {
            Trimmable p = pool.trimmablePool;
            if (!p.isTrimmable()) {
                return;
            }
            int peakInUse = pool.aggregateWindow.max();   // full peak demand over the window — keep enough for the worst burst, no outlier trimming
            int idleCount = p.getIdle();
            int owned = p.getCountInUse() + idleCount;          // total objects owned by the pool: in use + idle
            // Only reclaim above the pool's own baseline (initial/min size): never trim the
            // pre-allocated floor away during idle, but do release genuine excess grown by a burst.
            if (idleCount > 0) {
                int floor = minPoolSize.applyAsInt(p);
                int toFree = Math.max(idleCount / 50, 1);  // gentle: free ~1/50 of idle per tick (floor 1 so small pools still drain)
                toFree = Math.min(toFree, owned - p.getMinSize());   // clamp so owned never drops below the baseline in one tick
                int keep = idleCount - toFree;
                if (toFree > 0 && keep > peakInUse && keep > floor) {
                    // trim() reports back whether the pool was actually shrunk.
                    if (p.trim(toFree)) {
                        log.info("Trimmed pool '{}': idle {} -> {} (freed {}, peak {}, floor {}, minSize {})",
                                p.getName(), idleCount, p.getIdle(), toFree, peakInUse, floor, p.getMinSize());
                    }
                }
            }
        });
    }

    /** Stop the background scheduler. */
    public void shutdown() {
        executor.shutdownNow();
    }

    boolean isRunning() {
        return !executor.isShutdown();
    }

    List<MonitoredPool> monitoredPools() {
        return pools;
    }

    public record MonitoredPool(AggregateWindow aggregateWindow, Trimmable trimmablePool) {}
}
