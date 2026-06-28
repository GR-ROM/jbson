package su.grinev.pool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FastPool}, focusing on the {@link Trimmable} signals
 * (in-use vs idle) and trim behaviour.
 */
public class FastPoolTest {

    private static FastPool<Object> pool(int initialSize, int maxSize, List<Object> destroyed) {
        return new FastPool<>("test", Object::new, destroyed::add, initialSize, maxSize, false, 0);
    }

    @Test
    void convenienceConstructor_isNonBlockingAndWorks() {
        FastPool<Object> pool = new FastPool<>(Object::new, 2, 10); // (supplier, initialSize, maxSize)
        assertEquals(2, pool.getIdle(), "prefilled to initialSize");
        Object a = pool.get();
        Object b = pool.get();
        Object c = pool.get(); // beyond initial size: non-blocking -> creates on demand, never blocks
        assertEquals(3, pool.getCountInUse());
        pool.release(a);
        pool.release(b);
        pool.release(c);
        assertEquals(0, pool.getCountInUse());
    }

    /** Regression: getCount() (in-use) must stay correct across reuse and never go negative. */
    @Test
    void getCount_InUse_tracksInUseAcrossReuse() {
        FastPool<Object> pool = pool(0, 10, new ArrayList<>());

        Object a = pool.get();
        Object b = pool.get();
        assertEquals(2, pool.getCountInUse(), "two checked out");
        assertEquals(0, pool.getIdle());

        pool.release(a);
        assertEquals(1, pool.getCountInUse());
        assertEquals(1, pool.getIdle(), "released object is now idle");

        Object c = pool.get(); // reuses the idle object — in-use must rise again
        assertEquals(2, pool.getCountInUse(), "reusing a pooled object still counts as in use");
        assertEquals(0, pool.getIdle());

        pool.release(b);
        pool.release(c);
        assertEquals(0, pool.getCountInUse(), "in-use returns to 0, never negative");
        assertEquals(2, pool.getIdle());
    }

    @Test
    void doubleRelease_isDetected() {
        FastPool<Object> pool = pool(0, 10, new ArrayList<>());
        Object o = pool.get();
        pool.release(o);

        // releasing again (nothing else in flight) drives the in-use count below zero
        assertThrows(IllegalStateException.class, () -> pool.release(o),
                "a double release must be rejected");
        // the pool is left consistent: in-use back at 0, not negative
        assertEquals(0, pool.getCountInUse());
    }

    @Test
    void prefilledObjects_startIdleNotInUse() {
        FastPool<Object> pool = pool(3, 10, new ArrayList<>());

        assertEquals(0, pool.getCountInUse(), "prefilled objects are idle, not in use");
        assertEquals(3, pool.getIdle());

        Object x = pool.get();
        assertEquals(1, pool.getCountInUse());
        assertEquals(2, pool.getIdle());

        pool.release(x);
        assertEquals(0, pool.getCountInUse());
        assertEquals(3, pool.getIdle());
    }

    @Test
    void trim_destroysIdleObjects() {
        List<Object> destroyed = new ArrayList<>();
        FastPool<Object> pool = pool(5, 10, destroyed);

        assertTrue(pool.trim(3));

        assertEquals(2, pool.getIdle(), "idle reduced by the trimmed amount");
        assertEquals(3, destroyed.size(), "trimmed objects were passed to the destroyer");
    }

    @Test
    void trim_returnsFalse_andKeepsObjects_whenNotEnoughIdle() {
        List<Object> destroyed = new ArrayList<>();
        FastPool<Object> pool = pool(2, 10, destroyed);

        assertFalse(pool.trim(5), "cannot trim more than is idle");
        assertEquals(2, pool.getIdle(), "objects are put back, none lost");
        assertTrue(destroyed.isEmpty(), "nothing destroyed when trim refuses");
    }
}
