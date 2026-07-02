package su.grinev.pool;

import lombok.Getter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
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
 * Release mode (see {@link Release}):
 * <ul>
 *   <li><b>AUTO</b> (default) — {@code Arena.ofAuto()}: the GC frees the native segment when the buffer
 *       becomes unreachable, exactly like a direct {@link ByteBuffer}'s cleaner. {@link #destroy()} is a
 *       no-op. Safe by construction — a dropped buffer never leaks. This is also the only mode that does
 *       not require the FFM {@code Arena.of{Shared,Confined}} API, so it is the path a Java 21 build uses.</li>
 *   <li><b>MANUAL</b> — {@code Arena.ofShared()}: {@link #destroy()} frees the segment deterministically.
 *       A {@link Cleaner} closes the arena if the buffer is dropped without {@code destroy()} (ofShared
 *       memory is otherwise never reclaimed by the GC). Use only when you take explicit ownership and want
 *       to reclaim native memory the instant you are done with it.</li>
 * </ul>
 *
 * {@link #ensureCapacity(int)} is only a safety net: if the content ever outgrows the preallocated
 * capacity it allocates a larger segment from a fresh arena and copies the content over (MANUAL closes the
 * old arena immediately; AUTO leaves it to the GC). FFM segments cannot be resized in place.
 *
 * {@link #dispose()} recycles the buffer back to its pool — orthogonal to the release mode.
 */
public class ArenaByteBuffer implements Disposable {

    /** Native-memory reclamation strategy. */
    public enum Release { AUTO, MANUAL }

    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * Holds the live arena for the {@link Cleaner} (MANUAL mode only). Kept in a separate object that does
     * NOT reference the enclosing {@link ArenaByteBuffer} (otherwise the buffer could never become
     * unreachable and the cleaner would never run).
     */
    private static final class ArenaHolder implements Runnable {
        private Arena arena;

        @Override
        public void run() {
            Arena a = arena;
            arena = null;
            if (a != null) {
                try {
                    a.close();
                } catch (RuntimeException ignored) {
                    // already closed (e.g. by ensureCapacity) — nothing to free
                }
            }
        }
    }

    private final Release release;
    private Runnable onDispose;
    private final ArenaHolder holder;          // MANUAL only (null in AUTO)
    private final Cleaner.Cleanable cleanable; // MANUAL only (null in AUTO)
    private Arena arena;
    private MemorySegment segment;
    @Getter
    protected ByteBuffer buffer;

    /** GC-managed (AUTO) buffer — the safe default. */
    public ArenaByteBuffer(int capacity) {
        this(capacity, Release.AUTO);
    }

    public ArenaByteBuffer(int capacity, Release release) {
        this.release = release;
        this.arena = newArena();
        this.segment = arena.allocate(capacity);
        this.buffer = segment.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        if (release == Release.MANUAL) {
            this.holder = new ArenaHolder();
            this.holder.arena = arena;
            this.cleanable = CLEANER.register(this, holder);
        } else {
            this.holder = null;
            this.cleanable = null;
        }
    }

    private Arena newArena() {
        return release == Release.MANUAL ? Arena.ofShared() : Arena.ofAuto();
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

        Arena newArena = newArena();
        MemorySegment newSegment = newArena.allocate(newCapacity);
        MemorySegment.copy(segment, 0, newSegment, 0, used);

        Arena oldArena = arena;
        this.arena = newArena;
        this.segment = newSegment;
        // Preserve the caller's byte order: msgpack writes BIG_ENDIAN, BSON LITTLE_ENDIAN —
        // hardcoding LE here would silently flip the order of a big-endian stream mid-write.
        this.buffer = newSegment.asByteBuffer().order(buffer.order());
        this.buffer.position(used);
        if (release == Release.MANUAL) {
            holder.arena = newArena;   // the cleaner now tracks the new arena
            oldArena.close();          // free the old segment now; AUTO leaves it to the GC
        }
    }

    /** Native base address of the backing segment. */
    public long address() {
        return segment.address();
    }

    /** Whether the backing native memory is still allocated (MANUAL: arena not yet closed; AUTO: until GC). */
    public boolean isAlive() {
        return segment.scope().isAlive();
    }

    @Override
    public void setOnDispose(Runnable onDispose) {
        this.onDispose = onDispose;
    }

    @Override
    public Runnable getOnDispose() {
        return onDispose;
    }

    @Override
    public void dispose() {
        if (onDispose != null) {
            onDispose.run();
        }
    }

    @Override
    public void destroy() {
        // Deterministic free only in MANUAL mode; AUTO is reclaimed by the GC.
        if (release == Release.MANUAL) {
            cleanable.clean();
        }
    }

    @Override
    public void close() {
        dispose();
    }
}
