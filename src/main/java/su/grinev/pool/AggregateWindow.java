package su.grinev.pool;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Fixed-size ring buffer of ints with lock-free aggregates over the values
 * currently stored.
 *
 * Concurrency: the backing store is an {@link AtomicIntegerArray}, so every
 * slot read/write carries volatile semantics. A reader thread is guaranteed to
 * observe the last published value of each slot (no stale reads, no torn ints).
 * Aggregates are still computed without a global lock, so a read concurrent
 * with writes returns a consistent-but-time-smeared snapshot — appropriate for
 * approximate monitoring metrics.
 *
 * Writes fill index 0, 1, 2, ... and wrap around modulo the capacity. Aggregates
 * only consider the {@link #size()} values written so far, so they are correct
 * while the window is still warming up (not yet full).
 */
public class AggregateWindow {
    private final AtomicInteger position = new AtomicInteger(0);
    private final AtomicInteger size = new AtomicInteger(0);
    private final AtomicIntegerArray window;

    public AggregateWindow(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be greater than 0.");
        }
        window = new AtomicIntegerArray(maxCapacity);
        position.set(0);
        size.set(0);
    }

    public void put(int value) {
        int idx = position.getAndUpdate(x -> (x + 1) % window.length());
        window.set(idx, value);
        // grow the live count until the window is full, then leave it pinned at capacity.
        size.updateAndGet(x -> x < window.length() ? x + 1 : x);
    }

    /** Number of values currently stored (0..capacity). */
    public int size() {
        return size.get();
    }

    /** Largest stored value, or 0 if the window is empty. */
    public int max() {
        int n = size.get();
        if (n == 0) {
            return 0;
        }
        int max = window.get(0);
        for (int i = 1; i < n; i++) {
            int v = window.get(i);
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    /** Smallest stored value, or 0 if the window is empty. */
    public int min() {
        int n = size.get();
        if (n == 0) {
            return 0;
        }
        int min = window.get(0);
        for (int i = 1; i < n; i++) {
            int v = window.get(i);
            if (v < min) {
                min = v;
            }
        }
        return min;
    }

    /** Arithmetic mean of stored values (integer-truncated), or 0 if empty. */
    public int avg() {
        int n = size.get();
        if (n == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += window.get(i);
        }
        return (int) (sum / n);
    }

    /** Most recently written value, or 0 if the window is empty. */
    public int last() {
        if (size.get() == 0) {
            return 0;
        }
        int idx = (position.get() - 1 + window.length()) % window.length();
        return window.get(idx);
    }

    /** Median (50th percentile) of stored values, or 0 if the window is empty. */
    public int median() {
        return percentile(50.0);
    }

    /** 95th percentile of stored values, or 0 if the window is empty. */
    public int p95() {
        return percentile(95.0);
    }

    /** 99th percentile of stored values, or 0 if the window is empty. */
    public int p99() {
        return percentile(99.0);
    }

    /**
     * The given percentile of the stored values using the nearest-rank method,
     * or 0 if the window is empty.
     *
     * A point-in-time snapshot of the live values is copied out and sorted, so
     * the result is self-consistent even under concurrent writes (it reflects a
     * smear of values observed during the copy, which is fine for monitoring).
     *
     * @param p percentile in the range [0, 100]
     */
    public int percentile(double p) {
        if (p < 0.0 || p > 100.0) {
            throw new IllegalArgumentException("percentile must be in [0, 100], was " + p);
        }
        int n = size.get();
        if (n == 0) {
            return 0;
        }
        int[] snapshot = new int[n];
        for (int i = 0; i < n; i++) {
            snapshot[i] = window.get(i);
        }
        Arrays.sort(snapshot);
        // nearest-rank: rank = ceil(p/100 * n), clamped to [1, n]
        int rank = (int) Math.ceil(p / 100.0 * n);
        if (rank < 1) {
            rank = 1;
        } else if (rank > n) {
            rank = n;
        }
        return snapshot[rank - 1];
    }
}
