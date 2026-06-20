package su.grinev.messagepack;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import su.grinev.BinaryDocument;
import su.grinev.Deserializer;
import su.grinev.pool.FastPool;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Slf4j
public class MessagePackReader implements Deserializer {
    private static final int STRING_BUFFER_SIZE = 256;
    private static final int DEFAULT_MAX_COLLECTION_SIZE = 65536;
    private static final int DEFAULT_MAP_POOL_SIZE = 16;
    private final FastPool<ReaderContext> contextPool;
    private final FastPool<ArrayDeque<ReaderContext>> stackPool;
    private final boolean useProjectionsForByteBuffer;
    private final boolean useByteBufferForBinary;
    private final int maxCollectionSize;
    private final ThreadLocal<byte[]> stringBuffer = ThreadLocal.withInitial(() -> new byte[STRING_BUFFER_SIZE]);
    private final ThreadLocal<MapPool> mapPoolThreadLocal = ThreadLocal.withInitial(() -> new MapPool(DEFAULT_MAP_POOL_SIZE));
    private final ThreadLocal<StringCache> stringCacheThreadLocal = ThreadLocal.withInitial(StringCache::new);
    @Setter
    @Getter
    private boolean readLengthHeader;
    @Setter
    @Getter
    private boolean timestampAsEpochMillis;

    public MessagePackReader(
            FastPool<ReaderContext> contextPool,
            FastPool<ArrayDeque<ReaderContext>> stackPool,
            boolean useProjectionsForByteBuffer,
            boolean useByteBufferForBinary) {
        this(contextPool, stackPool, useProjectionsForByteBuffer, useByteBufferForBinary, DEFAULT_MAX_COLLECTION_SIZE);
    }

    public MessagePackReader(
            FastPool<ReaderContext> contextPool,
            FastPool<ArrayDeque<ReaderContext>> stackPool,
            boolean useProjectionsForByteBuffer,
            boolean useByteBufferForBinary,
            int maxCollectionSize) {
        this.contextPool = contextPool;
        this.stackPool = stackPool;
        this.useProjectionsForByteBuffer = useProjectionsForByteBuffer;
        this.useByteBufferForBinary = useByteBufferForBinary;
        this.maxCollectionSize = maxCollectionSize;
        this.readLengthHeader = true;
    }

    public void deserialize(ByteBuffer buffer, BinaryDocument binaryDocument) {
        int length = -1;
        if (readLengthHeader) {
            length = buffer.getInt();
        }

        Map<Object, Object> root = binaryDocument.getDocumentMap();
        ArrayDeque<ReaderContext> stack = stackPool.get();
        MapPool mapPool = mapPoolThreadLocal.get();
        mapPool.reset();

        try {
            final int rootSize = getMapSize(buffer);
            stack.addFirst(contextPool.get().initMap(root, rootSize));

            while (!stack.isEmpty()) {
                ReaderContext current = stack.getFirst();
                int stackSize = stack.size();

                if (!current.isArray) {
                    Map<Object, Object> map = current.objectMap;
                    while (current.index < current.size) {
                        current.index++;
                        Object key = readValue(buffer, null);
                        Object value = readValue(buffer, stack);
                        map.put(key, value);

                        if (stack.size() > stackSize) {
                            break;
                        }
                    }
                } else {List<Object> list = current.array;
                    while (current.index < current.size) {
                        current.index++;
                        Object value = readValue(buffer, stack);
                        list.add(value);

                        if (stack.size() > stackSize) {
                            break;
                        }
                    }
                }

                if (stack.size() == stackSize) {
                    stack.removeFirst();
                    contextPool.release(current);
                }
            }
        } finally {
            for (ReaderContext ctx : stack) {
                contextPool.release(ctx);
            }
            stack.clear();
            stackPool.release(stack);
        }

        if (length > -1 && length < buffer.position()) {
            log.warn("Buffer is too small");
        }
    }

