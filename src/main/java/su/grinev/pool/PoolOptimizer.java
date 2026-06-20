package su.grinev.pool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically samples each monitored pool's size into a rolling
 * {@link AggregateWindow} and, less frequently, shrinks any pool that holds
 * more than its observed peak demand — releasing the excess (and, for
 * arena-backed pools, the underlying native memory) during long idle periods.
 */
public class PoolOptimizer {
    private static final int MAX_IDLE_WINDOW_SEC = 3600;
    private static final int SAMPLE_PERIOD_SEC = 1;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final List<MonitoredPool> pools;
    private final int minPoolSize;

    public PoolOptimizer(Collection<Trimmable> pools, int idlePeriodSeconds, int minPoolSize) {
        this(pools, idlePeriodSeconds, minPoolSize, true);
    }

    /** Package-private: tests build the optimizer without starting the background scheduler. */
    PoolOptimizer(Collection<Trimmable> pools, int idlePeriodSeconds, int minPoolSize, boolean start) {
        this.minPoolSize = minPoolSize;
        this.pools = new ArrayList<>(pools.size());
        pools.forEach(pool ->
                this.pools.add(new MonitoredPool(new AggregateWindow(MAX_IDLE_WINDOW_SEC), pool)));

        if (start) {
            executor.scheduleAtFixedRate(this::fillAggregateWindow, SAMPLE_PERIOD_SEC, SAMPLE_PERIOD_SEC, TimeUnit.SECONDS);
            executor.scheduleAtFixedRate(this::optimize, idlePeriodSeconds, idlePeriodSeconds, TimeUnit.SECONDS);
        }
    }

    void fillAggregateWindow() {
        pools.forEach(pool -> pool.aggregateWindow.put(pool.trimmablePool.getCount()));
    }

    void optimize() {
        pools.forEach(pool -> {
            if (!pool.trimmablePool.isTrimmable()) {
                return;
            }
            int peakInUse = pool.aggregateWindow.max();
            int idleCount = pool.trimmablePool.getIdle();
            if (idleCount > peakInUse) {
                pool.trimmablePool.trim(Math.min(idleCount - peakInUse, idleCount - minPoolSize));
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
