package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.pool.Pool;
import su.grinev.pool.PoolFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for BasePool: blocking, timeout, contention, and deadlock detection.
 */
public class BasePoolConcurrencyTest {

    @Test
    void blockingPool_waitsAndResumes() throws Exception {
        Pool<StringBuilder> pool = createPool(0, 2, 5000, true);

        StringBuilder a = pool.get();
        StringBuilder b = pool.get();

        // Pool full — next get() should block
        AtomicBoolean gotItem = new AtomicBoolean(false);
        Thread waiter = Thread.ofPlatform().start(() -> {
            StringBuilder c = pool.get();
            gotItem.set(true);
            pool.release(c);
        });

        Thread.sleep(100);
        assertFalse(gotItem.get(), "Should be blocked waiting for pool slot");

        pool.release(a);
        waiter.join(2000);
        assertTrue(gotItem.get(), "Should have unblocked after release");
    }

    @Test
    void timeoutPool_throwsOnTimeout() {
        Pool<StringBuilder> pool = createPool(0, 1, 100, true);
        StringBuilder a = pool.get();

        IllegalStateException ex = assertThrows(IllegalStateException.class, pool::get);
        assertTrue(ex.getMessage().contains("timeout"), "Should mention timeout: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("100ms"), "Should mention duration: " + ex.getMessage());

        pool.release(a);
    }

    @Test
    void nonBlockingPool_throwsImmediately() {
        Pool<StringBuilder> pool = createPool(0, 2, 1000, false);
        pool.get();
        pool.get();

        IllegalStateException ex = assertThrows(IllegalStateException.class, pool::get);
        assertTrue(ex.getMessage().contains("overflow"), "Should mention overflow: " + ex.getMessage());
    }

    @Test
    void highContention_noDeadlock() throws Exception {
        int threadCount = 8;
        int opsPerThread = 100_000;
        Pool<StringBuilder> pool = createPool(4, 16, 5000, true);

        AtomicLong totalOps = new AtomicLong();
        AtomicBoolean failed = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            Thread.ofPlatform().name("contention-" + t).start(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        StringBuilder obj = pool.get();
                        obj.setLength(0);
                        obj.append(Thread.currentThread().getName()).append("-").append(i);
                        pool.release(obj);
                        totalOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.printf("%s: %s%n", Thread.currentThread().getName(), e.getMessage());
                    failed.set(true);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads did not finish — possible deadlock");
        assertFalse(failed.get(), "Failures detected during contention test");
        assertEquals((long) threadCount * opsPerThread, totalOps.get());
    }

    @Test
    void multipleGetRelease_poolReusesObjects() {
        AtomicInteger created = new AtomicInteger(0);
        PoolFactory factory = PoolFactory.Builder.builder()
                .setMinPoolSize(0)
                .setMaxPoolSize(3)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();
        Pool<StringBuilder> pool = factory.getPool(() -> {
            created.incrementAndGet();
            return new StringBuilder();
        });

        // First round: creates 3 objects
        StringBuilder a = pool.get();
        StringBuilder b = pool.get();
        StringBuilder c = pool.get();
        assertEquals(3, created.get());

        pool.release(a);
        pool.release(b);
        pool.release(c);

        // Second round: should reuse objects, not create new ones
        pool.get();
        pool.get();
        pool.get();
        assertEquals(3, created.get(), "Pool should reuse objects, not create new ones");
    }

    @Test
    void virtualThreads_noDeadlock() throws Exception {
        int threadCount = 200;
        int opsPerThread = 1_000;
        Pool<StringBuilder> pool = createPool(2, 8, 5000, true);

        AtomicLong totalOps = new AtomicLong();
        AtomicBoolean failed = new AtomicBoolean(false);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            StringBuilder obj = pool.get();
                            obj.setLength(0);
                            obj.append("vthread");
                            pool.release(obj);
                            totalOps.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.set(true);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertFalse(failed.get());
        assertEquals((long) threadCount * opsPerThread, totalOps.get());
    }

    @Test
    void prefilled_poolServesFromCache() {
        AtomicInteger created = new AtomicInteger(0);
        PoolFactory factory = PoolFactory.Builder.builder()
                .setMinPoolSize(5)
                .setMaxPoolSize(10)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();
        Pool<StringBuilder> pool = factory.getPool(() -> {
            created.incrementAndGet();
            return new StringBuilder();
        });

        // 5 objects were pre-created during init
        assertEquals(5, created.get());

        // Getting 5 objects should not create new ones (served from prefill cache)
        for (int i = 0; i < 5; i++) {
            pool.get();
        }
        assertEquals(5, created.get(), "Prefilled items should be served from cache");
    }

    @Test
    void blockingTimeout_releasedBeforeDeadline_succeeds() throws Exception {
        Pool<StringBuilder> pool = createPool(0, 1, 2000, true);
        StringBuilder a = pool.get();

        AtomicBoolean success = new AtomicBoolean(false);
        Thread waiter = Thread.ofPlatform().start(() -> {
            StringBuilder b = pool.get();
            success.set(true);
            pool.release(b);
        });

        // Release after 200ms — well within the 2000ms timeout
        Thread.sleep(200);
        pool.release(a);

        waiter.join(3000);
        assertTrue(success.get(), "Should have obtained item before timeout");
    }

    private Pool<StringBuilder> createPool(int minSize, int maxSize, int timeoutMs, boolean blocking) {
        PoolFactory factory = PoolFactory.Builder.builder()
                .setMinPoolSize(minSize)
                .setMaxPoolSize(maxSize)
                .setOutOfPoolTimeout(timeoutMs)
                .setBlocking(blocking)
                .build();
        return factory.getPool(StringBuilder::new);
    }
}
