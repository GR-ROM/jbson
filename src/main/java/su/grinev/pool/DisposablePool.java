package su.grinev.pool;

import java.util.function.Supplier;

/**
 * A {@link FastPool} of {@link Disposable} objects.
 *
 * Borrowed objects get an {@code onDispose} hook so {@code dispose()} returns
 * them to the pool. Idle objects removed by {@link #trim(int)} are permanently
 * released via {@link Disposable#destroy()} (e.g. closing an arena), so the pool
 * shrinks its real footprint during long idle periods.
 */
public class DisposablePool<T extends Disposable> extends FastPool<T> {

    public DisposablePool(String name, Supplier<T> supplier,
                          int initialSize, int maxSize, boolean blocking, int timeoutMs) {
        super(name, supplier, Disposable::destroy, initialSize, maxSize, blocking, timeoutMs);
    }

    @Override
    public T get() {
        T t = super.get();
        t.setOnDispose(() -> release(t));
        return t;
    }
}