    private Object readValue(ByteBuffer buffer, ArrayDeque<ReaderContext> stack) {
        byte b = buffer.get();
        if ((b & 0x80) == 0) {
            // Positive fixint: 0x00-0x7F (most common for small integers)
            return (int) b;
        }

        int unsigned = b & 0xFF;

        if (unsigned >= 0xA0 && unsigned <= 0xBF) {
            // Fixstr: 0xA0-0xBF - use thread-local buffer + string cache
            int len = unsigned & 0x1F;
            validateDataLength(len, buffer, "fixstr");
            byte[] strBuf = stringBuffer.get();
            if (strBuf.length < len) {
                strBuf = new byte[STRING_BUFFER_SIZE * 2];
                stringBuffer.set(strBuf);
            }
            buffer.get(strBuf, 0, len);
            return stringCacheThreadLocal.get().intern(strBuf, 0, len);
        }

        if (unsigned <= 0x8F) {
            // Fixmap: 0x80-0x8F - push to stack
            int size = unsigned & 0x0F;
            Map<Object, Object> map = mapPoolThreadLocal.get().get();
            stack.addFirst(contextPool.get().initMap(map, size));
            return map;
        }

        if (unsigned >= 0xE0) {
            // Negative fixint: 0xE0-0xFF
            return (int) b;
        }

        if (unsigned <= 0x9F) {
            // Fixarray: 0x90-0x9F - push to stack
            int size = unsigned & 0x0F;
            List<Object> list = new ArrayList<>(size);
            if (size > 0) {
                stack.addFirst(contextPool.get().initArray(list, size));
            }
            return list;
        }

        // Less common types
        return switch (unsigned) {
            case 0xC0 -> null;  // NIL
            case 0xC2 -> false; // FALSE
            case 0xC3 -> true;  // TRUE
            case 0xCC -> buffer.get() & 0xFF;     // UINT8
            case 0xCD -> buffer.getShort() & 0xFFFF; // UINT16
            case 0xCE -> buffer.getInt() & 0xFFFFFFFFL; // UINT32
            case 0xCF -> buffer.getLong(); // UINT64
            case 0xD0 -> (int) buffer.get();   // INT8
            case 0xD1 -> (int) buffer.getShort(); // INT16
            case 0xD2 -> buffer.getInt();  // INT32
            case 0xD3 -> buffer.getLong(); // INT64
            case 0xCA -> buffer.getFloat();  // FLOAT32
            case 0xCB -> buffer.getDouble(); // FLOAT64
            case 0xD9 -> readString(buffer, buffer.get() & 0xFF);    // STR8
            case 0xDA -> readString(buffer, buffer.getShort() & 0xFFFF); // STR16
            case 0xDB -> readString(buffer, buffer.getInt()); // STR32
            case 0xC4 -> readBinary(buffer, buffer.get() & 0xFF);    // BIN8
            case 0xC5 -> readBinary(buffer, buffer.getShort() & 0xFFFF); // BIN16
            case 0xC6 -> readBinary(buffer, buffer.getInt()); // BIN32
            case 0xDC -> readArray(stack, buffer.getShort() & 0xFFFF); // ARRAY16
            case 0xDD -> readArray(stack, buffer.getInt()); // ARRAY32
            case 0xDE -> readMap(stack, buffer.getShort() & 0xFFFF); // MAP16
            case 0xDF -> readMap(stack, buffer.getInt()); // MAP32
            case 0xD4 -> readExtension(buffer, 1);
            case 0xD5 -> readExtension(buffer, 2);
            case 0xD6 -> readExtension(buffer, 4);
            case 0xD7 -> readExtension(buffer, 8);
            case 0xD8 -> readExtension(buffer, 16);
            case 0xC7 -> readExtension(buffer, buffer.get() & 0xFF);
            case 0xC8 -> readExtension(buffer, buffer.getShort() & 0xFFFF);
            case 0xC9 -> readExtension(buffer, buffer.getInt());
            case 0xC1 -> throw new MessagePackException("Invalid format byte 0xC1");
            default -> throw new MessagePackException("Unknown format byte 0x" + Integer.toHexString(unsigned));
        };
    }

    private Map<Object, Object> readMap(ArrayDeque<ReaderContext> stack, int size) {
        Objects.requireNonNull(stack, "Map cannot be used as key");
        validateCollectionSize(size, "map");

        Map<Object, Object> map = mapPoolThreadLocal.get().get();
        stack.addFirst(contextPool.get().initMap(map, size));
        return map;
    }

    private List<Object> readArray(ArrayDeque<ReaderContext> stack, int size) {
        Objects.requireNonNull(stack, "List cannot be used as key");
        validateCollectionSize(size, "array");

        List<Object> list = new ArrayList<>(size);
        stack.addFirst(contextPool.get().initArray(list, size));
        return list;
    }

    private void validateCollectionSize(int size, String type) {
        if (size < 0) {
            throw new MessagePackException("Negative " + type + " size: " + size);
        }
        if (size > maxCollectionSize) {
            throw new MessagePackException(type + " size " + size + " exceeds maximum " + maxCollectionSize);
        }
    }

    private void validateDataLength(int length, ByteBuffer buffer, String type) {
        if (length < 0) {
            throw new MessagePackException("Negative " + type + " length: " + length);
        }
        if (length > buffer.remaining()) {
            throw new MessagePackException(type + " length " + length + " exceeds remaining buffer " + buffer.remaining());
        }
    }

