package su.grinev.bson;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import su.grinev.BinaryDocument;
import su.grinev.Deserializer;
import su.grinev.exception.BsonException;
import su.grinev.pool.Pool;
import su.grinev.pool.PoolFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
public class BsonObjectReader implements Deserializer {
    private final Pool<ReaderContext> contextPool;
    private final Pool<byte[]> packetPool;
    private final Pool<ArrayDeque<ReaderContext>> stackPool;
    private Pool<ByteBuffer> binaryPacketPool;
    private final int documentSizeLimit;
    @Setter
    private boolean readBinaryAsByteArray = true;
    private final boolean enableBufferProjection;
    private final Map<Integer, Function<ByteBuffer, Object>> customDeserializer = new HashMap<>();

    public BsonObjectReader(
            PoolFactory poolFactory,
            int documentSizeLimit,
            boolean enableBufferProjection,
            Supplier<ByteBuffer> byteBufferAllocator
    ) {
        this.documentSizeLimit = documentSizeLimit;
        this.enableBufferProjection = enableBufferProjection;
        contextPool = poolFactory.getPool("bson-reader-context-pool", ReaderContext::new);
        packetPool = poolFactory.getPool("bson-reader-input-steam-pool", () -> new byte[documentSizeLimit]);
        stackPool = poolFactory.getPool("bson-reader-stack-pool", () -> new ArrayDeque<>(64));
        if (!enableBufferProjection) {
            binaryPacketPool = poolFactory.getPool("bson-reader-packet-pool", byteBufferAllocator);
        }
    }

    public void deserialize(ByteBuffer buffer, BinaryDocument binaryDocument) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        Map<Object, Object> rootDocument = binaryDocument.getDocumentMap();
        BsonReader bsonReader = new BsonByteBufferReader(buffer, binaryPacketPool);
        ArrayDeque<ReaderContext> stack = stackPool.get();

        try {
            int rootDocumentLength = bsonReader.readInt();
            if (rootDocumentLength > documentSizeLimit) {
                throw new BsonException("Document is too big");
            }

            ReaderContext ctx = contextPool.get()
                    .setLength(rootDocumentLength)
                    .setValue(rootDocument);
            stack.addFirst(ctx);

            while (!stack.isEmpty()) {
                ctx = stack.getFirst();
                int stackSizeBefore = stack.size();

                if (ctx.getValue() instanceof Map map) {
                    while (true) {
                        int type = bsonReader.readByte();
                        if (type == 0) {
                            break;
                        }
                        Integer key = Integer.parseInt(bsonReader.readCString());
                        Object value = doReadValue(bsonReader, ctx, stack, type);
                        map.put(key, value);

                        if (stack.size() > stackSizeBefore) {
                            break;
                        }
                    }
                } else if (ctx.getValue() instanceof List list) {
                    int index = 0;
                    while (true) {
                        int type = bsonReader.readByte();
                        if (type == 0) {
                            break;
                        }
                        bsonReader.readCString(); // Skip array index key
                        Object value = doReadValue(bsonReader, ctx, stack, type);
                        list.add(index++, value);

                        if (stack.size() > stackSizeBefore) {
                            break;
                        }
                    }
                }

                if (ctx == stack.getFirst()) {
                    stack.removeFirst();
                    contextPool.release(ctx);
                }
            }

        } finally {
            for (ReaderContext ctx : stack) {
                contextPool.release(ctx);
            }
            stack.clear();
            stackPool.release(stack);
        }
    }

    public void deserialize(InputStream inputStream, BinaryDocument binaryDocument) throws IOException {
        byte[] lengthBytes = inputStream.readNBytes(4);
        if (lengthBytes.length != 4) {
            throw new IOException("Unable to read document length");
        }

        int totalLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (totalLength <= 0 || totalLength > documentSizeLimit) {
            throw new IOException("Invalid document length: " + totalLength + " (limit: " + documentSizeLimit + ")");
        }
        byte[] documentBytes = packetPool.get();
        try {
            System.arraycopy(lengthBytes, 0, documentBytes, 0, 4);

            int offset = 4;
            while (offset < totalLength) {
                int read = inputStream.read(documentBytes, offset, totalLength - offset);
                if (read == -1) {
                    throw new IOException("Unexpected end of stream");
                }
                offset += read;
            }

            ByteBuffer buffer = ByteBuffer.wrap(documentBytes);
            deserialize(buffer, binaryDocument);
        } finally {
            packetPool.release(documentBytes);
        }
    }

    private Object doReadValue(BsonReader objectReader, ReaderContext ctx, Deque<ReaderContext> stack, int type) {
        return switch (type) {
            case 0x01 -> objectReader.readDouble();
            case 0x02 -> objectReader.readString(); // UTF-8 String
            case 0x03 -> { // Embedded document
                int len = objectReader.readInt();
                if (len < 0) {
                    throw new BsonException("Negative nested document length: " + len);
                }
                if (len > ctx.getLength()) {
                    throw new BsonException("Nested document cannot have more than " + ctx.getLength() + " bytes");
                }

                Object value = new HashMap<>(8);
                ReaderContext readerContext = contextPool.get()
                        .setLength(len)
                        .setValue(value);
                stack.addFirst(readerContext);
                yield value;
            }
            case 0x04 -> { // Array
                int len = objectReader.readInt();
                if (len < 0) {
                    throw new BsonException("Negative nested array length: " + len);
                }
                if (len > ctx.getLength()) {
                    throw new BsonException("Nested document cannot have more than " + ctx.getLength() + " bytes");
                }

                Object value = new ArrayList<>(8);
                ReaderContext readerContext = contextPool.get()
                        .setLength(len)
                        .setValue(value);
                stack.addFirst(readerContext);
                yield value;
            }
            case 0x05 -> {
                if (readBinaryAsByteArray) {
                    yield objectReader.readBinaryAsArray();
                } else {
                    yield objectReader.readBinary(enableBufferProjection);
                }
            }
            case 0x07 -> objectReader.readObjectId();
            case 0x08 -> objectReader.readBoolean();
            case 0x09 -> objectReader.readDateTime();
            case 0x0A -> null;
            case 0x10 -> objectReader.readInt();
            case 0x12 -> objectReader.readLong();
            case 0x13 -> objectReader.readDecimal128();
            default -> customDeserializer.computeIfAbsent(type, i -> { throw new IllegalArgumentException("Unsupported BSON type: 0x" + Integer.toHexString(type)); });
        };
    }
}
