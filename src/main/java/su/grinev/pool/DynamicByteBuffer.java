package su.grinev.pool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DynamicByteBuffer implements Disposable {
    private Runnable onDispose;
    private ByteBuffer buffer;
    private final boolean direct;

    public DynamicByteBuffer(int capacity, boolean direct) {
        this.direct = direct;
        if (direct) {
            this.buffer = ByteBuffer.allocateDirect(capacity);
        } else {
            this.buffer = ByteBuffer.allocate(capacity);
        }
        initBuffer();
    }

    public void ensureCapacity(int additionalCapacity) {
        if (buffer.remaining() < additionalCapacity) {
            ByteBuffer oldBuffer = buffer;
            if (direct) {
                buffer = ByteBuffer.allocateDirect(Math.max(buffer.capacity() * 2, buffer.remaining() + additionalCapacity));
            } else {
                buffer = ByteBuffer.allocate(Math.max(buffer.capacity() * 2, buffer.remaining() + additionalCapacity));
            }
            initBuffer();
            buffer.put(oldBuffer.flip());
        }
    }

    public void initBuffer() {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.clear();
    }

    public ByteBuffer getBuffer() {
        return buffer;
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

    @Override
    public void close() {
        dispose();
    }
}
