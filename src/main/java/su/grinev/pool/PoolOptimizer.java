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
 * every {@code idlePeriodSeconds}, frees a 1/10 slice of any pool whose retained
 * 9/10 would still exceed both the windowed 99.5th-percentile demand (so a rare
 * outlier spike doesn't keep a pool inflated) and the per-pool floor —
 * releasing the excess (and, for arena-backed pools, native memory) when demand
 * drops. A shorter window forgets demand spikes faster, so pools reclaim sooner.
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
        this(pools, DEFAULT_WINDOW_SEC, idlePeriodSeconds, p -> minPoolSize, true);
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
        pools.forEach(pool -> pool.aggregateWindow.put(pool.trimmablePool.getCount()));
    }

    void optimize() {
        pools.forEach(pool -> {
            Trimmable p = pool.trimmablePool;
            if (!p.isTrimmable()) {
                return;
            }
            int peakInUse = pool.aggregateWindow.p995();   // 99.5th pct, not max — ignore only the top-0.5% outliers
            int idleCount = p.getIdle();
            int floor = minPoolSize.applyAsInt(p);
            int toFree = idleCount / 10;     // gradual: free 1/10 of idle per tick
            int keep = idleCount - toFree;
            if (toFree > 0 && keep > peakInUse && keep > floor) {
                // trim() reports back whether the pool was actually shrunk.
                if (p.trim(toFree)) {
                    log.info("Trimmed pool '{}': idle {} -> {} (freed {}, peak {}, floor {})",
                            p.getName(), idleCount, p.getIdle(), toFree, peakInUse, floor);
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
