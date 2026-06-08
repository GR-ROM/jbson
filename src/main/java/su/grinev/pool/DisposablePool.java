package su.grinev.pool;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class DisposablePool<T extends Disposable> extends BasePool<T> {
    private final Supplier<T> supplier;

    public DisposablePool(String name, AtomicInteger counter, int initialSize, int limit, int timeoutMs, boolean blocking, Supplier<T> supplier) {
        super(name, counter, initialSize, limit, timeoutMs, blocking);
        this.supplier = supplier;
        for (int i = 0; i < initialSize; i++) {
            prefill(supply());
        }
    }

    protected T supply() {
        super.currentPoolSize.incrementAndGet();
        T t = supplier.get();
        t.setOnDispose(() -> release(t));
        return t;
    }
}