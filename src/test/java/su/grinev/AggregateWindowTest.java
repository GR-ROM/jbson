package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.pool.AggregateWindow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AggregateWindow} — a fixed-size, lock-free ring buffer of
 * ints exposing {@link AggregateWindow#max()}, {@link AggregateWindow#min()},
 * {@link AggregateWindow#avg()}, {@link AggregateWindow#last()} and
 * {@link AggregateWindow#size()}.
 *
 * Writes fill index 0, 1, 2, ... and wrap around modulo the capacity. All
 * aggregates consider only the {@link AggregateWindow#size()} values written so
 * far, so they are correct while the window is still warming up. The concrete
 * write index is not observable through the public API, so these tests assert
 * position-independent results only.
 */
public class AggregateWindowTest {

    // ---------------------------------------------------------------------
    // Empty / freshly constructed window
    // ---------------------------------------------------------------------

    @Test
    void constructor_rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new AggregateWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new AggregateWindow(-1));
    }

    @Test
    void emptyWindow_size_isZero() {
        assertEquals(0, new AggregateWindow(10).size());
    }

    @Test
    void emptyWindow_max_isZero() {
        assertEquals(0, new AggregateWindow(10).max());
    }

    @Test
    void emptyWindow_min_isZero() {
        assertEquals(0, new AggregateWindow(10).min());
    }

    @Test
    void emptyWindow_avg_isZero() {
        assertEquals(0, new AggregateWindow(10).avg());
    }

    @Test
    void emptyWindow_last_isZero() {
        assertEquals(0, new AggregateWindow(10).last());
    }

    // ---------------------------------------------------------------------
    // size()
    // ---------------------------------------------------------------------

    @Test
    void size_countsWrittenValues_untilFull() {
        AggregateWindow window = new AggregateWindow(3);
        assertEquals(0, window.size());
        window.put(1);
        assertEquals(1, window.size());
        window.put(2);
        assertEquals(2, window.size());
        window.put(3);
        assertEquals(3, window.size());
    }

    @Test
    void size_pinsAtCapacity_afterWrapAround() {
        AggregateWindow window = new AggregateWindow(3);
        for (int i = 0; i < 10; i++) {
            window.put(i);
        }
        assertEquals(3, window.size(), "size never exceeds capacity");
    }

    // ---------------------------------------------------------------------
    // max()
    // ---------------------------------------------------------------------

    @Test
    void max_returnsLargestStoredValue() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(5);
        window.put(42);
        window.put(7);
        assertEquals(42, window.max());
    }

    @Test
    void max_isUnaffectedByLaterSmallerValues() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(100);
        window.put(1);
        window.put(2);
        assertEquals(100, window.max(), "a later smaller value must not lower the max");
    }

    /**
     * max() is computed only over written slots (seeded from the first value),
     * so an all-negative window reports its true maximum rather than 0.
     */
    @Test
    void max_returnsLargestValue_evenWhenAllNegative() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(-5);
        window.put(-1);
        window.put(-9);
        assertEquals(-1, window.max());
    }

    // ---------------------------------------------------------------------
    // min()
    // ---------------------------------------------------------------------

    @Test
    void min_returnsSmallestStoredValue() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(5);
        window.put(2);
        window.put(7);
        assertEquals(2, window.min());
    }

    /**
     * min() ignores the unwritten (zero) slots: with only positive values
     * stored it returns the smallest of them, not the 0 of an empty slot.
     */
    @Test
    void min_ignoresUnwrittenZeroSlots() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(5);
        window.put(8);
        assertEquals(5, window.min(), "untouched slots must not count as a 0 minimum");
    }

    @Test
    void min_handlesNegativeValues() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(-5);
        window.put(-1);
        window.put(3);
        assertEquals(-5, window.min());
    }

    // ---------------------------------------------------------------------
    // avg()
    // ---------------------------------------------------------------------

    @Test
    void avg_dividesByLiveCount_notByCapacity() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(100);
        assertEquals(100, window.avg(), "single value averaged over count 1, not capacity 10");
    }

    @Test
    void avg_isIntegerTruncated() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(5);
        window.put(4);
        // sum = 9, count = 2 -> 9 / 2 == 4 (truncated)
        assertEquals(4, window.avg());
    }

    @Test
    void avg_overSeveralValues() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(10);
        window.put(20);
        window.put(30);
        assertEquals(20, window.avg());
    }

    // ---------------------------------------------------------------------
    // last()
    // ---------------------------------------------------------------------

    @Test
    void last_returnsMostRecentValue() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(1);
        window.put(2);
        window.put(3);
        assertEquals(3, window.last());
    }

    @Test
    void last_tracksValueAcrossWrapAround() {
        AggregateWindow window = new AggregateWindow(3);
        window.put(1);
        window.put(2);
        window.put(3);
        window.put(99); // wraps to index 0
        assertEquals(99, window.last());
    }

    // ---------------------------------------------------------------------
    // percentiles: median / p95 / p99 (nearest-rank)
    // ---------------------------------------------------------------------

    @Test
    void percentiles_onEmptyWindow_areZero() {
        AggregateWindow window = new AggregateWindow(10);
        assertEquals(0, window.median());
        assertEquals(0, window.p95());
        assertEquals(0, window.p99());
    }

    /**
     * Values 1..100 stored. Nearest-rank: rank = ceil(p/100 * n).
     *  median (p50): rank 50  -> value 50
     *  p95:          rank 95  -> value 95
     *  p99:          rank 99  -> value 99
     */
    @Test
    void percentiles_over1To100() {
        AggregateWindow window = new AggregateWindow(100);
        for (int i = 1; i <= 100; i++) {
            window.put(i);
        }
        assertEquals(50, window.median());
        assertEquals(95, window.p95());
        assertEquals(99, window.p99());
    }

    @Test
    void percentiles_areOrderIndependent() {
        AggregateWindow ascending = new AggregateWindow(100);
        AggregateWindow descending = new AggregateWindow(100);
        for (int i = 1; i <= 100; i++) {
            ascending.put(i);
            descending.put(101 - i);
        }
        assertEquals(ascending.p95(), descending.p95());
        assertEquals(ascending.median(), descending.median());
    }

    @Test
    void percentile_zeroIsMin_andHundredIsMax() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(3);
        window.put(8);
        window.put(1);
        window.put(5);
        assertEquals(window.min(), window.percentile(0));
        assertEquals(window.max(), window.percentile(100));
    }

    @Test
    void percentile_onlyConsidersLiveValues_duringWarmup() {
        // capacity 100 but only 4 values written: percentiles must NOT be
        // dragged toward the unwritten zero slots.
        AggregateWindow window = new AggregateWindow(100);
        window.put(10);
        window.put(20);
        window.put(30);
        window.put(40);
        // sorted live = [10, 20, 30, 40], n = 4
        assertEquals(20, window.median(), "ceil(0.5*4)=2 -> value 20");
        assertEquals(40, window.p95(), "ceil(0.95*4)=4 -> value 40");
    }

    @Test
    void percentile_rejectsOutOfRange() {
        AggregateWindow window = new AggregateWindow(10);
        window.put(1);
        assertThrows(IllegalArgumentException.class, () -> window.percentile(-1));
        assertThrows(IllegalArgumentException.class, () -> window.percentile(101));
    }

    // ---------------------------------------------------------------------
    // Wrap-around semantics
    // ---------------------------------------------------------------------

    /**
     * With capacity N the indices used are 0, 1, ..., N-1, then wrap to 0 —
     * the Nth put never overflows. After exactly N puts every slot is written.
     */
    @Test
    void puttingCapacityValues_doesNotOverflow_fillsEverySlot() {
        int capacity = 4;
        AggregateWindow window = new AggregateWindow(capacity);
        for (int i = 1; i <= capacity; i++) {
            final int value = i;
            assertDoesNotThrow(() -> window.put(value));
        }
        assertEquals(capacity, window.size());
        assertEquals(4, window.max());
        assertEquals(1, window.min());
        assertEquals((1 + 2 + 3 + 4) / capacity, window.avg());
    }

    /**
     * After a full lap, subsequent puts overwrite the oldest slots and the new
     * value becomes the max.
     */
    @Test
    void putsBeyondCapacity_overwriteOldestSlots() {
        AggregateWindow window = new AggregateWindow(3);
        window.put(1);
        window.put(2);
        window.put(3);
        window.put(4); // wraps to index 0, overwriting the value 1
        assertEquals(4, window.max());
        assertEquals(2, window.min(), "the oldest value (1) was overwritten");
    }

    // ---------------------------------------------------------------------
    // Degenerate capacity-1 window
    // ---------------------------------------------------------------------

    @Test
    void capacityOne_overwritesSingleSlot() {
        AggregateWindow window = new AggregateWindow(1);
        window.put(7);
        assertEquals(1, window.size());
        assertEquals(7, window.max());
        assertEquals(7, window.avg());
        assertEquals(7, window.last());

        window.put(3); // overwrites the single slot
        assertEquals(1, window.size());
        assertEquals(3, window.max());
        assertEquals(3, window.last());
    }

    // ---------------------------------------------------------------------
    // Concurrency: writes from many threads must not corrupt the window
    // ---------------------------------------------------------------------

    /**
     * Hammer a window from several writer threads while readers aggregate.
     * With the AtomicIntegerArray backing store there must be no torn values,
     * out-of-range indices, or exceptions; every observed value must be one of
     * the legitimately written ones and size must stay pinned at capacity.
     */
    @Test
    void concurrentWritesAndReads_stayConsistent() throws Exception {
        int capacity = 64;
        AggregateWindow window = new AggregateWindow(capacity);
        int writers = 8;
        int putsPerWriter = 50_000;
        int writtenValue = 777; // every writer stores the same legal value

        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        try {
            Future<?>[] tasks = new Future<?>[writers + 2];
            for (int w = 0; w < writers; w++) {
                tasks[w] = pool.submit(() -> {
                    for (int i = 0; i < putsPerWriter; i++) {
                        window.put(writtenValue);
                    }
                });
            }
            // concurrent readers: must never throw and never see a stray value
            for (int r = 0; r < 2; r++) {
                tasks[writers + r] = pool.submit(() -> {
                    for (int i = 0; i < putsPerWriter; i++) {
                        int max = window.max();
                        int min = window.min();
                        assertTrue(max == 0 || max == writtenValue);
                        assertTrue(min == 0 || min == writtenValue);
                    }
                });
            }
            for (Future<?> t : tasks) {
                t.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(capacity, window.size());
        assertEquals(writtenValue, window.max());
        assertEquals(writtenValue, window.min());
        assertEquals(writtenValue, window.avg());
    }
}
