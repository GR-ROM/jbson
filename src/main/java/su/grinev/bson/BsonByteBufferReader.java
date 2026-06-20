package su.grinev.bson;

import lombok.extern.slf4j.Slf4j;
import su.grinev.exception.BsonException;
import su.grinev.pool.FastPool;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static su.grinev.bson.Utility.decodeDecimal128;

@Slf4j
public class BsonByteBufferReader implements BsonReader {

    private static final int STRING_BUFFER_SIZE = 256;
    private static final ThreadLocal<byte[]> stringBuffer = ThreadLocal.withInitial(() -> new byte[STRING_BUFFER_SIZE]);
    private final ByteBuffer buffer;
    private final FastPool<ByteBuffer> byteBufferPool;

    public BsonByteBufferReader(ByteBuffer buffer, FastPool<ByteBuffer> binaryPacketPool) {
        this.buffer = buffer;
        this.byteBufferPool = binaryPacketPool;
    }

    @Override
    public String readString() {
        int rawLen = buffer.getInt();
        if (rawLen <= 0) {
            throw new BsonException("Invalid string length: " + rawLen);
        }
        int len = rawLen - 1; // exclude null terminator
        if (len > buffer.remaining()) {
            throw new BsonException("String length " + len + " exceeds remaining buffer " + buffer.remaining());
        }
        byte[] bytes = stringBuffer.get();
        if (bytes.length < len) {
            bytes = new byte[Math.max(len, STRING_BUFFER_SIZE * 2)];
            stringBuffer.set(bytes);
        }
        buffer.get(bytes, 0, len);
        buffer.position(buffer.position() + 1);
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    @Override
    public String readCString() {
        int len = 0;
        int limit = buffer.limit();
        int start = buffer.position();
        for (int i = start; i < limit; i++) {
            if (buffer.get(i) == 0) {
                len = i - start;
                byte[] bytes = stringBuffer.get();
                if (bytes.length < len) {
                    bytes = new byte[Math.max(len, STRING_BUFFER_SIZE * 2)];
                    stringBuffer.set(bytes);
                }
                buffer.get(bytes, 0, len);
                buffer.position(buffer.position() + 1); // skip null terminator
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
        }
        throw new BsonException("CString missing null terminator at position " + start);
    }

    @Override
    public byte[] readBinaryAsArray() {
        int len = buffer.getInt();
        byte subtype = buffer.get();

        if (subtype == 0x02) {
            int innerLen = buffer.getInt();
            if (innerLen != len - 4) {
                throw new BsonException("Invalid old binary format: length mismatch");
            }
            len = innerLen;
        }

        if (len < 0) {
            throw new BsonException("Negative binary length: " + len);
        }
        if (len > buffer.remaining()) {
            throw new BsonException("Binary length " + len + " exceeds remaining buffer " + buffer.remaining());
        }

        byte[] temp = new byte[len];
        buffer.get(temp, 0, len);
        return temp;
    }

    @Override
    public ByteBuffer readBinary(boolean bufferProjection) {
        int len = buffer.getInt();
        byte subtype = buffer.get();

        if (subtype == 0x02) {
            int innerLen = buffer.getInt();
            if (innerLen != len - 4) {
                throw new BsonException("Invalid old binary format: length mismatch");
            }
            len = innerLen;
        }

        if (len < 0) {
            throw new BsonException("Negative binary length: " + len);
        }

        if (len > buffer.remaining()) {
            throw new BsonException("Binary data truncated: len=" + len + ", remaining=" + buffer.remaining());
        }

        ByteBuffer buffer1;
        if (bufferProjection) {
            buffer1 = buffer.slice(buffer.position(), len);
            buffer.position(buffer.position() + len);
        } else {
            buffer1 = byteBufferPool.get().clear();

            if (len > buffer1.capacity()) {
                buffer1 = ByteBuffer.allocateDirect(len);
                log.warn("Reallocated direct buffer for binary data: {} bytes", len);
            }

            int oldLimit = buffer.limit();
            buffer.limit(buffer.position() + len);
            buffer1.put(buffer);
            buffer.limit(oldLimit);
            buffer1.flip();
        }
        return buffer1;
    }

    @Override
    public String readObjectId() {
        byte[] oid = new byte[12];
        buffer.get(oid);
        StringBuilder sb = new StringBuilder(24);
        for (byte b : oid) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public Instant readDateTime() {
        long epochMillis = buffer.getLong();
        return Instant.ofEpochMilli(epochMillis);
    }

    @Override
    public BigDecimal readDecimal128() {
        long low = buffer.getLong();
        long high = buffer.getLong();
        return decodeDecimal128(low, high);
    }

    @Override
    public double readDouble() {
        return buffer.getDouble();
    }

    @Override
    public float readFloat() {
        return buffer.getFloat();
    }

    @Override
    public int readInt() {
        return buffer.getInt();
    }

    @Override
    public int readInt(int position) {
        return buffer.getInt(position);
    }

    @Override
    public byte readByte() {
        return buffer.get();
    }

    @Override
    public int position() {
        return buffer.position();
    }

    @Override
    public void position(int position) {
        buffer.position(position);
    }

    @Override
    public long readLong() {
        return buffer.getLong();
    }

    @Override
    public boolean readBoolean() {
        return buffer.get() != 0;
    }
}