    private Object readBinary(ByteBuffer buffer, int length) {
        validateDataLength(length, buffer, "binary");
        if (useByteBufferForBinary) {
            ByteBuffer byteBuffer;
            if (useProjectionsForByteBuffer) {
                byteBuffer = buffer.slice(buffer.position(), length);
                buffer.position(buffer.position() + length);
            } else {
                byteBuffer = ByteBuffer.allocateDirect(length);
                int oldLimit = buffer.limit();
                buffer.limit(buffer.position() + length);
                byteBuffer.put(buffer);
                buffer.limit(oldLimit);
                byteBuffer.flip();
            }
            return byteBuffer;
        } else {
            byte[] data = new byte[length];
            buffer.get(data, 0, length);
            return data;
        }
    }

    private Object readExtension(ByteBuffer buffer, int length) {
        if (length < 0) {
            throw new MessagePackException("Negative extension length: " + length);
        }
        if (length + 1 < 0 || length + 1 > buffer.remaining()) { // +1 for ext type byte; overflow-safe
            throw new MessagePackException("extension length " + length + " exceeds remaining buffer " + buffer.remaining());
        }
        byte extType = buffer.get();
        if (extType == -1) {
            return readTimestamp(buffer, length);
        }
        byte[] data = new byte[length];
        buffer.get(data);
        return new MessagePackExtension(extType, data);
    }

    private Object readTimestamp(ByteBuffer buffer, int length) {
        return switch (length) {
            case 4 -> {
                long seconds = buffer.getInt() & 0xFFFFFFFFL;
                yield timestampAsEpochMillis ? seconds * 1000L : Instant.ofEpochSecond(seconds);
            }
            case 8 -> {
                long val = buffer.getLong();
                int nanos = (int) (val >>> 34);
                long seconds = val & 0x3FFFFFFFFL;
                yield timestampAsEpochMillis ? seconds * 1000L + nanos / 1_000_000L : Instant.ofEpochSecond(seconds, nanos);
            }
            case 12 -> {
                int nanos = buffer.getInt();
                long seconds = buffer.getLong();
                yield timestampAsEpochMillis ? seconds * 1000L + nanos / 1_000_000L : Instant.ofEpochSecond(seconds, nanos);
            }
            default -> throw new MessagePackException("Invalid timestamp length: " + length);
        };
    }

    private String readString(ByteBuffer buffer, int len) {
        validateDataLength(len, buffer, "string");
        byte[] strBuf = stringBuffer.get();
        if (strBuf.length < len) {
            strBuf = new byte[len];
            stringBuffer.set(strBuf);
        }
        buffer.get(strBuf, 0, len);
        return stringCacheThreadLocal.get().intern(strBuf, 0, len);
    }

    static final class MapPool {
        private final CompactMap[] maps;
        private int index;

        MapPool(int size) {
            this.maps = new CompactMap[size];
            for (int i = 0; i < size; i++) {
                maps[i] = new CompactMap();
            }
        }

        Map<Object, Object> get() {
            if (index < maps.length) {
                CompactMap map = maps[index++];
                map.clear();
                return map;
            }
            return new CompactMap();
        }

        void reset() {
            index = 0;
        }
    }

    private int getMapSize(ByteBuffer buffer) {
        byte b = buffer.get();
        int unsigned = b & 0xFF;

        int size;
        if (unsigned >= 0x80 && unsigned <= 0x8F) {
            size = unsigned & 0x0F;
        } else if (unsigned == 0xDE) {
            size = buffer.getShort() & 0xFFFF;
        } else if (unsigned == 0xDF) {
            size = buffer.getInt();
        } else {
            throw new MessagePackException("Unexpected type 0x" + Integer.toHexString(unsigned));
        }
        validateCollectionSize(size, "root map");
        return size;
    }

    /**
     * Thread-local open-addressing string cache. After warmup with protocol strings,
     * {@link #intern} returns cached instances with zero allocation.
     */
    static final class StringCache {
        private static final int CAPACITY = 64;
        private static final int MASK = CAPACITY - 1;
        private final byte[][] cachedBytes = new byte[CAPACITY][];
        private final String[] cachedStrings = new String[CAPACITY];

        String intern(byte[] buf, int offset, int len) {
            int h = hash(buf, offset, len) & MASK;
            byte[] existing = cachedBytes[h];
            if (existing != null && existing.length == len && bytesEqual(existing, buf, offset, len)) {
                return cachedStrings[h];
            }
            String s = new String(buf, offset, len, StandardCharsets.UTF_8);
            byte[] copy = new byte[len];
            System.arraycopy(buf, offset, copy, 0, len);
            cachedBytes[h] = copy;
            cachedStrings[h] = s;
            return s;
        }

        private static int hash(byte[] buf, int offset, int len) {
            int h = 1;
            for (int i = 0; i < len; i++) {
                h = 31 * h + buf[offset + i];
            }
            return h;
        }

        private static boolean bytesEqual(byte[] a, byte[] b, int bOffset, int len) {
            for (int i = 0; i < len; i++) {
                if (a[i] != b[bOffset + i]) return false;
            }
            return true;
        }
    }
}
