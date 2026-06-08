package su.grinev;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.MessagePackWriter;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.WriterContext;
import su.grinev.pool.DisposablePool;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.Pool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MessagePackBenchmark {

    private MessagePackWriter messagePackWriter;
    private MessagePackReader messagePackReader;
    private BinaryDocument document128kb;
    private ByteBuffer serialized128kb;
    private BinaryDocument manyFieldsDocument;
    private ByteBuffer manyFieldsSerialized;
    private BinaryDocument simpleDocument;
    private ByteBuffer simpleSerialized;
    private DisposablePool<DynamicByteBuffer> bufferPool;

    @Setup(Level.Trial)
    public void setup() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(1000)
                .setBlocking(true)
                .setOutOfPoolTimeout(1000)
                .build();

        Pool<ReaderContext> readerContextPool = poolFactory.getPool(ReaderContext::new);
        Pool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));
        Pool<WriterContext> writerContextPool = poolFactory.getPool(WriterContext::new);
        Pool<ArrayDeque<WriterContext>> writerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(16));
        bufferPool = poolFactory.getDisposablePool(() -> new DynamicByteBuffer(256 * 1024, true));

        messagePackWriter = new MessagePackWriter(writerContextPool, writerStackPool);
        messagePackReader = new MessagePackReader(readerContextPool, stackPool, false, false);

        // Create document with 128KB binary payload
        byte[] largePayload = new byte[128 * 1024];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 128);
        }

        Map<Object, Object> payload128kb = new HashMap<>();
        payload128kb.put(0, "FORWARD_PACKET");
        payload128kb.put(1, 12345L);
        payload128kb.put(2, System.currentTimeMillis());
        payload128kb.put(3, largePayload);
        document128kb = new BinaryDocument(payload128kb);

        // Pre-serialize for deserialization benchmark
        DynamicByteBuffer buffer = bufferPool.get();
        messagePackWriter.serialize(buffer, document128kb);
        serialized128kb = ByteBuffer.allocateDirect(buffer.getBuffer().remaining());
        serialized128kb.put(buffer.getBuffer());
        serialized128kb.flip();
        buffer.dispose();
        bufferPool.release(buffer);

        // Setup many fields benchmark - 1000 nested objects
        Map<Object, Object> fields = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            Map<Integer, Object> nested = new HashMap<>();
            nested.put(0, i);
            nested.put(1, "item_" + i);
            nested.put(2, i % 2 == 0);
            nested.put(3, i * 1.5);
            nested.put(4, List.of("tag1", "tag2", "tag3"));
            fields.put(i, nested);
        }
        manyFieldsDocument = new BinaryDocument(fields);

        // Pre-serialize many fields for deserialization benchmark
        DynamicByteBuffer manyFieldsBuffer = bufferPool.get();
        messagePackWriter.serialize(manyFieldsBuffer, manyFieldsDocument);
        manyFieldsSerialized = ByteBuffer.allocateDirect(manyFieldsBuffer.getBuffer().remaining());
        manyFieldsSerialized.put(manyFieldsBuffer.getBuffer());
        manyFieldsSerialized.flip();
        manyFieldsBuffer.dispose();
        bufferPool.release(manyFieldsBuffer);

        // Setup simple document benchmark
        Map<Object, Object> simple = new HashMap<>();
        simple.put(0, 42);
        simple.put(1, "test");
        simple.put(2, true);
        simple.put(3, 3.14159);
        simpleDocument = new BinaryDocument(simple);

        DynamicByteBuffer simpleBuffer = bufferPool.get();
        messagePackWriter.serialize(simpleBuffer, simpleDocument);
        simpleSerialized = ByteBuffer.allocateDirect(simpleBuffer.getBuffer().remaining());
        simpleSerialized.put(simpleBuffer.getBuffer());
        simpleSerialized.flip();
        simpleBuffer.dispose();
        bufferPool.release(simpleBuffer);
    }

    // --- 128KB payload benchmarks ---

    @Benchmark
    public DynamicByteBuffer serialize128kb() {
        DynamicByteBuffer buffer = bufferPool.get();
        messagePackWriter.serialize(buffer, document128kb);
        buffer.dispose();
        bufferPool.release(buffer);
        return buffer;
    }

    @Benchmark
    public BinaryDocument deserialize128kb() {
        serialized128kb.rewind();
        BinaryDocument result = new BinaryDocument(new HashMap<>());
        messagePackReader.deserialize(serialized128kb, result);
        return result;
    }

    @Benchmark
    public BinaryDocument roundtrip128kb() {
        DynamicByteBuffer buffer = bufferPool.get();
        messagePackWriter.serialize(buffer, document128kb);
        BinaryDocument result = new BinaryDocument(new HashMap<>());
        messagePackReader.deserialize(buffer.getBuffer(), result);
        buffer.dispose();
        bufferPool.release(buffer);
        return result;
    }

    // --- Many small objects benchmark ---

    @Benchmark
    public DynamicByteBuffer serializeManyFields() {
        DynamicByteBuffer buffer = bufferPool.get();
        messagePackWriter.serialize(buffer, manyFieldsDocument);
        buffer.dispose();
        bufferPool.release(buffer);
        return buffer;
    }

    @Benchmark
    public BinaryDocument deserializeManyFields() {
        manyFieldsSerialized.rewind();
        BinaryDocument result = new BinaryDocument(new HashMap<>());
        messagePackReader.deserialize(manyFieldsSerialized, result);
        return result;
    }

    @Benchmark
    public BinaryDocument roundtripManyFields() {
        DynamicByteBuffer buffer = bufferPool.get();
        messagePackWriter.serialize(buffer, manyFieldsDocument);
        BinaryDocument result = new BinaryDocument(new HashMap<>());
        messagePackReader.deserialize(buffer.getBuffer(), result);
        buffer.dispose();
        bufferPool.release(buffer);
        return result;
    }

    // --- Simple object benchmark ---

    @Benchmark
    public DynamicByteBuffer serializeSimple() {
        DynamicByteBuffer buffer = bufferPool.get();
        messagePackWriter.serialize(buffer, simpleDocument);
        buffer.dispose();
        bufferPool.release(buffer);
        return buffer;
    }

    @Benchmark
    public BinaryDocument deserializeSimple() {
        simpleSerialized.rewind();
        BinaryDocument result = new BinaryDocument(new HashMap<>());
        messagePackReader.deserialize(simpleSerialized, result);
        return result;
    }

    @Benchmark
    public BinaryDocument roundtripSimple() {
        DynamicByteBuffer buffer = bufferPool.get();
        messagePackWriter.serialize(buffer, simpleDocument);
        BinaryDocument result = new BinaryDocument(new HashMap<>());
        messagePackReader.deserialize(buffer.getBuffer(), result);
        buffer.dispose();
        bufferPool.release(buffer);
        return result;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(MessagePackBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
