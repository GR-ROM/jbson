package su.grinev.bson;

import su.grinev.BinaryDocument;
import su.grinev.Serializer;
import su.grinev.pool.DisposablePool;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.Pool;
import su.grinev.pool.PoolFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static su.grinev.bson.Utility.encodeDecimal128;
import static su.grinev.bson.WriterContext.fillForArray;
import static su.grinev.bson.WriterContext.fillForDocument;

public class BsonObjectWriter implements Serializer {

    private static final byte[][] INDEX_BYTES = new byte[1024][];
    static {
        for (int i = 0; i < INDEX_BYTES.length; i++) {
            INDEX_BYTES[i] = Integer.toString(i).getBytes(StandardCharsets.UTF_8);
        }
    }

    private final Pool<WriterContext> writerContextPool;
    private final DisposablePool<DynamicByteBuffer> dynamicByteBufferPool;
    private final Pool<byte[]> bufferPool;
    private final Pool<ArrayDeque<WriterContext>> stackPool;
    private final Map<Object, byte[]> keyBytesCache = new ConcurrentHashMap<>();

    public BsonObjectWriter(
            PoolFactory poolFactory,
            int documentSize,
            boolean directBuffers
    ) {
        writerContextPool = poolFactory.getPool("bson-writer-context-pool", WriterContext::new);
        dynamicByteBufferPool = poolFactory.getDisposablePool("bson-write-buffer-pool", () -> new DynamicByteBuffer(documentSize, directBuffers));
        bufferPool = poolFactory.getPool("bson-writer-nested-buffer-pool", () -> new byte[documentSize]);
        stackPool = poolFactory.getPool("bson-writer-stack-pool", () -> new ArrayDeque<>(64));
    }

    private byte[] getKeyBytes(String key) {
        return keyBytesCache.computeIfAbsent(key, k -> ((String)k).getBytes(StandardCharsets.UTF_8));
    }

    static byte[] getIndexBytes(int index) {
        return index < INDEX_BYTES.length ? INDEX_BYTES[index] : Integer.toString(index).getBytes(StandardCharsets.UTF_8);
    }

    public void serialize(DynamicByteBuffer buffer, BinaryDocument document) {
        buffer.initBuffer();
        ArrayDeque<WriterContext> stack = stackPool.get();
        try {
            WriterContext writerContext = writerContextPool.get();
            stack.addFirst(fillForDocument(writerContext, 0, document.getDocumentMap()));

            serializeLoop(buffer, stack);
        } finally {
            stack.clear();
            stackPool.release(stack);
        }
        buffer.flip();
    }

    private void serializeLoop(DynamicByteBuffer buffer, Deque<WriterContext> stack) {
        while (!stack.isEmpty()) {
            WriterContext ctx = stack.getFirst();
            int stackSize = stack.size();

            if (ctx.startPos == -1) {
                ctx.startPos = buffer.position();
                buffer.ensureCapacity(4);
                buffer.position(buffer.position() + 4); // reserve space for length
            }

            while (ctx.hasNext() && stack.size() <= stackSize) {
                if (ctx.isArray) {
                    int index = ctx.nextArrayIndex();
                    Object value = ctx.arrayList.get(index);
                    if (value == null) value = WriterContext.NullObject.INSTANCE;
                    byte[] indexBytes = getIndexBytes(index);
                    writeValueWithArrayKey(buffer, stack, value, indexBytes);
                } else {
                    Map.Entry<Object, Object> entry = ctx.mapIterator.next();
                    String key = entry.getKey().toString();
                    Object value = entry.getValue();
                    if (value == null) value = WriterContext.NullObject.INSTANCE;
                    writeValue(buffer, stack, value, getKeyBytes(key));
                }
            }

            if (!ctx.hasNext() && stack.size() == stackSize) {
                writeTerminator(buffer);
                int length = buffer.position() - ctx.startPos;
                buffer.putInt(ctx.lengthPos, length);
                stack.removeFirst();
                ctx.startPos = -1; // Reset for reuse
                writerContextPool.release(ctx);
            }
        }
    }

