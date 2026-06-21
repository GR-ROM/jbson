package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.pool.ArenaByteBuffer;
import su.grinev.pool.DisposablePool;
import su.grinev.pool.PoolFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the arena allocation/deallocation path through the pool:
 * {@link DisposablePool#get()} hands out an {@link ArenaByteBuffer}, and
 * {@link DisposablePool#trim(int)} closes the arena of each trimmed buffer,
 * releasing native memory deterministically when the pool shrinks.
 */
public class DisposablePoolArenaTrimTest {

    private DisposablePool<ArenaByteBuffer> newPool() {
        PoolFactory factory = PoolFactory.Builder.builder()
                .setMinPoolSize(0)
                .setMaxPoolSize(8)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();
        // MANUAL mode: trim() must deterministically close each freed buffer's arena.
        return factory.getDisposablePool(() -> new ArenaByteBuffer(64, ArenaByteBuffer.Release.MANUAL));
    }

    @Test
    void trim_closesArenasOfPooledBuffers_andShrinksOwnedSize() {
        DisposablePool<ArenaByteBuffer> pool = newPool();

        List<ArenaByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            buffers.add(pool.get());
        }
        assertEquals(4, pool.getCurrentPoolSize(), "get() created 4 buffers");

        for (ArenaByteBuffer b : buffers) {
            assertTrue(b.isAlive());
            pool.release(b);
        }
        assertEquals(4, pool.getAvailable());

        // shrink the pool: every freed buffer's arena must be closed
        assertTrue(pool.trim(4));

        assertEquals(0, pool.getAvailable());
        assertEquals(0, pool.getCurrentPoolSize(), "owned size drops as buffers are freed");
        for (ArenaByteBuffer b : buffers) {
            assertFalse(b.isAlive(), "trimmed buffer's native memory must be released");
        }
    }

    @Test
    void getCount_getIdle_getInFlight_reflectPoolState() {
        DisposablePool<ArenaByteBuffer> pool = newPool();

        ArenaByteBuffer a = pool.get();
        ArenaByteBuffer b = pool.get();
        assertEquals(2, pool.getCount(), "two objects in use");
        assertEquals(2, pool.getInFlight());
        assertEquals(0, pool.getIdle(), "none idle in the pool yet");

        pool.release(a);
        assertEquals(1, pool.getCount(), "one still in use");
        assertEquals(1, pool.getIdle(), "released object is now idle");

        pool.release(b);
        assertEquals(0, pool.getCount());
        assertEquals(2, pool.getIdle());
    }

    @Test
    void trim_returnsFalse_whenNotEnoughAvailable() {
        DisposablePool<ArenaByteBuffer> pool = newPool();
        ArenaByteBuffer b = pool.get();
        pool.release(b);
        assertEquals(1, pool.getAvailable());

        assertFalse(pool.trim(2), "cannot trim more buffers than are available");
        assertTrue(b.isAlive(), "nothing is freed when trim refuses");
        b.destroy();
    }

    @Test
    void releasedBuffer_isReusedNotReallocated() {
        DisposablePool<ArenaByteBuffer> pool = newPool();
        ArenaByteBuffer first = pool.get();
        pool.release(first);

        ArenaByteBuffer second = pool.get();
        assertSame(first, second, "release returns the buffer to the pool for reuse");
        assertTrue(second.isAlive());
        pool.release(second);
    }
}
