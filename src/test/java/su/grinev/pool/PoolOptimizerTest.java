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

    @Test
    void optimize_trimsIdleDownToPeakDemand() {
        FakeTrimmable t = new FakeTrimmable(10, 50); // peak demand 10, 50 idle
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // observed peak demand = 10

        opt.optimize();

        assertEquals(List.of(40), t.trimCalls, "trims idle excess over peak demand: 50 - 10");
        assertEquals(10, t.idle, "keeps just enough idle to cover the peak demand");
    }

    @Test
    void optimize_doesNotTrim_whenIdleNotAbovePeak() {
        FakeTrimmable t = new FakeTrimmable(30, 30); // idle == peak demand
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 30

        opt.optimize();

        assertTrue(t.trimCalls.isEmpty(), "no idle excess over peak demand -> nothing trimmed");
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
    void optimize_doesNotTrimBelowMinPoolSize() {
        FakeTrimmable t = new FakeTrimmable(5, 100); // peak demand 5, 100 idle
        PoolOptimizer opt = optimizerFor(30, t);     // keep at least 30 idle
        opt.fillAggregateWindow();          // peak demand = 5

        opt.optimize();

        // idle excess over peak is 95, but the floor allows trimming only 100 - 30 = 70
        assertEquals(List.of(70), t.trimCalls);
        assertEquals(30, t.idle, "never shrinks idle below minPoolSize");
    }

    @Test
    void optimize_trimsToPeak_whenPeakAboveMinPoolSize() {
        FakeTrimmable t = new FakeTrimmable(50, 100); // peak demand 50
        PoolOptimizer opt = optimizerFor(20, t);      // floor 20 (below peak)
        opt.fillAggregateWindow();          // peak demand = 50

        opt.optimize();

        // peak (50) dominates the floor (20): trim idle down to 50
        assertEquals(List.of(50), t.trimCalls);
        assertEquals(50, t.idle);
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

        assertEquals(List.of(80), big.trimCalls, "big pool trimmed down to its floor 20");
        assertEquals(20, big.idle);
        assertEquals(List.of(20), small.trimCalls, "small pool trimmed down to its floor 80");
        assertEquals(80, small.idle);
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
        FakeTrimmable a = new FakeTrimmable(10, 50); // 40 idle excess over peak 10
        FakeTrimmable b = new FakeTrimmable(30, 30); // idle == peak, no excess
        PoolOptimizer opt = optimizerFor(a, b);
        opt.fillAggregateWindow();          // peaks: a=10, b=30

        opt.optimize();

        assertEquals(List.of(40), a.trimCalls);
        assertTrue(b.trimCalls.isEmpty());
    }

    @Test
    void optimize_beforeAnySampling_treatsAllIdleAsExcess() {
        // empty window -> peak demand 0, so all idle objects look like excess.
        FakeTrimmable t = new FakeTrimmable(0, 5);
        PoolOptimizer opt = optimizerFor(t);

        opt.optimize(); // no samples yet: peak == 0, idle 5 > 0 -> trims 5

        assertEquals(List.of(5), t.trimCalls,
                "with no samples the peak demand is 0, so all idle looks like excess");
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
