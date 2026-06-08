package su.grinev.pool;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class Pool<T> extends BasePool<T> {
    private final Supplier<T> supplier;

    public Pool(String name, AtomicInteger counter, int initialSize, int limit, int timeoutMs, boolean blocking, Supplier<T> supplier) {
        super(name, counter, initialSize, limit, timeoutMs, blocking);
        this.supplier = supplier;

        for (int i = 0; i < initialSize; i++) {
            prefill(supply());
        }
    }

    @Override
    protected T supply() {
        super.currentPoolSize.incrementAndGet();
        return supplier.get();
    }
}
