package su.grinev.messagepack;

import lombok.Getter;
import lombok.Setter;
import su.grinev.BinaryDocument;
import su.grinev.Serializer;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.Pool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MessagePackWriter implements Serializer {
    private final Pool<WriterContext> contextPool;
    private final Pool<ArrayDeque<WriterContext>> stackPool;
    private final Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, byte[]>> stringBytesCache = ThreadLocal.withInitial(HashMap::new);
    @Setter
    @Getter
    private boolean writeLengthHeader;

    public MessagePackWriter(Pool<WriterContext> contextPool, Pool<ArrayDeque<WriterContext>> stackPool) {
        this.contextPool = contextPool;
        this.stackPool = stackPool;
        writeLengthHeader = true;
    }

    @SuppressWarnings("unchecked")
    public void serialize(DynamicByteBuffer buffer, BinaryDocument document) {
        buffer.getBuffer().clear().order(ByteOrder.BIG_ENDIAN);
        if (writeLengthHeader) {
            buffer.putInt(0);
        }
        Map<Object, Object> documentMap = document.getDocumentMap();
        ArrayDeque<WriterContext> stack = stackPool.get();

        try {
            stack.push(contextPool.get().initMap(mapIterator(documentMap)));

            writeMapHeader(buffer, documentMap.size());

            while (!stack.isEmpty()) {
                WriterContext context = stack.getFirst();
                int stackSize = stack.size();

                if (!context.isArray) {
                    while (context.objectMap.hasNext() && stack.size() == stackSize) {
                        Map.Entry<Object, Object> objectEntry = context.objectMap.next();
                        Object keyObj = objectEntry.getKey();
                        if (keyObj instanceof String s) {
                            byte[] keyBytes = keyCache.computeIfAbsent(s, k -> k.getBytes(StandardCharsets.UTF_8));
                            doWriteString(buffer, keyBytes);
                        } else {
                            writeValue(stack, buffer, keyObj);
                        }
                        writeValue(stack, buffer, objectEntry.getValue());
                    }
                } else {
                    while (context.array.hasNext() && stack.size() == stackSize) {
                        Object value = context.array.next();
                        writeValue(stack, buffer, value);
                    }
                }

                if (stack.size() == stackSize) {
                    WriterContext ctx = stack.removeFirst();
                    contextPool.release(ctx);
                }
            }

            if (writeLengthHeader) {
                int bufferSize = buffer.getBuffer().position();
                buffer.position(0).putInt(bufferSize);
                buffer.position(bufferSize);
            }
            buffer.flip();
        } finally {
            stackPool.release(stack);
        }
    }

    private void writeMapHeader(DynamicByteBuffer buffer, int size) {
        if (size < 16) {
            buffer.put((byte) (0x80 | size));
        } else if (size < 65536) {
            buffer.put((byte) 0xDE);
            buffer.putShort((short) size);
        } else {
            buffer.put((byte) 0xDF);
            buffer.putInt(size);
        }
    }

    private void writeArrayHeader(DynamicByteBuffer buffer, int size) {
        if (size < 16) {
            buffer.put((byte) (0x90 | size));
        } else if (size < 65536) {
            buffer.put((byte) 0xDC);
            buffer.putShort((short) size);
        } else {
            buffer.put((byte) 0xDD);
            buffer.putInt(size);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeValue(ArrayDeque<WriterContext> stack, DynamicByteBuffer buffer, Object value) {
        switch (value) {
            case null -> buffer.put((byte) 0xC0);
            case Boolean b -> buffer.put(b ? (byte) 0xC3 : (byte) 0xC2);
            case Integer i -> writeInt(buffer, i);
            case Long l -> writeLong(buffer, l);
            case Float f -> buffer.put((byte) 0xCA).putFloat(f);
            case Double d -> buffer.put((byte) 0xCB).putDouble(d);
            case String s -> writeString(buffer, s);
            case byte[] bytes -> writeBinary(buffer, bytes);
            case ByteBuffer bb -> writeBinary(buffer, bb);
            case List list -> {
                writeArrayHeader(buffer, list.size());
                WriterContext writerContext = contextPool.get().initList(list.iterator());
                stack.push(writerContext);
            }
            case Map map -> {
                writeMapHeader(buffer, map.size());
                WriterContext writerContext = contextPool.get().initMap(mapIterator(map));
                stack.push(writerContext);
            }
            case MessagePackExtension ext -> writeExtension(buffer, ext);
            case Instant inst -> writeTimestamp(buffer, inst);
            case LocalDateTime ldt -> writeTimestamp(buffer, ldt.toInstant(ZoneOffset.UTC));
            default -> throw new MessagePackException("Unsupported type: " + value.getClass().getName());
        }
    }

    private void writeInt(DynamicByteBuffer buffer, int value) {
        if (value >= 0) {
            if (value <= 0x7F) {
                buffer.put((byte) value);                       // positive fixint
            } else if (value <= 0xFF) {
                buffer.put((byte) 0xCC).put((byte) value);     // uint8
            } else if (value <= 0xFFFF) {
                buffer.put((byte) 0xCD).putShort((short) value); // uint16
            } else {
                buffer.put((byte) 0xCE).putInt(value);          // uint32
            }
        } else {
            if (value >= -32) {
                buffer.put((byte) value);                       // negative fixint
            } else if (value >= -128) {
                buffer.put((byte) 0xD0).put((byte) value);     // int8
            } else if (value >= -32768) {
                buffer.put((byte) 0xD1).putShort((short) value); // int16
            } else {
                buffer.put((byte) 0xD2).putInt(value);          // int32
            }
        }
    }

    private void writeLong(DynamicByteBuffer buffer, long value) {
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            writeInt(buffer, (int) value);
        } else if (value > 0 && value <= 0xFFFFFFFFL) {
            buffer.put((byte) 0xCE).putInt((int) value);       // uint32
        } else {
            buffer.put((byte) 0xD3).putLong(value);
        }
    }

    private void writeString(DynamicByteBuffer buffer, String value) {
        Map<String, byte[]> cache = stringBytesCache.get();
        byte[] bytes = cache.get(value);
        if (bytes == null) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
            cache.put(value, bytes);
        }
        doWriteString(buffer, bytes);
    }

    private void doWriteString(DynamicByteBuffer buffer, byte[] stringBytes) {
        int len = stringBytes.length;
        if (len < 32) {
            buffer.put((byte) (0xA0 | len));
        } else if (len < 256) {
            buffer.put((byte) 0xD9).put((byte) len);
        } else if (len < 65536) {
            buffer.put((byte) 0xDA).putShort((short) len);
        } else {
            buffer.put((byte) 0xDB).putInt(len);
        }
        buffer.put(stringBytes);
    }

    private void writeBinary(DynamicByteBuffer buffer, byte[] bytes) {
        int len = bytes.length;
        if (len < 256) {
            buffer.put((byte) 0xC4).put((byte) len);
        } else if (len < 65536) {
            buffer.put((byte) 0xC5).putShort((short) len);
        } else {
            buffer.put((byte) 0xC6).putInt(len);
        }
        buffer.put(bytes);
    }

    private void writeBinary(DynamicByteBuffer buffer, ByteBuffer bb) {
        int len = bb.remaining();
        if (len < 256) {
            buffer.put((byte) 0xC4).put((byte) len);
        } else if (len < 65536) {
            buffer.put((byte) 0xC5).putShort((short) len);
        } else {
            buffer.put((byte) 0xC6).putInt(len);
        }
        buffer.getBuffer().put(bb);
    }

    private void writeExtension(DynamicByteBuffer buffer, MessagePackExtension ext) {
        int len = ext.data().length;
        switch (len) {
            case 1 -> buffer.put((byte) 0xD4);
            case 2 -> buffer.put((byte) 0xD5);
            case 4 -> buffer.put((byte) 0xD6);
            case 8 -> buffer.put((byte) 0xD7);
            case 16 -> buffer.put((byte) 0xD8);
            default -> {
                if (len < 256) {
                    buffer.put((byte) 0xC7).put((byte) len);
                } else if (len < 65536) {
                    buffer.put((byte) 0xC8).putShort((short) len);
                } else {
                    buffer.put((byte) 0xC9).putInt(len);
                }
            }
        }
        buffer.put(ext.type()).put(ext.data());
    }

    private void writeTimestamp(DynamicByteBuffer buffer, Instant instant) {
        long seconds = instant.getEpochSecond();
        int nanos = instant.getNano();

        if (nanos == 0 && seconds >= 0 && seconds <= 0xFFFFFFFFL) {
            // Timestamp 32: fixext 4 (0xD6), type=-1, 4 bytes uint32 seconds
            buffer.put((byte) 0xD6).put((byte) -1).putInt((int) seconds);
        } else if (seconds >= 0 && seconds <= 0x3FFFFFFFFL) {
            // Timestamp 64: fixext 8 (0xD7), type=-1, 8 bytes
            // Upper 30 bits = nanoseconds, lower 34 bits = seconds
            long val = ((long) nanos << 34) | seconds;
            buffer.put((byte) 0xD7).put((byte) -1).putLong(val);
        } else {
            // Timestamp 96: ext 8 format (0xC7), length=12, type=-1, 4 bytes nanos + 8 bytes seconds
            buffer.put((byte) 0xC7).put((byte) 12).put((byte) -1).putInt(nanos).putLong(seconds);
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterator<Map.Entry<Object, Object>> mapIterator(Map<?, ?> map) {
        if (map instanceof CompactMap cm) {
            return cm.entryIterator();
        }
        return ((Map<Object, Object>) map).entrySet().iterator();
    }
}
