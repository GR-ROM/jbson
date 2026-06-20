package su.grinev.pool;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Randomized, time-boxed chaos / stress test for {@link FastPool}: many worker
 * threads hammer get()/release() with randomized batch sizes and hold times
 * while a separate thread concurrently trims the pool by random amounts — all
 * racing on one shared pool for a fixed wall-clock duration.
 *
 * Invariants checked under the storm:
 *  - an object is never handed to two threads at once (no aliasing / corruption);
 *  - a trimmed (destroyed) object is never handed back out;
 *  - in-use count returns to 0 once everyone releases (no drift / negative);
 *  - a blocking pool never exceeds its configured limit.
 */
public class FastPoolChaosTest {

    private static final long DURATION_MILLIS = 2_000;

    /** Pooled object that tracks whether it is currently borrowed and whether it was destroyed. */
    static final class Item {
        final int id;
        final AtomicBoolean borrowed = new AtomicBoolean(false);
        volatile boolean destroyed = false;

        Item(int id) {
            this.id = id;
        }
    }

    @Test
    void chaos_nonBlocking_randomBatches() throws Exception {
        // no limit: workers may hold a random batch of objects at once
        runChaos(false, 0, 4);
    }

    @Test
    void chaos_blocking_respectsLimit() throws Exception {
        // single borrow per op keeps the limit invariant clean and avoids multi-permit deadlock
        runChaos(true, 8, 1);
    }

    private void runChaos(boolean blocking, int limit, int maxBatch) throws Exception {
        AtomicInteger idGen = new AtomicInteger();
        int maxSize = blocking ? limit : 32;

        FastPool<Item> pool = new FastPool<>(
                "chaos",
                () -> new Item(idGen.incrementAndGet()),
                item -> item.destroyed = true,
                0, maxSize, blocking, blocking ? 1000 : 0);

        int workers = 16;
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger concurrentlyBorrowed = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicLong totalOps = new AtomicLong();
        AtomicLong totalTrims = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(workers + 1);

        final long deadlineNanos = System.nanoTime() + DURATION_MILLIS * 1_000_000L;

        for (int w = 0; w < workers; w++) {
            final long seed = 0xC0FFEEL + w;
            ex.submit(() -> {
                Random rnd = new Random(seed);
                Item[] held = new Item[Math.max(maxBatch, 1)];
                try {
                    start.await();
                    while (running.get() && failure.get() == null && System.nanoTime() < deadlineNanos) {
                        int batch = 1 + rnd.nextInt(Math.max(maxBatch, 1));
                        int n = 0;
                        for (; n < batch; n++) {
                            Item it = pool.get();
                            if (it.destroyed) {
                                failure.compareAndSet(null, "got a destroyed item: " + it.id);
                                releaseHeld(pool, held, n, concurrentlyBorrowed, failure);
                                return;
                            }
                            if (!it.borrowed.compareAndSet(false, true)) {
                                failure.compareAndSet(null, "item " + it.id + " borrowed by two threads at once");
                                releaseHeld(pool, held, n, concurrentlyBorrowed, failure);
                                return;
                            }
                            held[n] = it;
                            int c = concurrentlyBorrowed.incrementAndGet();
                            maxConcurrent.accumulateAndGet(c, Math::max);
                        }

                        // randomized hold time
                        int spins = rnd.nextInt(64);
                        for (int s = 0; s < spins; s++) {
                            Thread.onSpinWait();
                        }
                        if (rnd.nextInt(32) == 0) {
                            Thread.yield();
                        }

                        releaseHeld(pool, held, n, concurrentlyBorrowed, failure);
                        totalOps.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, "worker threw: " + t);
                }
            });
        }

        ex.submit(() -> {
            Random rnd = new Random(0xBEEFL);
            try {
                start.await();
                while (running.get() && failure.get() == null && System.nanoTime() < deadlineNanos) {
                    pool.trim(1 + rnd.nextInt(8)); // destroy a random number of idle objects
                    totalTrims.incrementAndGet();
                    for (int s = 0, e = rnd.nextInt(128); s < e; s++) {
                        Thread.onSpinWait();
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, "trimmer threw: " + t);
            }
        });

        start.countDown();
        ex.shutdown();
        if (!ex.awaitTermination(DURATION_MILLIS + 30_000, TimeUnit.MILLISECONDS)) {
            running.set(false);
            ex.shutdownNow();
            fail("chaos workers did not finish in time (possible deadlock)");
        }

        assertNull(failure.get(), () -> "chaos invariant violated: " + failure.get());
        assertEquals(0, concurrentlyBorrowed.get(), "every borrow was balanced by a release");
        assertEquals(0, pool.getCount(), "in-use count returns to zero, never drifts");
        if (blocking) {
            assertTrue(maxConcurrent.get() <= limit,
                    "blocking pool exceeded its limit: peak " + maxConcurrent.get() + " > " + limit);
        }
        System.out.printf("chaos[%s] ops=%d trims=%d peakBorrowed=%d distinctItems=%d%n",
                blocking ? "blocking" : "nonBlocking",
                totalOps.get(), totalTrims.get(), maxConcurrent.get(), idGen.get());
    }

    private static void releaseHeld(FastPool<Item> pool, Item[] held, int n,
                                    AtomicInteger concurrentlyBorrowed, AtomicReference<String> failure) {
        for (int k = 0; k < n; k++) {
            Item it = held[k];
            held[k] = null;
            concurrentlyBorrowed.decrementAndGet();
            if (!it.borrowed.compareAndSet(true, false)) {
                failure.compareAndSet(null, "item " + it.id + " borrow flag corrupted under owner");
            }
            pool.release(it);
        }
    }
}
