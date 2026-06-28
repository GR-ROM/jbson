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
 * Model: each second the in-use count ({@link Trimmable#getCountInUse()}) is sampled
 * into a rolling window; on the idle tick, if the pool holds more idle objects
 * ({@link Trimmable#getIdle()}) than the peak demand, the excess idle objects are
 * trimmed down to {@code max(peakInUse, minPoolSize)}.
 */
public class PoolOptimizerTest {

    /** A controllable {@link Trimmable}: independent in-use and idle counts; records trim requests. */
    static final class FakeTrimmable implements Trimmable {
        int inUse;
        int idle;
        int minSize;
        boolean trimmable = true;
        final List<Integer> trimCalls = new ArrayList<>();

        FakeTrimmable(int inUse, int idle) {
            this(inUse, idle, 0);
        }

        FakeTrimmable(int inUse, int idle, int minSize) {
            this.inUse = inUse;
            this.idle = idle;
            this.minSize = minSize;
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public int getCountInUse() {
            return inUse;
        }

        @Override
        public int getIdle() {
            return idle;
        }

        @Override
        public int getMinSize() {
            return minSize;
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

    // Strategy: toFree = max(idle / 50, 1); trim toFree only when (idle - toFree) > peak AND > floor.
    // i.e. free ~1/50 of idle per tick (gentle decay, at least one) as long as the retained
    // remainder still exceeds peak and floor.

    @Test
    void optimize_freesAGradualSlice_whenSafe() {
        FakeTrimmable t = new FakeTrimmable(0, 160); // peak 0, 160 idle, floor 0
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // observed peak demand = 0

        opt.optimize();

        // toFree = max(160/50, 1) = 3; keep 157 > peak(0) and > floor(0)
        assertEquals(List.of(3), t.trimCalls, "frees ~1/50 of idle (gentle decay)");
        assertEquals(157, t.idle);
    }

    @Test
    void optimize_usesMax_holdsPeakAgainstIsolatedSpikes() {
        FakeTrimmable t = new FakeTrimmable(0, 160); // 160 idle
        PoolOptimizer opt = optimizerFor(t);
        // 199 low-demand samples (in-use 5) and one spike (150): max() == 150, so the pool is
        // held at the peak — even a lone burst keeps it sized until that peak ages out of the window.
        t.inUse = 5;
        for (int i = 0; i < 199; i++) {
            opt.fillAggregateWindow();
        }
        t.inUse = 158;
        opt.fillAggregateWindow();

        opt.optimize();

        // toFree = max(160/50, 1) = 3; keep = 157, but 157 < max(158) -> does NOT trim (peak held).
        assertEquals(List.of(), t.trimCalls, "max() keeps the pool sized for the worst burst, ignoring nothing");
        assertEquals(160, t.idle);
    }

    @Test
    void optimize_doesNotTrim_whenKeepWouldDropBelowPeak() {
        FakeTrimmable t = new FakeTrimmable(20, 16); // peak 20, idle 16
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 20

        opt.optimize();

        // toFree = max(16/50, 1) = 1, keep 15; 15 > peak(20)? no -> nothing trimmed
        assertTrue(t.trimCalls.isEmpty(), "won't trim when the retained remainder wouldn't exceed peak demand");
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
        PoolOptimizer opt = optimizerFor(99, t);     // floor 99
        opt.fillAggregateWindow();

        opt.optimize();

        // toFree = max(100/50, 1) = 2, keep 98; 98 > floor(99)? no -> the floor guard blocks the trim
        assertTrue(t.trimCalls.isEmpty(), "floor guard prevents trimming below it");
    }

    @Test
    void optimize_drainsSmallPoolByOne_whenSliceRoundsToZero() {
        FakeTrimmable t = new FakeTrimmable(0, 9); // idle 9 -> 9/50 rounds to 0, floored to 1
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();

        opt.optimize();

        // toFree = max(9/50, 1) = 1, keep 8 > peak(0) and > floor(0) -> small pools still drain
        assertEquals(List.of(1), t.trimCalls, "the floor-of-1 keeps tiny excess pools draining");
        assertEquals(8, t.idle);
    }

    @Test
    void optimize_convergesGraduallyAcrossTicks() {
        FakeTrimmable t = new FakeTrimmable(0, 160); // peak 0, 160 idle, floor 0
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak demand = 0

        opt.optimize(); // 160 -> free 3 -> 157
        opt.optimize(); // 157 -> free 3 -> 154
        opt.optimize(); // 154 -> free 3 -> 151

        assertEquals(List.of(3, 3, 3), t.trimCalls, "each tick frees ~1/50 — gentle decay");
        assertEquals(151, t.idle);
    }

    @Test
    void optimize_appliesPerPoolFloor() {
        FakeTrimmable big = new FakeTrimmable(0, 160);   // big buffers: small floor
        FakeTrimmable small = new FakeTrimmable(0, 160); // cheap objects: large floor
        PoolOptimizer opt = new PoolOptimizer(
                List.<Trimmable>of(big, small), 300,
                (Trimmable p) -> p == big ? 20 : 158, false);
        opt.fillAggregateWindow(); // peak demand 0 for both

        opt.optimize();

        // big: toFree 3, keep 157 > floor 20 -> trim 3; small: keep 157 > floor 158? no -> no trim
        assertEquals(List.of(3), big.trimCalls, "big pool (low floor) is trimmed");
        assertEquals(157, big.idle);
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
        FakeTrimmable a = new FakeTrimmable(0, 160);  // keep 157 > peak 0 -> trimmed
        FakeTrimmable b = new FakeTrimmable(20, 16);  // keep 15 < peak 20 -> untouched
        PoolOptimizer opt = optimizerFor(a, b);
        opt.fillAggregateWindow();          // peaks: a=0, b=20

        opt.optimize();

        assertEquals(List.of(3), a.trimCalls);
        assertTrue(b.trimCalls.isEmpty());
    }

    @Test
    void optimize_beforeAnySampling_freesAGradualSlice() {
        // empty window -> peak demand 0, so a sufficiently-large idle pool gets a ~1/50 slice freed.
        FakeTrimmable t = new FakeTrimmable(0, 160);
        PoolOptimizer opt = optimizerFor(t);

        opt.optimize(); // peak 0, toFree = max(160/50, 1) = 3

        assertEquals(List.of(3), t.trimCalls);
        assertEquals(157, t.idle);
    }

    // ---------------------------------------------------------------------
    // minSize floor — never trim a pool below its own initial/baseline size
    // ---------------------------------------------------------------------

    @Test
    void optimize_doesNotTrim_whenOwnedAtMinSize() {
        FakeTrimmable t = new FakeTrimmable(0, 100, 100); // owned 100 == minSize
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();

        opt.optimize();

        assertTrue(t.trimCalls.isEmpty(), "a pool at its baseline size is never trimmed");
        assertEquals(100, t.idle);
    }

    @Test
    void optimize_doesNotTrim_whenOwnedBelowMinSize() {
        FakeTrimmable t = new FakeTrimmable(0, 40, 100); // owned 40 < minSize (e.g. still warming up)
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();

        opt.optimize();

        assertTrue(t.trimCalls.isEmpty());
    }

    @Test
    void optimize_trimsFullyIdlePool_downTowardMinSize() {
        // Regression: a fully idle pool (in-use 0) with excess above its baseline must still be
        // reclaimed — an in-use-only guard would wrongly never trim it.
        FakeTrimmable t = new FakeTrimmable(0, 200, 100); // owned 200 > minSize 100
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak 0

        opt.optimize();

        // toFree = min(max(200/50,1), owned-minSize=100) = 4; keep 196 > peak 0 and floor 0
        assertEquals(List.of(4), t.trimCalls);
        assertEquals(196, t.idle);
    }

    @Test
    void optimize_clampsToFree_soOwnedNeverDropsBelowMinSize() {
        FakeTrimmable t = new FakeTrimmable(0, 100, 99); // owned 100, only 1 above minSize
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();

        opt.optimize();

        // raw toFree = max(100/50,1)=2, clamped to owned-minSize=1 so owned can't dip below 99
        assertEquals(List.of(1), t.trimCalls, "toFree is clamped to the room above minSize");
        assertEquals(99, t.idle);
    }

    @Test
    void optimize_convergesToMinSize_thenStops() {
        FakeTrimmable t = new FakeTrimmable(0, 102, 100);
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();

        opt.optimize(); // owned 102 -> free min(2,2)=2 -> owned 100
        opt.optimize(); // owned 100 == minSize -> no trim

        assertEquals(List.of(2), t.trimCalls);
        assertEquals(100, t.idle);
    }

    @Test
    void optimize_ownedCountsInUseTowardBaseline() {
        // owned = in-use + idle; the baseline guard counts both, so a pool whose idle alone is below
        // minSize can still be trimmed when in-use pushes owned above it.
        FakeTrimmable t = new FakeTrimmable(60, 70, 100); // idle 70 < minSize, but owned 130 > minSize
        PoolOptimizer opt = optimizerFor(t);
        opt.fillAggregateWindow();          // peak = in-use 60

        opt.optimize();

        // toFree = min(max(70/50,1)=1, owned-minSize=30)=1; keep 69 > peak 60 and floor 0
        assertEquals(List.of(1), t.trimCalls);
        assertEquals(69, t.idle);
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
