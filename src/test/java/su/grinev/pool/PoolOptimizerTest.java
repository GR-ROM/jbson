package su.grinev.pool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PoolOptimizer}. The optimizer is built with {@code start=false}
 * so the schedulers never fire, and {@code fillAggregateWindow()} / {@code optimize()}
 * are driven manually for deterministic assertions.
 *
 * Model: each second the in-use count ({@link Trimmable#getCount()}) is sampled
 * into a rolling window; on the idle tick, if the pool holds more idle objects
 * ({@link Trimmable#getIdle()}) than the peak demand, the excess idle objects are
 * trimmed down to {@code max(peakInUse, minPoolSize)}.
 */
public class PoolOptimizerTest {

    /** A controllable {@link Trimmable}: independent in-use and idle counts; records trim requests. */
    static final class FakeTrimmable implements Trimmable {
        int inUse;
        int idle;
        boolean trimmable = true;
        final List<Integer> trimCalls = new ArrayList<>();

        FakeTrimmable(int inUse, int idle) {
            this.inUse = inUse;
            this.idle = idle;
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public int getCount() {
            return inUse;
        }

        @Override
        public int getIdle() {
            return idle;
        }

        @Override
        public boolean trim(int n) {
            trimCalls.add(n);
            if (n > 0) {
                idle = Math.max(0, idle - n);
            }
            return true;
        }

        @Override
        public boolean isTrimmable() {
            return trimmable;
        }
    }

    private static PoolOptimizer optimizerFor(Trimmable... pools) {
        return optimizerFor(0, pools);
    }

    private static PoolOptimizer optimizerFor(int minPoolSize, Trimmable... pools) {
        return new PoolOptimizer(List.<Trimmable>of(pools), 300, minPoolSize, false);
    }

    // ---------------------------------------------------------------------
    // sampling
    // ---------------------------------------------------------------------

    @Test
    void fillAggregateWindow_samplesInUseCount() {
        FakeTrimmable t = new FakeTrimmable(7, 100); // 7 in use, 100 idle
        PoolOptimizer opt = optimizerFor(t);

        opt.fillAggregateWindow();

        assertEquals(7, opt.monitoredPools().get(0).aggregateWindow().max(),
                "the window samples demand (in-use), not idle");
    }

    @Test
    void fillAggregateWindow_tracksPeakDemand() {
        FakeTrimmable t = new FakeTrimmable(10, 0);
        PoolOptimizer opt = optimizerFor(t);

        opt.fillAggregateWindow();          // demand 10
        t.inUse = 40;
        opt.fillAggregateWindow();          // demand 40 (peak)
        t.inUse = 20;
        opt.fillAggregateWindow();          // demand 20

        assertEquals(40, opt.monitoredPools().get(0).aggregateWindow().max(),
                "the window retains the peak demand over the sampling history");
    }

    // ---------------------------------------------------------------------
    // optimize / trim
    // ---------------------------------------------------------------------

    // Strategy: toFree = idle / 10; trim toFree only when toFree > 0 AND (idle - toFree) > peak AND > floor.
    // i.e. free 1/10 of idle per tick (gradual decay) as long as the retained 9/10 still exceeds peak and floor.

    @Test
    void optimize_freesAGradualSlice_whenSafe() {
        FakeTrimmable t = new FakeTrimmable(0, 160); // peak 0, 160 idle, floor 0
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // observed peak demand = 0

        opt.optimize();

        // toFree = 160/10 = 16; keep 144 > peak(0) and > floor(0)
        assertEquals(List.of(16), t.trimCalls, "frees 1/10 of idle (gradual decay)");
        assertEquals(144, t.idle);
    }

    @Test
    void optimize_usesP995_ignoringIsolatedSpikes() {
        FakeTrimmable t = new FakeTrimmable(0, 160); // 160 idle
        PoolOptimizer opt = optimizerFor(t);
        // 199 low-demand samples (in-use 5) and one spike (150): p99.5 == 5, but max() would be 150.
        // (A lone spike is the top 0.5% of 200 samples, so the 99.5th percentile excludes it.)
        t.inUse = 5;
        for (int i = 0; i < 199; i++) {
            opt.fillAggregateWindow();
        }
        t.inUse = 150;
        opt.fillAggregateWindow();

        opt.optimize();

        // keep = 160 - 16 = 144 > p99.5(5) -> trims. (Against max(150) the lone spike would have blocked it.)
        assertEquals(List.of(16), t.trimCalls, "trims against the 99.5th percentile, ignoring the lone spike");
        assertEquals(144, t.idle);
    }

    @Test
    void optimize_doesNotTrim_whenKeepWouldDropBelowPeak() {
        FakeTrimmable t = new FakeTrimmable(20, 16); // peak 20, idle 16
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 20

        opt.optimize();

        // toFree = 16/10 = 1, keep 15; 15 > peak(20)? no -> nothing trimmed
        assertTrue(t.trimCalls.isEmpty(), "won't trim when the retained 9/10 wouldn't exceed peak demand");
    }

    @Test
    void optimize_doesNotTrim_whenIdleBelowPeak() {
        FakeTrimmable t = new FakeTrimmable(50, 16); // idle below peak demand
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 50

        opt.optimize();

        assertTrue(t.trimCalls.isEmpty());
    }

    @Test
    void optimize_floorGuard_blocksTrim_whenKeepWouldDropToFloor() {
        FakeTrimmable t = new FakeTrimmable(0, 100); // peak 0, 100 idle
        PoolOptimizer opt = optimizerFor(95, t);     // floor 95
        opt.fillAggregateWindow();

        opt.optimize();

        // toFree = 100/10 = 10, keep 90; 90 > floor(95)? no -> the floor guard blocks the trim
        assertTrue(t.trimCalls.isEmpty(), "floor guard prevents trimming below it");
    }

    @Test
    void optimize_doesNotTrim_whenIdleTooSmall() {
        FakeTrimmable t = new FakeTrimmable(0, 9); // idle < 10 -> toFree = 0
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();

        opt.optimize();

        assertTrue(t.trimCalls.isEmpty(), "below 10 idle, 1/10 rounds to 0 -> nothing trimmed");
    }

    @Test
    void optimize_convergesGraduallyAcrossTicks() {
        FakeTrimmable t = new FakeTrimmable(0, 160); // peak 0, 160 idle, floor 0
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 0

        opt.optimize(); // 160 -> free 16 -> 144
        opt.optimize(); // 144 -> free 14 -> 130
        opt.optimize(); // 130 -> free 13 -> 117

        assertEquals(List.of(16, 14, 13), t.trimCalls, "each tick frees 1/10 — gradual decay");
        assertEquals(117, t.idle);
    }

    @Test
    void optimize_appliesPerPoolFloor() {
        FakeTrimmable big = new FakeTrimmable(0, 160);   // big buffers: small floor
        FakeTrimmable small = new FakeTrimmable(0, 160); // cheap objects: large floor
        PoolOptimizer opt = new PoolOptimizer(
                List.<Trimmable>of(big, small), 300,
                (Trimmable p) -> p == big ? 20 : 155, false);
        opt.fillAggregateWindow(); // peak demand 0 for both

        opt.optimize();

        // big: toFree 16, keep 144 > floor 20 -> trim 16; small: keep 144 > floor 155? no -> no trim
        assertEquals(List.of(16), big.trimCalls, "big pool (low floor) is trimmed");
        assertEquals(144, big.idle);
        assertTrue(small.trimCalls.isEmpty(), "small pool (high floor) is left alone");
        assertEquals(160, small.idle);
    }

    @Test
    void optimize_skipsNonTrimmablePools() {
        FakeTrimmable t = new FakeTrimmable(0, 160); // lots of idle excess
        t.trimmable = false;
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();

        opt.optimize();

        assertTrue(t.trimCalls.isEmpty(), "a non-trimmable pool is never trimmed");
    }

    @Test
    void optimize_handlesMultiplePoolsIndependently() {
        FakeTrimmable a = new FakeTrimmable(0, 160);  // keep 144 > peak 0 -> trimmed
        FakeTrimmable b = new FakeTrimmable(20, 16);  // keep 15 < peak 20 -> untouched
        PoolOptimizer opt = optimizerFor(a, b);
        opt.fillAggregateWindow();          // peaks: a=0, b=20

        opt.optimize();

        assertEquals(List.of(16), a.trimCalls);
        assertTrue(b.trimCalls.isEmpty());
    }

    @Test
    void optimize_beforeAnySampling_freesAGradualSlice() {
        // empty window -> peak demand 0, so a sufficiently-large idle pool gets a 1/10 slice freed.
        FakeTrimmable t = new FakeTrimmable(0, 160);
        PoolOptimizer opt = optimizerFor(t);

        opt.optimize(); // peak 0, toFree = 160/10 = 16

        assertEquals(List.of(16), t.trimCalls);
        assertEquals(144, t.idle);
    }

    // ---------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------

    @Test
    void startedOptimizer_canBeShutDown() {
        PoolOptimizer opt = new PoolOptimizer(List.<Trimmable>of(new FakeTrimmable(1, 0)), 300, 0);
        assertTrue(opt.isRunning());

        opt.shutdown();

        assertFalse(opt.isRunning(), "shutdown stops the background scheduler");
    }
}