    private void writeValue(DynamicByteBuffer buffer, Deque<WriterContext> stack, Object value, byte[] keyBytes) {
        switch (value) {
            case String s -> {
                byte[] strBytes = s.getBytes(StandardCharsets.UTF_8);
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 4 + strBytes.length + 1);
                buffer.put((byte) 0x02);
                writeCString(buffer, keyBytes);
                buffer.putInt(strBytes.length + 1);
                writeCString(buffer, strBytes);
            }
            case Integer i -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 4);
                buffer.put((byte) 0x10);
                writeCString(buffer, keyBytes);
                buffer.putInt(i);
            }
            case Long l -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 8);
                buffer.put((byte) 0x12);
                writeCString(buffer, keyBytes);
                buffer.putLong(l);
            }
            case Double d -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 8);
                buffer.put((byte) 0x01);
                writeCString(buffer, keyBytes);
                buffer.putDouble(d);
            }
            case BigDecimal bigDecimal -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 16);
                buffer.put((byte) 0x13);
                writeCString(buffer, keyBytes);
                long[] encoded = encodeDecimal128(bigDecimal);
                buffer.putLong(encoded[0]);
                buffer.putLong(encoded[1]);
            }
            case Boolean b -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 1);
                buffer.put((byte) 0x08);
                writeCString(buffer, keyBytes);
                buffer.put((byte) (b ? 1 : 0));
            }
            case WriterContext.NullObject ignored -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1);
                buffer.put((byte) 0x0A);
                writeCString(buffer, keyBytes);
            }
            case byte[] bytes -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 4 + 1 + bytes.length);
                buffer.put((byte) 0x05);
                writeCString(buffer, keyBytes);
                buffer.putInt(bytes.length)
                        .put((byte) 0x00)
                        .put(bytes);
            }
            case ByteBuffer byteBuffer -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 4 + 1 + byteBuffer.limit());
                buffer.put((byte) 0x05);
                writeCString(buffer, keyBytes);
                buffer.putInt(byteBuffer.limit())
                        .put((byte) 0x00)
                        .getBuffer().put(byteBuffer);
                byteBuffer.position(0);
            }
            case Instant instant -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1 + 8);
                buffer.put((byte) 0x09);
                writeCString(buffer, keyBytes);
                buffer.putLong(instant.toEpochMilli());
            }
            case Map map -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1);
                buffer.put((byte) 0x03);
                writeCString(buffer, keyBytes);

                WriterContext newCtx = writerContextPool.get();
                stack.addFirst(fillForDocument(newCtx, buffer.position(), map));
            }
            case List list -> {
                buffer.ensureCapacity(1 + keyBytes.length + 1);
                buffer.put((byte) 0x04);
                writeCString(buffer, keyBytes);

                WriterContext newCtx = writerContextPool.get();
                stack.addFirst(fillForArray(newCtx, buffer.position(), list));
            }
            default -> throw new IllegalArgumentException("Unsupported type: " + value.getClass());
        }
    }

    private void writeValueWithArrayKey(DynamicByteBuffer buffer, Deque<WriterContext> stack, Object value, byte[] indexBytes) {
        switch (value) {
            case String s -> {
                byte[] strBytes = s.getBytes(StandardCharsets.UTF_8);
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 4 + strBytes.length + 1);
                buffer.put((byte) 0x02);
                buffer.put(indexBytes).put((byte) 0x00);
                buffer.putInt(strBytes.length + 1);
                writeCString(buffer, strBytes);
            }
            case Integer i -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 4);
                buffer.put((byte) 0x10);
                buffer.put(indexBytes).put((byte) 0x00);
                buffer.putInt(i);
            }
            case Long l -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 8);
                buffer.put((byte) 0x12);
                buffer.put(indexBytes).put((byte) 0x00);
                buffer.putLong(l);
            }
            case Double d -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 8);
                buffer.put((byte) 0x01);
                buffer.put(indexBytes).put((byte) 0x00);
                buffer.putDouble(d);
            }
            case BigDecimal bigDecimal -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 16);
                buffer.put((byte) 0x13);
                buffer.put(indexBytes).put((byte) 0x00);
                long[] encoded = encodeDecimal128(bigDecimal);
                buffer.putLong(encoded[0]);
                buffer.putLong(encoded[1]);
            }
            case Boolean b -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 1);
                buffer.put((byte) 0x08);
                buffer.put(indexBytes).put((byte) 0x00);
                buffer.put((byte) (b ? 1 : 0));
            }
            case WriterContext.NullObject ignored -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1);
                buffer.put((byte) 0x0A);
                buffer.put(indexBytes).put((byte) 0x00);
            }
            case byte[] bytes -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 4 + 1 + bytes.length);
                buffer.put((byte) 0x05);
                buffer.put(indexBytes).put((byte) 0x00);
                buffer.putInt(bytes.length)
                        .put((byte) 0x00)
                        .put(bytes);
            }
            case ByteBuffer byteBuffer -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 4 + 1 + byteBuffer.limit());
                buffer.put((byte) 0x05);
                buffer.put(indexBytes).put((byte) 0x00);
                buffer.putInt(byteBuffer.limit())
                        .put((byte) 0x00)
                        .getBuffer().put(byteBuffer);
                byteBuffer.position(0);
            }
            case Instant instant -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1 + 8);
                buffer.put((byte) 0x09);
                buffer.put(indexBytes).put((byte) 0x00);
                buffer.putLong(instant.toEpochMilli());
            }
            case Map map -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1);
                buffer.put((byte) 0x03);
                buffer.put(indexBytes).put((byte) 0x00);

                WriterContext newCtx = writerContextPool.get();
                stack.addFirst(fillForDocument(newCtx, buffer.position(), map));
            }
            case List list -> {
                buffer.ensureCapacity(1 + indexBytes.length + 1);
                buffer.put((byte) 0x04);
                buffer.put(indexBytes).put((byte) 0x00);

                WriterContext newCtx = writerContextPool.get();
                stack.addFirst(fillForArray(newCtx, buffer.position(), list));
            }
            default -> throw new IllegalArgumentException("Unsupported type: " + value.getClass());
        }
    }

    public void serialize(BinaryDocument document, OutputStream outputStream) throws IOException {
        try (DynamicByteBuffer dynamicByteBuffer = dynamicByteBufferPool.get()) {
            serialize(dynamicByteBuffer, document);
            byte[] buf = bufferPool.get();
            try {
                while (dynamicByteBuffer.getBuffer().hasRemaining()) {
                    int chunkSize = Math.min(buf.length, dynamicByteBuffer.getBuffer().remaining());
                    dynamicByteBuffer.getBuffer().get(buf, 0, chunkSize);
                    outputStream.write(buf, 0, chunkSize);
                }
            } finally {
                bufferPool.release(buf);
            }
        }
    }

    private static void writeTerminator(DynamicByteBuffer buffer) {
        buffer.ensureCapacity(1);
        buffer.put((byte) 0x00);
    }

    private static void writeCString(DynamicByteBuffer buffer, byte[] keyBytes) {
        buffer.put(keyBytes).put((byte) 0x00);
    }
}
