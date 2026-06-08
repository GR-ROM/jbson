package su.grinev.pool;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class PoolFactory {

    private int maxPoolSize;
    private int minPoolSize;
    private int outOfPoolTimeout;
    private boolean blocking;
    private final Map<String, BasePool> pools = new ConcurrentHashMap<>();
    private final AtomicInteger poolCounter = new AtomicInteger(0);

    public PoolFactory(int maxPoolSize, int minPoolSize, int outOfPoolTimeout, boolean blocking) {
        this.maxPoolSize = maxPoolSize;
        this.minPoolSize = minPoolSize;
        this.outOfPoolTimeout = outOfPoolTimeout;
        this.blocking = blocking;
    }

    public PoolFactory() {

    }

    public Map<String, BasePool> getPools() {
        return new HashMap<>(pools);
    }

    public <T> Pool<T> getPool(Supplier<T> supplier) {
        String name = "Pool-" + poolCounter.getAndIncrement();
        return getPool(name, supplier);
    }

    public <T> Pool<T> getPool(String name, Supplier<T> supplier) {
        Pool<T> pool = new Pool<>(name, new AtomicInteger(0), minPoolSize, maxPoolSize, outOfPoolTimeout, blocking, supplier);
        pools.put(name, pool);
        return pool;
    }

    public <T> FastPool<T> getFastPool(Supplier<T> supplier) {
        return new FastPool<>(supplier, minPoolSize, maxPoolSize);
    }

    public <T> FastPool<T> getFastPool(String name, Supplier<T> supplier) {
        return new FastPool<>(supplier, minPoolSize, maxPoolSize);
    }

    public <T extends Disposable> DisposablePool<T> getDisposablePool(Supplier<T> supplier) {
        String name = "DisposablePool-" + poolCounter.getAndIncrement();
        return getDisposablePool(name, supplier);
    }

    public <T extends Disposable> DisposablePool<T> getDisposablePool(String name, Supplier<T> supplier) {
        DisposablePool<T> disposablePool = new DisposablePool<>(name, new AtomicInteger(0), minPoolSize, maxPoolSize, outOfPoolTimeout, blocking, supplier);
        pools.put(name, disposablePool);
        return disposablePool;
    }

    public static class Builder {

        private final PoolFactory instance = new PoolFactory();

        public static Builder builder() {
            return new Builder();
        }

        public Builder setMaxPoolSize(int maxPoolSize) {
            instance.maxPoolSize = maxPoolSize;
            return this;
        }

        public Builder setMinPoolSize(int minPoolSize) {
            instance.minPoolSize = minPoolSize;
            return this;
        }

        public Builder setOutOfPoolTimeout(int outOfPoolTimeout) {
            instance.outOfPoolTimeout = outOfPoolTimeout;
            return this;
        }

        public Builder setBlocking(boolean blocking) {
            instance.blocking = blocking;
            return this;
        }

        public PoolFactory build() {
            return instance;
        }
    }
}
