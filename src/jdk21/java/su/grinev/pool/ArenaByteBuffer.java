package su.grinev.pool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Java 21 variant of {@link ArenaByteBuffer} — used by the {@code jbson-jdk21} artifact.
 *
 * <p>Native memory comes from {@link ByteBuffer#allocateDirect(int)} and is reclaimed by the platform's
 * cleaner when the buffer becomes unreachable. There is <b>no deterministic free</b> on this platform:
 * both {@link Release#AUTO} and {@link Release#MANUAL} behave identically (GC-managed) and {@link #destroy()}
 * is always a no-op. The Java 25 ({@code jbson}) artifact uses FFM {@code Arena} to give MANUAL a real
 * deterministic free.
 *
 * <p>The public API mirrors the Java 25 variant exactly so {@link DynamicByteBuffer} and consumers compile
 * unchanged against either artifact.
 */
public class ArenaByteBuffer implements Disposable {

    /** Native-memory reclamation strategy. On Java 21 both modes are GC-managed (auto only). */
    public enum Release { AUTO, MANUAL }

    private Runnable onDispose;
    protected ByteBuffer buffer;

    /** GC-managed buffer — the only mode available on Java 21. */
    public ArenaByteBuffer(int capacity) {
        this(capacity, Release.AUTO);
    }

    public ArenaByteBuffer(int capacity, Release release) {
        // release is accepted for API parity; on Java 21 the direct buffer is always GC-managed.
        this.buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Capacity of the preallocated direct buffer in bytes. Writes up to this size never reallocate. */
    public int capacity() {
        return buffer.capacity();
    }

    /**
     * Safety net for the rare case where content outgrows the preallocated capacity: allocate a larger
     * direct buffer, copy the content over, and let the GC reclaim the old one.
     */
    public void ensureCapacity(int additionalCapacity) {
        if (buffer.remaining() >= additionalCapacity) {
            return;
        }
        int used = buffer.position();
        int newCapacity = Math.max(capacity() * 2, used + additionalCapacity);

        ByteBuffer src = buffer.duplicate();
        src.position(0).limit(used);
        ByteBuffer grown = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.LITTLE_ENDIAN);
        grown.put(src);             // copies [0, used); leaves grown.position() == used
        this.buffer = grown;        // old direct buffer becomes unreachable -> GC reclaims it
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * Not available on the Java 21 build: the native address of a direct buffer can only be read through
     * internal {@code sun.nio.ch}/Unsafe APIs, which the {@code --release 21} compile deliberately excludes.
     * The Java 25 build exposes it via the FFM segment address. Not used in production (test/interop only).
     */
    public long address() {
        throw new UnsupportedOperationException("address() is not available on the jbson-jdk21 build");
    }

    /** Always {@code true} while the buffer is held — Java 21 has no deterministic free, the GC owns it. */
    public boolean isAlive() {
        return buffer != null;
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
        // No-op: the direct buffer is reclaimed by the GC. (Java 25 MANUAL mode frees deterministically.)
    }

    @Override
    public void close() {
        dispose();
    }
}
