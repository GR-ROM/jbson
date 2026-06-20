package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.pool.ArenaByteBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ArenaByteBuffer} — a native, arena-backed buffer whose memory
 * is released deterministically by {@link ArenaByteBuffer#destroy()}.
 */
public class ArenaByteBufferTest {

    @Test
    void allocatesLiveNativeMemory() {
        ArenaByteBuffer b = new ArenaByteBuffer(64);
        assertTrue(b.isAlive());
        assertNotEquals(0L, b.address(), "a real native segment has a non-zero address");
        assertEquals(64, b.capacity());
        assertEquals(64, b.getBuffer().capacity());
        b.destroy();
    }

    @Test
    void writesWithinPreallocatedCapacity_neverReallocate() {
        ArenaByteBuffer b = new ArenaByteBuffer(64);
        long address = b.address();

        // fill the buffer completely (16 ints == 64 bytes) checking room each time
        for (int i = 0; i < 16; i++) {
            b.ensureCapacity(Integer.BYTES);
            b.getBuffer().putInt(i);
        }

        assertEquals(address, b.address(), "preallocated capacity is never reallocated while it suffices");
        assertEquals(64, b.capacity());
        b.getBuffer().flip();
        assertEquals(0, b.getBuffer().getInt(0));
        b.destroy();
    }

    @Test
    void readsBackWhatWasWritten() {
        ArenaByteBuffer b = new ArenaByteBuffer(64);
        ByteBuffer buf = b.getBuffer();
        buf.putInt(0, 0x01020304);
        buf.putLong(8, 42L);
        assertEquals(0x01020304, buf.getInt(0));
        assertEquals(42L, buf.getLong(8));
        b.destroy();
    }

    @Test
    void isLittleEndian() {
        ArenaByteBuffer b = new ArenaByteBuffer(8);
        b.getBuffer().putInt(0, 1);
        assertEquals(1, b.getBuffer().get(0), "little-endian: least significant byte first");
        b.destroy();
    }

    @Test
    void destroy_releasesMemory_andMakesAccessFail() {
        ArenaByteBuffer b = new ArenaByteBuffer(64);
        assertTrue(b.isAlive());

        b.destroy();

        assertFalse(b.isAlive(), "arena closed -> memory released");
        assertThrows(IllegalStateException.class, () -> b.getBuffer().getInt(0),
                "accessing a buffer after its arena is closed must fail");
    }

    @Test
    void dispose_runsOnDisposeCallback_withoutFreeingArena() {
        ArenaByteBuffer b = new ArenaByteBuffer(16);
        AtomicInteger recycles = new AtomicInteger();
        b.setOnDispose(recycles::incrementAndGet);

        b.dispose();

        assertEquals(1, recycles.get(), "dispose runs the recycle callback");
        assertTrue(b.isAlive(), "dispose recycles to the pool, it does not free the arena");
        b.destroy();
    }

    @Test
    void close_delegatesToDispose() {
        ArenaByteBuffer b = new ArenaByteBuffer(16);
        AtomicInteger recycles = new AtomicInteger();
        b.setOnDispose(recycles::incrementAndGet);

        b.close();

        assertEquals(1, recycles.get(), "close() delegates to dispose()");
        b.destroy();
    }

    @Test
    void destroy_isIdempotent() {
        ArenaByteBuffer b = new ArenaByteBuffer(16);
        b.destroy();
        assertDoesNotThrow(b::destroy, "a second destroy must be a safe no-op");
    }

    @Test
    void ensureCapacity_isNoopWhenEnoughRoom() {
        ArenaByteBuffer b = new ArenaByteBuffer(64);
        long address = b.address();
        b.ensureCapacity(16);
        assertEquals(address, b.address(), "no reallocation when the preallocated capacity already suffices");
        assertTrue(b.isAlive());
        b.destroy();
    }

    /**
     * Fallback path: when content outgrows the preallocated capacity the buffer
     * reallocates into a fresh, larger arena, copies the content, and the old
     * arena is closed. This is the rare safety net, not the hot path.
     */
    @Test
    void ensureCapacity_fallbackGrowsAndPreservesContent() {
        ArenaByteBuffer b = new ArenaByteBuffer(8);
        b.getBuffer().putInt(777); // remaining now 4
        long oldAddress = b.address();

        b.ensureCapacity(64); // 4 < 64 -> outgrows preallocation, must grow

        assertTrue(b.capacity() >= 68);
        assertNotEquals(oldAddress, b.address(), "grown buffer lives in a fresh segment");
        assertTrue(b.isAlive());
        b.getBuffer().flip();
        assertEquals(777, b.getBuffer().getInt(0), "content is copied across the growth");
        b.destroy();
    }
}
