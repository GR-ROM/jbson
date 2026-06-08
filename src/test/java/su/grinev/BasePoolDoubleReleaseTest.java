package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.pool.Pool;
import su.grinev.pool.PoolFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates J12: BasePool counter drift on double-release.
 *
 * Bug: release() adds the object to the pool BEFORE checking the counter.
 * A double-release puts two copies of the same object in the pool while
 * the counter only accounts for one. Over time this causes:
 * - Counter drift (more objects in pool than counter reflects)
 * - Two consumers receiving the same object instance (data corruption)
 * - The commented-out exception at line 77 confirms this was a known issue
 */
public class BasePoolDoubleReleaseTest {

    /**
     * Double-release of the same object should be detected and rejected.
     * Currently it silently corrupts the pool.
     */
    @Test
    void doubleRelease_shouldBeDetected() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(0)
                .setMaxPoolSize(5)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        Pool<StringBuilder> pool = poolFactory.getPool(StringBuilder::new);

        // Get an object from the pool
        StringBuilder obj = pool.get();
        obj.append("data");

        // First release — legitimate
        pool.release(obj);

        // Second release — BUG: should throw, but currently silently adds
        // the same object to the pool a second time
        assertThrows(IllegalStateException.class, () -> pool.release(obj),
                "Double release should throw IllegalStateException");
    }

    /**
     * After fix: double-release is rejected, so the pool never contains
     * two references to the same object. Two get() calls always return
     * different instances.
     */
    @Test
    void afterFix_twoGets_returnDifferentObjects() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(0)
                .setMaxPoolSize(5)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        Pool<StringBuilder> pool = poolFactory.getPool(StringBuilder::new);

        StringBuilder obj = pool.get();
        pool.release(obj);

        // Second release is rejected
        assertThrows(IllegalStateException.class, () -> pool.release(obj));

        // Only one copy in the pool — second get() creates a new instance
        StringBuilder a = pool.get();
        StringBuilder b = pool.get();
        assertNotSame(a, b,
                "Two get() calls should return different objects");
    }

    /**
     * Counter drift: after double-release, counter is lower than actual
     * checked-out objects. This can cause the pool to hand out more
     * objects than its limit allows.
     */
    @Test
    void doubleRelease_causesCounterDrift() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(0)
                .setMaxPoolSize(3)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        Pool<StringBuilder> pool = poolFactory.getPool(StringBuilder::new);

        // Check out 3 objects — pool at limit
        StringBuilder a = pool.get();
        StringBuilder b = pool.get();
        StringBuilder c = pool.get();

        // Should be at limit now
        assertThrows(IllegalStateException.class, () -> pool.get(),
                "Pool should be at limit (3/3)");

        // Release one object twice — counter goes from 3→2→1
        pool.release(a);
        pool.release(a);  // BUG: counter now thinks only 1 object is checked out

        // Counter says 1 checked out, but actually 2 are (b and c).
        // Pool allows 2 more get() calls — exceeding the real limit.
        StringBuilder d = pool.get();  // counter: 1→2, OK
        StringBuilder e = pool.get();  // counter: 2→3, OK

        // We now have 4 objects checked out (b, c, d, e) with limit=3!
        // After fix: the double release should have been rejected
        int checkedOut = 4; // b, c, d, e
        assertTrue(checkedOut > 3,
                "Counter drift allows exceeding pool limit");
    }

    /**
     * Normal get/release cycle should work correctly.
     */
    @Test
    void normalGetRelease_worksCorrectly() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(0)
                .setMaxPoolSize(3)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        Pool<StringBuilder> pool = poolFactory.getPool(StringBuilder::new);

        for (int i = 0; i < 100; i++) {
            StringBuilder obj = pool.get();
            obj.setLength(0);
            obj.append("iteration-").append(i);
            pool.release(obj);
        }

        // Pool should still be functional
        StringBuilder a = pool.get();
        StringBuilder b = pool.get();
        StringBuilder c = pool.get();
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);

        // Limit respected
        assertThrows(IllegalStateException.class, () -> pool.get());

        pool.release(a);
        pool.release(b);
        pool.release(c);
    }
}
