package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.messagepack.*;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.Pool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class MessagePackTest {

    private final PoolFactory poolFactory = PoolFactory.Builder.builder()
            .setMinPoolSize(1)
            .setMaxPoolSize(10)
            .setOutOfPoolTimeout(1000)
            .setBlocking(false)
            .build();

    private final Pool<ReaderContext> readerContextPool = poolFactory.getPool(ReaderContext::new);
    private final Pool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));
    private final Pool<WriterContext> writerContextPool = poolFactory.getPool(WriterContext::new);
    private final Pool<ArrayDeque<WriterContext>> writerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(16));

    @Test
    public void serializeSimpleMap() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, 42);
        map.put(1, "test");

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(map));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        assertEquals(42, deserialized.get("0"));
        assertEquals("test", deserialized.get("1"));
    }

    @Test
    public void serializeNestedMap() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);

        Map<Object, Object> nested = new HashMap<>();
        nested.put(0, 2);
        nested.put(1, true);

        Map<Object, Object> root = new HashMap<>();
        root.put(0, 1);
        root.put(1, nested);

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(root));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        assertEquals(1, deserialized.get("0"));
        assertEquals(2, deserialized.get("1.0"));
        assertEquals(true, deserialized.get("1.1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void serializeComplexObject() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, null);
        map.put(1, true);
        map.put(2, 42);
        map.put(3, -10);
        map.put(4, 1000000L);
        map.put(5, 3.14f);
        map.put(6, 2.718281828);
        map.put(7, "hello");
        map.put(8, new byte[]{0x01, 0x02, 0x03});
        map.put(9, List.of(1, 2, 3));

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(map));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        assertNull(deserialized.get("0"));
        assertEquals(true, deserialized.get("1"));
        assertEquals(42, deserialized.get("2"));
        assertEquals(-10, deserialized.get("3"));
        assertEquals(1000000, ((Number) deserialized.get("4")).intValue());
        assertEquals(3.14f, ((Number) deserialized.get("5")).floatValue(), 0.001f);
        assertEquals(2.718281828, ((Number) deserialized.get("6")).doubleValue(), 0.0000001);
        assertEquals("hello", deserialized.get("7"));

        byte[] binary = (byte[]) deserialized.get("8");
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, binary);

        List<Object> array = (List<Object>) deserialized.get("9");
        assertEquals(3, array.size());
        assertEquals(1, array.get(0));
        assertEquals(2, array.get(1));
        assertEquals(3, array.get(2));
    }

    @Test
    public void serializeDeeplyNestedMaps() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);

        Map<Object, Object> level3 = new HashMap<>();
        level3.put(0, "deep");

        Map<Object, Object> level2 = new HashMap<>();
        level2.put(0, level3);

        Map<Object, Object> level1 = new HashMap<>();
        level1.put(0, level2);

        Map<Object, Object> root = new HashMap<>();
        root.put(0, level1);

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(root));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        assertEquals("deep", deserialized.get("0.0.0.0"));
    }

    @Test
    public void contextPoolReusesObjects() {
        PoolFactory localFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(1)
                .setMaxPoolSize(5)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        Pool<ReaderContext> localReaderPool = localFactory.getPool(ReaderContext::new);
        Pool<ArrayDeque<ReaderContext>> localStackPool = localFactory.getPool(() -> new ArrayDeque<>(64));
        Pool<WriterContext> localWriterPool = localFactory.getPool(WriterContext::new);
        Pool<ArrayDeque<WriterContext>> localWriterStackPool = localFactory.getPool(() -> new ArrayDeque<>(16));

        MessagePackWriter writer = new MessagePackWriter(localWriterPool, localWriterStackPool);
        MessagePackReader reader = new MessagePackReader(localReaderPool, localStackPool, false, false);

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);

        // Run multiple serialization/deserialization cycles
        for (int i = 0; i < 10; i++) {
            Map<Object, Object> map = new HashMap<>();
            map.put(0, i);
            map.put(1, Map.of(0, i * 2));

            writer.serialize(buffer, new BinaryDocument(map));
            ByteBuffer buf = buffer.getBuffer();

            BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
            reader.deserialize(buf, deserialized);
            assertEquals(i, deserialized.get("0"));
        }

        // Pool should have reused contexts - max pool size is 5, but we did 10 iterations
        // This verifies no memory leak and proper pool recycling
    }

    @Test
    public void serializeExtension() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, new MessagePackExtension((byte) 1, new byte[]{0x01, 0x02, 0x03, 0x04}));

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(map));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        MessagePackExtension ext = (MessagePackExtension) deserialized.get("0");
        assertEquals(1, ext.type());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, ext.data());
    }

    @Test
    public void serializeTimestamp64WithNanos() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        Instant now = Instant.now();

        Map<Object, Object> map = new HashMap<>();
        map.put(0, now);

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(map));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        assertEquals(now, deserialized.get("0"));
    }

    @Test
    public void serializeTimestamp32NoNanos() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        Instant ts = Instant.ofEpochSecond(1000000);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, ts);

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(map));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        assertEquals(ts, deserialized.get("0"));
    }

    @Test
    public void serializeTimestamp96NegativeSeconds() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        Instant ts = Instant.ofEpochSecond(-1, 500000000);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, ts);

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(map));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        assertEquals(ts, deserialized.get("0"));
    }

    @Test
    public void serializeTimestamp32Epoch() {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        Instant ts = Instant.EPOCH;

        Map<Object, Object> map = new HashMap<>();
        map.put(0, ts);

        DynamicByteBuffer buffer = new DynamicByteBuffer(129 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(map));
        ByteBuffer buf = buffer.getBuffer();

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(buf, deserialized);

        assertEquals(ts, deserialized.get("0"));
    }

    // --- Delta timing tests ---

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int MEASURE_ITERATIONS = 10000;

    @Test
    public void timingSerialize128kb() {
        PoolFactory perfPoolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(100)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        Pool<WriterContext> perfWriterPool = perfPoolFactory.getPool(WriterContext::new);
        Pool<ArrayDeque<WriterContext>> perfWriterStackPool = perfPoolFactory.getPool(() -> new ArrayDeque<>(16));
        MessagePackWriter writer = new MessagePackWriter(perfWriterPool, perfWriterStackPool);

        byte[] payload = new byte[128 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 128);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, "FORWARD");
        map.put(1, 12345L);
        map.put(2, payload);
        BinaryDocument doc = new BinaryDocument(map);

        DynamicByteBuffer buffer = new DynamicByteBuffer(256 * 1024, true);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            writer.serialize(buffer, doc);
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            writer.serialize(buffer, doc);
        }
        long elapsed = System.nanoTime() - start;

        double avgMicros = (elapsed / 1000.0) / MEASURE_ITERATIONS;
        double throughputMBps = (128.0 * 1024 * MEASURE_ITERATIONS) / (elapsed / 1_000_000_000.0) / (1024 * 1024);

        System.out.printf("Serialize 128KB: %.2f µs/op, %.2f MB/s%n", avgMicros, throughputMBps);
    }

    @Test
    public void timingDeserialize128kb() {
        PoolFactory perfPoolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(100)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        Pool<WriterContext> perfWriterPool = perfPoolFactory.getPool(WriterContext::new);
        Pool<ReaderContext> perfReaderPool = perfPoolFactory.getPool(ReaderContext::new);
        Pool<ArrayDeque<ReaderContext>> perfStackPool = perfPoolFactory.getPool(() -> new ArrayDeque<>(64));

        Pool<ArrayDeque<WriterContext>> perfWriterStackPool = perfPoolFactory.getPool(() -> new ArrayDeque<>(16));
        MessagePackWriter writer = new MessagePackWriter(perfWriterPool, perfWriterStackPool);
        MessagePackReader reader = new MessagePackReader(perfReaderPool, perfStackPool, false, false);

        byte[] payload = new byte[128 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 128);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, "FORWARD");
        map.put(1, 12345L);
        map.put(2, payload);

        DynamicByteBuffer serialized = new DynamicByteBuffer(256 * 1024, true);
        writer.serialize(serialized, new BinaryDocument(map));
        ByteBuffer data = ByteBuffer.allocateDirect(serialized.getBuffer().remaining());
        data.put(serialized.getBuffer());

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            data.rewind();
            reader.deserialize(data, new BinaryDocument(new HashMap<>()));
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            data.rewind();
            reader.deserialize(data, new BinaryDocument(new HashMap<>()));
        }
        long elapsed = System.nanoTime() - start;

        double avgMicros = (elapsed / 1000.0) / MEASURE_ITERATIONS;
        double throughputMBps = (128.0 * 1024 * MEASURE_ITERATIONS) / (elapsed / 1_000_000_000.0) / (1024 * 1024);

        System.out.printf("Deserialize 128KB: %.2f µs/op, %.2f MB/s%n", avgMicros, throughputMBps);
    }

    @Test
    public void timingRoundtrip128kb() {
        PoolFactory perfPoolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(100)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        Pool<WriterContext> perfWriterPool = perfPoolFactory.getPool(WriterContext::new);
        Pool<ReaderContext> perfReaderPool = perfPoolFactory.getPool(ReaderContext::new);
        Pool<ArrayDeque<ReaderContext>> perfStackPool = perfPoolFactory.getPool(() -> new ArrayDeque<>(64));

        Pool<ArrayDeque<WriterContext>> perfWriterStackPool = perfPoolFactory.getPool(() -> new ArrayDeque<>(16));
        MessagePackWriter writer = new MessagePackWriter(perfWriterPool, perfWriterStackPool);
        MessagePackReader reader = new MessagePackReader(perfReaderPool, perfStackPool, false, false);

        byte[] payload = new byte[128 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 128);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, "FORWARD");
        map.put(1, 12345L);
        map.put(2, payload);
        BinaryDocument doc = new BinaryDocument(map);

        DynamicByteBuffer buffer = new DynamicByteBuffer(256 * 1024, true);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            writer.serialize(buffer, doc);
            reader.deserialize(buffer.getBuffer(), new BinaryDocument(new HashMap<>()));
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            writer.serialize(buffer, doc);
            reader.deserialize(buffer.getBuffer(), new BinaryDocument(new HashMap<>()));
        }
        long elapsed = System.nanoTime() - start;

        double avgMicros = (elapsed / 1000.0) / MEASURE_ITERATIONS;
        double throughputMBps = (128.0 * 1024 * MEASURE_ITERATIONS) / (elapsed / 1_000_000_000.0) / (1024 * 1024);

        System.out.printf("Roundtrip 128KB: %.2f µs/op, %.2f MB/s%n", avgMicros, throughputMBps);
    }

    @Test
    public void timingManyFields() {
        PoolFactory perfPoolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(100)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        Pool<WriterContext> perfWriterPool = perfPoolFactory.getPool(WriterContext::new);
        Pool<ReaderContext> perfReaderPool = perfPoolFactory.getPool(ReaderContext::new);
        Pool<ArrayDeque<ReaderContext>> perfStackPool = perfPoolFactory.getPool(() -> new ArrayDeque<>(64));

        Pool<ArrayDeque<WriterContext>> perfWriterStackPool = perfPoolFactory.getPool(() -> new ArrayDeque<>(16));
        MessagePackWriter writer = new MessagePackWriter(perfWriterPool, perfWriterStackPool);
        MessagePackReader reader = new MessagePackReader(perfReaderPool, perfStackPool, false, false);

        // 1000 nested objects
        Map<Object, Object> fields = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            Map<Object, Object> nested = new HashMap<>();
            nested.put(0, i);
            nested.put(1, "item_" + i);
            nested.put(2, i % 2 == 0);
            nested.put(3, i * 1.5);
            fields.put(i, nested);
        }
        BinaryDocument doc = new BinaryDocument(fields);

        // Pre-serialize for deserialize test
        DynamicByteBuffer preSerialized = new DynamicByteBuffer(512 * 1024, true);
        writer.serialize(preSerialized, doc);
        ByteBuffer data = ByteBuffer.allocateDirect(preSerialized.getBuffer().remaining());
        data.put(preSerialized.getBuffer());
        int dataSize = data.position();

        DynamicByteBuffer buffer = new DynamicByteBuffer(512 * 1024, true);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            writer.serialize(buffer, doc);
        }

        // Measure serialize
        long startSer = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            writer.serialize(buffer, doc);
        }
        long elapsedSer = System.nanoTime() - startSer;

        // Warmup deserialize
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            data.rewind();
            reader.deserialize(data, new BinaryDocument(new HashMap<>()));
        }

        // Measure deserialize
        long startDe = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            data.rewind();
            reader.deserialize(data, new BinaryDocument(new HashMap<>()));
        }
        long elapsedDe = System.nanoTime() - startDe;

        double avgSerMicros = (elapsedSer / 1000.0) / MEASURE_ITERATIONS;
        double avgDeMicros = (elapsedDe / 1000.0) / MEASURE_ITERATIONS;
        double opsPerSec = MEASURE_ITERATIONS / (elapsedSer / 1_000_000_000.0);

        System.out.printf("Many fields (1000 nested, %d bytes):%n", dataSize);
        System.out.printf("  Serialize:   %.2f µs/op, %.0f ops/s%n", avgSerMicros, opsPerSec);
        System.out.printf("  Deserialize: %.2f µs/op, %.0f ops/s%n", avgDeMicros, MEASURE_ITERATIONS / (elapsedDe / 1_000_000_000.0));
    }

}
