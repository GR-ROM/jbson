package su.grinev.pool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * High-performance lock-free object pool.
 * Uses ArrayBlockingQueue for correct concurrent get/release without TOCTOU races.
 */
public class FastPool<T> {
    private final ArrayBlockingQueue<T> pool;
    private final Supplier<T> supplier;
    private final AtomicLong totalCreated = new AtomicLong(0);

    public FastPool(Supplier<T> supplier, int initialSize, int maxSize) {
        this.pool = new ArrayBlockingQueue<>(maxSize);
        this.supplier = supplier;

        for (int i = 0; i < initialSize; i++) {
            pool.offer(supplier.get());
            totalCreated.incrementAndGet();
        }
    }

    public T get() {
        T item = pool.poll();
        if (item != null) {
            return item;
        }
        totalCreated.incrementAndGet();
        return supplier.get();
    }

    public void release(T item) {
        pool.offer(item);
    }

    public int size() {
        return pool.size();
    }

    public long getTotalCreated() {
        return totalCreated.get();
    }
}
