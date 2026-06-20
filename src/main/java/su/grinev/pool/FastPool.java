package su.grinev.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * High-performance object pool backed by an {@link ArrayBlockingQueue}.
 *
 * Under contention this dramatically outperforms a lock-free CAS stack (see
 * PoolBenchmark): contending threads park briefly on a single short-held lock
 * instead of spinning on multiple contended atomics.
 *
 * Concurrency limit: when {@code blocking} is enabled, at most {@code maxSize}
 * objects may be in flight at once — further {@link #get()} calls block (up to
 * {@code timeoutMs}, or indefinitely if {@code timeoutMs <= 0}) until a release.
 * When blocking is disabled there is no limit: {@link #get()} always succeeds,
 * creating a fresh object if the pool is empty (the fast path — no semaphore).
 */
public class FastPool<T> implements Trimmable {
    public final String name;
    private final ArrayBlockingQueue<T> pool;
    private final Supplier<T> supplier;
    private final Consumer<T> destroyer;
    private final int maxSize;
    private final boolean blocking;
    private final int timeoutMs;
    private final Semaphore permits;            // non-null only when blocking — enforces the in-flight limit
    private final AtomicInteger isUse = new AtomicInteger(0);
    private final AtomicLong totalCreated = new AtomicLong(0);

    public FastPool(String name, Supplier<T> supplier, Consumer<T> destroyer,
                    int initialSize, int maxSize, boolean blocking, int timeoutMs) {
        this.name = name;
        this.pool = new ArrayBlockingQueue<>(Math.max(maxSize, 1));
        this.supplier = supplier;
        this.destroyer = destroyer;
        this.maxSize = maxSize;
        this.blocking = blocking;
        this.timeoutMs = timeoutMs;
        this.permits = blocking ? new Semaphore(maxSize) : null;

        for (int i = 0; i < initialSize; i++) {
            pool.offer(supplier.get());
            totalCreated.incrementAndGet();
        }
    }

    public T get() {
        if (permits != null) {
            acquirePermit();
        }
        isUse.incrementAndGet();
        T item = pool.poll();
        if (item != null) {
            return item;
        }
        totalCreated.incrementAndGet();
        return supplier.get();
    }

    private void acquirePermit() {
        if (permits.tryAcquire()) {
            return; // fast path: a permit was immediately available (no parking, just a CAS)
        }
        if (timeoutMs > 0) {
            try {
                if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Pool '" + name + "' timeout after " + timeoutMs + "ms (limit=" + maxSize + ")");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Pool '" + name + "' interrupted while waiting for a free object", e);
            }
        } else {
            permits.acquireUninterruptibly();
        }
    }

    public void release(T item) {
        if (isUse.decrementAndGet() < 0) {
            // More releases than gets — a double release (or releasing a non-borrowed object).
            isUse.incrementAndGet();
            throw new IllegalStateException("Double release detected in pool '" + name + "'");
        }
        pool.offer(item);
        if (permits != null) {
            permits.release();
        }
    }

    // --- Trimmable ---

    /** Objects currently in use (checked out) — the demand signal sampled by the optimizer. */
    @Override
    public int getCount() {
        return isUse.get();
    }

    /** Objects currently idle (available) in the pool. */
    @Override
    public int getIdle() {
        return pool.size();
    }

    @Override
    public boolean isTrimmable() {
        return true;
    }

    @Override
    public boolean trim(int size) {
        List<T> items = new ArrayList<>(size);
        int drained = pool.drainTo(items, size);
        if (drained < size) {
            items.forEach(pool::offer);
            return false;
        }
        items.forEach(destroyer);
        return true;
    }

    // --- BasePool-compatible accessors (read-only) ---

    public int getInFlight() {
        return isUse.get();
    }

    public int getAvailable() {
        return pool.size();
    }

    /** Owned objects: in use + idle in the pool. */
    public int getCurrentPoolSize() {
        return isUse.get() + pool.size();
    }

    public int size() {
        return pool.size();
    }

    public long getTotalCreated() {
        return totalCreated.get();
    }
}
