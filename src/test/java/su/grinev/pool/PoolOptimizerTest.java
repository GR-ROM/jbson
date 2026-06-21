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

    // Strategy: keep = idle / 2; trim (idle - keep) only when keep > peak AND keep > floor.
    // i.e. halve idle per tick as long as the retained half still exceeds peak demand and the floor.

    @Test
    void optimize_halvesIdle_whenHalfStillExceedsPeak() {
        FakeTrimmable t = new FakeTrimmable(10, 50); // peak demand 10, 50 idle
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // observed peak demand = 10

        opt.optimize();

        // keep = 50/2 = 25; 25 > peak(10) and > floor(0) -> trim 50-25
        assertEquals(List.of(25), t.trimCalls, "halves idle (gradual decay)");
        assertEquals(25, t.idle);
    }

    @Test
    void optimize_doesNotTrim_whenHalfNotAbovePeak() {
        FakeTrimmable t = new FakeTrimmable(50, 100); // peak demand 50, 100 idle
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 50

        opt.optimize();

        // keep = 50; 50 > peak(50)? no -> nothing trimmed
        assertTrue(t.trimCalls.isEmpty(), "won't halve when the retained half wouldn't exceed peak demand");
    }

    @Test
    void optimize_doesNotTrim_whenIdleBelowPeak() {
        FakeTrimmable t = new FakeTrimmable(50, 20); // idle below peak demand
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 50

        opt.optimize();

        assertTrue(t.trimCalls.isEmpty());
    }

    @Test
    void optimize_floorGuard_blocksHalving_whenHalfWouldDropToFloor() {
        FakeTrimmable t = new FakeTrimmable(5, 100); // peak 5, 100 idle
        PoolOptimizer opt = optimizerFor(60, t);     // floor 60
        opt.fillAggregateWindow();          // peak demand = 5

        opt.optimize();

        // keep = 50; 50 > floor(60)? no -> the floor guard blocks the halving
        assertTrue(t.trimCalls.isEmpty(), "floor guard prevents halving below it");
    }

    @Test
    void optimize_halvingConvergesAcrossTicks() {
        FakeTrimmable t = new FakeTrimmable(0, 100); // peak 0, 100 idle, floor 0
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 0

        opt.optimize(); // 100 -> keep 50 -> trim 50
        opt.optimize(); //  50 -> keep 25 -> trim 25
        opt.optimize(); //  25 -> keep 12 -> trim 13

        assertEquals(List.of(50, 25, 13), t.trimCalls, "each tick halves the idle pool");
        assertEquals(12, t.idle);
    }

    @Test
    void optimize_appliesPerPoolFloor() {
        FakeTrimmable big = new FakeTrimmable(0, 100);   // big buffers: small floor
        FakeTrimmable small = new FakeTrimmable(0, 100); // cheap objects: large floor
        PoolOptimizer opt = new PoolOptimizer(
                List.<Trimmable>of(big, small), 300,
                (Trimmable p) -> p == big ? 20 : 80, false);
        opt.fillAggregateWindow(); // peak demand 0 for both

        opt.optimize();

        // big: keep 50 > floor 20 -> trim 50; small: keep 50 > floor 80? no -> no trim
        assertEquals(List.of(50), big.trimCalls, "big pool (low floor) is halved");
        assertEquals(50, big.idle);
        assertTrue(small.trimCalls.isEmpty(), "small pool (high floor) is left alone");
        assertEquals(100, small.idle);
    }

    @Test
    void optimize_skipsNonTrimmablePools() {
        FakeTrimmable t = new FakeTrimmable(0, 100); // lots of idle excess
        t.trimmable = false;
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();

        opt.optimize();

        assertTrue(t.trimCalls.isEmpty(), "a non-trimmable pool is never trimmed");
    }

    @Test
    void optimize_handlesMultiplePoolsIndependently() {
        FakeTrimmable a = new FakeTrimmable(10, 50); // half (25) > peak 10 -> halved
        FakeTrimmable b = new FakeTrimmable(30, 30); // half (15) < peak 30 -> untouched
        PoolOptimizer opt = optimizerFor(a, b);
        opt.fillAggregateWindow();          // peaks: a=10, b=30

        opt.optimize();

        assertEquals(List.of(25), a.trimCalls);
        assertTrue(b.trimCalls.isEmpty());
    }

    @Test
    void optimize_beforeAnySampling_halvesIdle() {
        // empty window -> peak demand 0, so a non-empty idle pool gets halved.
        FakeTrimmable t = new FakeTrimmable(0, 5);
        PoolOptimizer opt = optimizerFor(t);

        opt.optimize(); // peak 0, keep = 5/2 = 2; 2 > 0 -> trim 5-2

        assertEquals(List.of(3), t.trimCalls);
        assertEquals(2, t.idle);
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
