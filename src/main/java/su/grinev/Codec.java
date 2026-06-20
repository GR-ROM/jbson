package su.grinev;

import lombok.Getter;
import su.grinev.bson.BsonObjectReader;
import su.grinev.bson.BsonObjectWriter;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.MessagePackWriter;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.WriterContext;
import su.grinev.pool.DisposablePool;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.FastPool;
import su.grinev.pool.PoolFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.function.Supplier;

@Getter
public class Codec {
    private final Binder binder;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final DisposablePool<DynamicByteBuffer> bufferPool;
    private final ThreadLocal<byte[]> serializeChunk = ThreadLocal.withInitial(() -> new byte[8192]);

    public Codec(Serializer serializer, Deserializer deserializer, DisposablePool<DynamicByteBuffer> bufferPool, Binder.ClassNameMode classNameMode) {
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.bufferPool = bufferPool;
        this.binder = new Binder(classNameMode);
    }

    public static Codec bson(PoolFactory poolFactory, int documentSize, Supplier<ByteBuffer> byteBufferAllocator) {
        return bson(poolFactory, documentSize, byteBufferAllocator, true, Binder.ClassNameMode.FULL_NAME);
    }

    public static Codec bson(PoolFactory poolFactory, int documentSize, Supplier<ByteBuffer> byteBufferAllocator, boolean readBinaryAsByteArray) {
        return bson(poolFactory, documentSize, byteBufferAllocator, readBinaryAsByteArray, Binder.ClassNameMode.FULL_NAME);
    }

    public static Codec bson(PoolFactory poolFactory, int documentSize, Supplier<ByteBuffer> byteBufferAllocator, boolean readBinaryAsByteArray, Binder.ClassNameMode classNameMode) {
        BsonObjectWriter writer = new BsonObjectWriter(poolFactory, documentSize, true);
        BsonObjectReader reader = new BsonObjectReader(poolFactory, documentSize, true, byteBufferAllocator);
        reader.setReadBinaryAsByteArray(readBinaryAsByteArray);
        DisposablePool<DynamicByteBuffer> pool = poolFactory.getDisposablePool("codec-buffer-pool", () -> new DynamicByteBuffer(documentSize, true));
        return new Codec(writer, reader, pool, classNameMode);
    }

    public static Codec messagePack(PoolFactory poolFactory, int documentSize) {
        return messagePack(poolFactory, documentSize, Binder.ClassNameMode.FULL_NAME);
    }

    public static Codec messagePack(PoolFactory poolFactory, int documentSize, Binder.ClassNameMode classNameMode) {
        FastPool<WriterContext> writerContextPool = poolFactory.getPool("msgpack-writer-context-pool", WriterContext::new);
        FastPool<ReaderContext> readerContextPool = poolFactory.getPool("msgpack-reader-context-pool", ReaderContext::new);
        FastPool<ArrayDeque<ReaderContext>> readerStackPool = poolFactory.getPool("msgpack-reader-stack-pool", () -> new ArrayDeque<>(64));
        FastPool<ArrayDeque<WriterContext>> writerStackPool = poolFactory.getPool("msgpack-writer-stack-pool", () -> new ArrayDeque<>(64));
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        MessagePackReader reader = new MessagePackReader(readerContextPool, readerStackPool, true, true);
        DisposablePool<DynamicByteBuffer> pool = poolFactory.getDisposablePool("codec-buffer-pool", () -> new DynamicByteBuffer(documentSize, true));
        return new Codec(writer, reader, pool, classNameMode);
    }

    public DynamicByteBuffer serialize(Object o) {
        BinaryDocument document = binder.unbind(o);
        DynamicByteBuffer buffer = bufferPool.get();
        serializer.serialize(buffer, document);
        return buffer;
    }

    public <T> T deserialize(ByteBuffer buffer, Class<T> tClass) {
        BinaryDocument document = new BinaryDocument(new HashMap<>());
        deserializer.deserialize(buffer, document);
        return binder.bind(tClass, document);
    }

    public void serialize(Object o, OutputStream outputStream) throws IOException {
        try (DynamicByteBuffer buffer = bufferPool.get()) {
            BinaryDocument document = binder.unbind(o);
            serializer.serialize(buffer, document);
            ByteBuffer raw = buffer.getBuffer();
            byte[] chunk = serializeChunk.get();
            while (raw.hasRemaining()) {
                int len = Math.min(chunk.length, raw.remaining());
                raw.get(chunk, 0, len);
                outputStream.write(chunk, 0, len);
            }
        }
    }

    public <T> T deserialize(InputStream inputStream, Class<T> tClass) throws IOException {
        byte[] data = inputStream.readAllBytes();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        return deserialize(buffer, tClass);
    }
}
