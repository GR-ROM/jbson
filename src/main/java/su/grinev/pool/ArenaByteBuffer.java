package su.grinev.pool;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A {@link Disposable}, arena-backed native byte buffer.
 *
 * The backing native segment is allocated once, up front, at the requested
 * capacity — sized for the buffer's expected high-water mark. Writing into it
 * never reallocates as long as the content fits, which is the intended (and
 * common) case for a pooled buffer reused many times.
 *
 * {@link #ensureCapacity(int)} is only a safety net: if the content ever
 * outgrows the preallocated capacity it falls back to allocating a larger
 * segment from a fresh arena, copying the content over, and closing the old
 * arena. FFM segments cannot be resized in place, and an arena releases its
 * memory only on {@code close()} — so growth necessarily means
 * realloc-and-close, never an in-place resize. Preallocation keeps that path
 * off the hot path entirely.
 *
 * {@link #dispose()} recycles the buffer back to its pool; {@link #destroy()}
 * closes the arena and returns the native memory to the OS deterministically.
 */
public class ArenaByteBuffer implements Disposable {
    private Runnable onDispose;
    private Arena arena;
    private MemorySegment segment;
    protected ByteBuffer buffer;

    public ArenaByteBuffer(int capacity) {
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(capacity);
        this.buffer = segment.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Capacity of the preallocated native segment in bytes. Writes up to this size never reallocate. */
    public int capacity() {
        return (int) segment.byteSize();
    }

    /**
     * Safety net for the rare case where content outgrows the preallocated
     * capacity. A no-op — and zero allocations — while the existing capacity
     * suffices, which is the expected path for a properly sized buffer.
     */
    public void ensureCapacity(int additionalCapacity) {
        if (buffer.remaining() >= additionalCapacity) {
            return;
        }
        int used = buffer.position();
        int newCapacity = Math.max(capacity() * 2, used + additionalCapacity);

        Arena newArena = Arena.ofShared();
        MemorySegment newSegment = newArena.allocate(newCapacity);
        MemorySegment.copy(segment, 0, newSegment, 0, used);

        Arena oldArena = arena;
        this.arena = newArena;
        this.segment = newSegment;
        this.buffer = newSegment.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        this.buffer.position(used);
        oldArena.close();
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    /** Native base address of the backing segment. */
    public long address() {
        return segment.address();
    }

    /** Whether the backing native memory is still allocated (arena not yet closed). */
    public boolean isAlive() {
        return segment.scope().isAlive();
    }

    @Override
    public void setOnDispose(Runnable onDispose) {
        this.onDispose = onDispose;
    }

    @Override
    public void dispose() {
        if (onDispose != null) {
            onDispose.run();
        }
    }

    @Override
    public void destroy() {
        if (segment.scope().isAlive()) {
            arena.close();
        }
    }

    @Override
    public void close() {
        dispose();
    }
}
