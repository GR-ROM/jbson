package su.grinev.pool;

import lombok.Getter;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

public abstract class BasePool<T> {
    protected final AtomicInteger counter = new AtomicInteger(0);
    @Getter
    protected final AtomicInteger currentPoolSize;
    private final AtomicReferenceArray<T> poolArray;
    private final AtomicInteger poolHead = new AtomicInteger(0);

    public int getInFlight() { return counter.get(); }
    public int getAvailable() { return poolHead.get(); }
    protected final ConcurrentLinkedDeque<Thread> waiters;
    protected int limit;
    protected int initalSize;
    protected final int timeoutMs;
    protected final boolean blocking;
    public final String name;

    public BasePool(String name, AtomicInteger currentPoolSize, int initialSize, int limit, int timeoutMs, boolean blocking) {
        this.name = name;
        this.currentPoolSize = currentPoolSize;
        this.limit = limit;
        this.initalSize = initialSize;
        this.timeoutMs = timeoutMs;
        this.blocking = blocking;
        this.waiters = new ConcurrentLinkedDeque<>();
        this.poolArray = new AtomicReferenceArray<>(Math.max(limit, 16));
    }

    protected abstract T supply();

    public T get() {
        int spins = 3;
        long deadlineNanos = 0;

        while (true) {
            int cur = counter.get();
            if (cur < limit && counter.compareAndSet(cur, cur + 1)) {
                T obj = pollFromPool();
                if (obj == null) {
                    // TOCTOU window: addToPool claimed head slot but hasn't written yet.
                    // Spin briefly; if still null, supply() — one extra alloc vs. losing the object.
                    for (int spin = 0; spin < 64 && obj == null; spin++) {
                        Thread.onSpinWait();
                        obj = pollFromPool();
                    }
                }
                return obj != null ? obj : supply();
            }

            if (!blocking) {
                throw new IllegalStateException("Pool '" + name + "' overflow (limit=" + limit + ")");
            }

            if (--spins > 0) {
                Thread.onSpinWait();
                continue;
            }

            Thread me = Thread.currentThread();
            waiters.add(me);

            cur = counter.get();
            if (cur < limit && counter.compareAndSet(cur, cur + 1)) {
                waiters.remove(me);
                T obj = pollFromPool();
                if (obj == null) {
                    for (int spin = 0; spin < 64 && obj == null; spin++) {
                        Thread.onSpinWait();
                        obj = pollFromPool();
                    }
                }
                return obj != null ? obj : supply();
            }

            if (timeoutMs > 0) {
                if (deadlineNanos == 0) {
                    deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    waiters.remove(me);
                    throw new IllegalStateException("Pool '" + name + "' timeout after " + timeoutMs + "ms (limit=" + limit + ")");
                }
                LockSupport.parkNanos(this, remaining);
            } else {
                LockSupport.park(this);
            }
            spins = 3;
        }
    }

    public void release(T t) {
        int c = counter.decrementAndGet();
        if (c < 0) {
            counter.incrementAndGet();
            throw new IllegalStateException("Double release detected in pool '" + name + "'");
        }

        addToPool(t);

        Thread w = waiters.poll();
        if (w != null) {
            LockSupport.unpark(w);
        }
    }

    protected void prefill(T item) {
        addToPool(item);
    }

    /**
     * Lock-free stack push. Head is advanced AFTER the write so poll
     * never observes a null in an occupied slot.
     *
     * Algorithm:
     *  1. Read head h. If array full, drop (caller already decremented counter
     *     so the slot is logically "free" — the item itself is just discarded).
     *  2. CAS poolArray[h]: null → item.  If another producer beat us to slot h,
     *     spin and retry with fresh h.
     *  3. CAS poolHead: h → h+1.  If head moved (consumer popped h-1 or another
     *     producer advanced), our item at slot h is still below the new head only
     *     if newHead > h.  If newHead <= h the item is above the stack top and
     *     invisible — clear slot h and retry from top.
     */
    private void addToPool(T item) {
        while (true) {
            int h = poolHead.get();
            if (h >= poolArray.length()) {
                return; // array full — drop; counter already decremented
            }
            if (!poolArray.compareAndSet(h, null, item)) {
                // Slot h already taken by another producer. Spin and retry.
                Thread.onSpinWait();
                continue;
            }
            // Item is written to slot h. Now publish by advancing head.
            if (poolHead.compareAndSet(h, h + 1)) {
                return; // success
            }
            // Head moved. Check if our slot is still reachable.
            int newHead = poolHead.get();
            if (newHead > h) {
                // Another producer advanced head past h — item is reachable. Done.
                return;
            }
            // Head went backward (consumer popped). Our slot h is above the new head
            // and invisible. Clear it and retry from current top.
            poolArray.compareAndSet(h, item, null);
        }
    }

    /**
     * Lock-free stack pop. Reads from slot head-1 after CAS-decrementing head.
     * Because addToPool now writes data BEFORE advancing head, slot head-1 is
     * guaranteed non-null in the common case.  The only exception is the brief
     * window in addToPool after CAS on the array but before CAS on head — in that
     * edge case we return null (caller will retry or call supply()).
     */
    private T pollFromPool() {
        int h;
        do {
            h = poolHead.get();
            if (h <= 0) {
                return null;
            }
        } while (!poolHead.compareAndSet(h, h - 1));
        return poolArray.getAndSet(h - 1, null);
    }
}
