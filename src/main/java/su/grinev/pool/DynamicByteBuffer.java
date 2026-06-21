package su.grinev.pool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DynamicByteBuffer extends ArenaByteBuffer implements Disposable {
    private Runnable onDispose;

    public DynamicByteBuffer(int capacity) {
        super(capacity);
    }

    /** Explicit release mode (AUTO = GC-managed default, MANUAL = deterministic destroy()). */
    public DynamicByteBuffer(int capacity, Release release) {
        super(capacity, release);
    }

    /**
     * Compatibility constructor: arena-backed memory is always native, so the
     * {@code direct} flag is no longer meaningful and is ignored. Defaults to AUTO release.
     */
    public DynamicByteBuffer(int capacity, boolean direct) {
        super(capacity);
    }

    public void initBuffer() {
        super.buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.clear();
    }

    public DynamicByteBuffer position(int newPosition) {
        buffer.position(newPosition);
        return this;
    }

    public int position() {
        return buffer.position();
    }

    public DynamicByteBuffer put(byte b) {
        buffer.put(b);
        return this;
    }

    public DynamicByteBuffer put(byte[] b) {
        buffer.put(b);
        return this;
    }

    public DynamicByteBuffer put(byte[] b, int off, int len) {
        buffer.put(b, off, len);
        return this;
    }

    public DynamicByteBuffer putInt(int i) {
        buffer.putInt(i);
        return this;
    }

    public DynamicByteBuffer putInt(int pos, int i) {
        buffer.putInt(pos, i);
        return this;
    }

    public DynamicByteBuffer putShort(short s) {
        buffer.putShort(s);
        return this;
    }

    public DynamicByteBuffer flip() {
        buffer.flip();
        return this;
    }

    public DynamicByteBuffer rewind() {
        buffer.rewind();
        return this;
    }

    public DynamicByteBuffer putLong(long l) {
        buffer.putLong(l);
        return this;
    }

    public DynamicByteBuffer putLong(int pos, long l) {
        buffer.putLong(pos, l);
        return this;
    }

    public DynamicByteBuffer putFloat(float f) {
        buffer.putFloat(f);
        return this;
    }

    public DynamicByteBuffer putDouble(double d) {
        buffer.putDouble(d);
        return this;
    }

    public DynamicByteBuffer putByteBuffer(ByteBuffer byteBuffer) {
        buffer.put(byteBuffer);
        return this;
    }

    @Override
    public void setOnDispose(Runnable onDispose) {
        this.onDispose = onDispose;
    }

    @Override
    public void dispose() {
        this.onDispose.run();
    }

    // destroy() is inherited from ArenaByteBuffer: it closes the arena (deterministic free).
    // A Cleaner in ArenaByteBuffer also closes the arena if the buffer is dropped without destroy().

    @Override
    public void close() {
        dispose();
    }
}
