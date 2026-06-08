package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.MessagePackWriter;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.WriterContext;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.Pool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MessagePackMapperTests {

    @Test
    public void serializeAndDeserializeObjectTest() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(100)
                .setMaxPoolSize(1000)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        Pool<ReaderContext> readerContextPool = poolFactory.getPool(ReaderContext::new);
        Pool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));
        Pool<WriterContext> writerContextPool = poolFactory.getPool(WriterContext::new);

        Pool<ArrayDeque<WriterContext>> writerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(16));
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);

        byte[] packet = new byte[1024];
        for (int i = 0; i < packet.length; i++) {
            packet[i] = (byte) i;
        }

        Map<Object, Object> data = new HashMap<>();
        data.put(0, packet);

        Map<Object, Object> request = new HashMap<>();
        request.put(0, "FOO");
        request.put(1, data);
        request.put(2, System.currentTimeMillis());

        BinaryDocument original = new BinaryDocument(request);

        DynamicByteBuffer b = new DynamicByteBuffer(8192, true);
        writer.serialize(b, original);
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        reader.deserialize(b.getBuffer(), deserialized);

        assertEquals(original.get("0"), deserialized.get("0"));
        assertEquals(original.get("2"), ((Number) deserialized.get("2")).longValue());
        assertArrayEquals((byte[]) original.get("1.0"), (byte[]) deserialized.get("1.0"));
    }

    @Test
    public void performanceTest() {
        final int WARMUP_ITERATIONS = 5000;
        final int BENCHMARK_ITERATIONS = 10000;

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
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, true, true);

        // Create 128KB payload
        byte[] packet = new byte[128 * 1024];
        for (int i = 0; i < packet.length; i++) {
            packet[i] = (byte) (i % 128);
        }

        Map<Object, Object> data = new HashMap<>();
        data.put(0, packet);

        Map<Object, Object> request = new HashMap<>();
        request.put(0, "FOO");
        request.put(1, data);

        BinaryDocument document = new BinaryDocument(request);

        DynamicByteBuffer b = new DynamicByteBuffer(256 * 1024, true);

        // Warm-up phase: allow JIT to optimize hot paths
        System.out.println("Warming up for " + WARMUP_ITERATIONS + " iterations...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            writer.serialize(b, document);
            BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
            reader.deserialize(b.getBuffer(), deserialized);
        }
        System.out.println("Warm-up complete. Running benchmark...");

        // Benchmark phase
        List<Long> serializationTime = new ArrayList<>();
        List<Long> deserializationTime = new ArrayList<>();
        BinaryDocument deserialized = null;

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long delta = System.nanoTime();
            writer.serialize(b, document);
            serializationTime.add(System.nanoTime() - delta);

            delta = System.nanoTime();
            deserialized = new BinaryDocument(new HashMap<>());
            reader.deserialize(b.getBuffer(), deserialized);
            deserializationTime.add(System.nanoTime() - delta);

            // Verify data integrity
            byte[] deserializedPacket = new  byte[128 * 1024];
            ByteBuffer bb = ((ByteBuffer) deserialized.get("1.0"));
            for (int i1 = 0; i1 < 128*1024; i1++) {
                deserializedPacket[i1] = bb.get(i1);
            }

            for (int j = 0; j < packet.length; j++) {
                assertEquals(packet[j], deserializedPacket[j]);
            }
        }

        List<Long> sortedSerialization = serializationTime.stream().sorted().toList();
        List<Long> sortedDeserialization = deserializationTime.stream().sorted().toList();

        long serMedian = sortedSerialization.get(sortedSerialization.size() / 2);
        long deserMedian = sortedDeserialization.get(sortedDeserialization.size() / 2);
        long serP99 = sortedSerialization.get((int) (sortedSerialization.size() * 0.99));
        long deserP99 = sortedDeserialization.get((int) (sortedDeserialization.size() * 0.99));

        System.out.println("=== MessagePack Performance (128KB payload) ===");
        System.out.println("Serialization median time: %.3fus".formatted(serMedian / 1000.0));
        System.out.println("Serialization p99 time: %.3fus".formatted(serP99 / 1000.0));
        System.out.println("Deserialization median time: %.3fus".formatted(deserMedian / 1000.0));
        System.out.println("Deserialization p99 time: %.3fus".formatted(deserP99 / 1000.0));
    }

    @Test
    public void performanceTestManyFields() {
        final int WARMUP_ITERATIONS = 5000;
        final int BENCHMARK_ITERATIONS = 10000;

        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(100)
                .setMaxPoolSize(2000)
                .setBlocking(true)
                .setOutOfPoolTimeout(1000)
                .build();

        Pool<ReaderContext> readerContextPool = poolFactory.getPool(ReaderContext::new);
        Pool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));
        Pool<WriterContext> writerContextPool = poolFactory.getPool(WriterContext::new);

        Pool<ArrayDeque<WriterContext>> writerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(16));
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, true, true);

        // Create 1000 nested objects
        Map<Object, Object> fields = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            Map<Integer, Object> nested = new HashMap<>();
            nested.put(0, i);
            nested.put(1, "item_" + i);
            nested.put(2, i % 2 == 0);
            nested.put(3, i * 1.5);
            fields.put(i, nested);
        }
        BinaryDocument document = new BinaryDocument(fields);

        DynamicByteBuffer b = new DynamicByteBuffer(512 * 1024, true);

        // Warm-up phase
        System.out.println("Warming up for " + WARMUP_ITERATIONS + " iterations...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            writer.serialize(b, document);
            reader.deserialize(b.getBuffer(), new BinaryDocument(new HashMap<>()));
        }
        System.out.println("Warm-up complete. Running benchmark...");

        // Benchmark phase
        List<Long> serializationTime = new ArrayList<>();
        List<Long> deserializationTime = new ArrayList<>();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long delta = System.nanoTime();
            writer.serialize(b, document);
            serializationTime.add(System.nanoTime() - delta);

            delta = System.nanoTime();
            BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
            reader.deserialize(b.getBuffer(), deserialized);
            deserializationTime.add(System.nanoTime() - delta);

            // Validate ALL deserialized data
            assertEquals(1000, deserialized.getDocumentMap().size(), "Document should have 1000 fields");
            for (int j = 0; j < 1000; j++) {
                @SuppressWarnings("unchecked")
                Map<Integer, Object> nested = (Map<Integer, Object>) deserialized.getDocumentMap().get(j);
                assertEquals(j, ((Number) nested.get(0)).intValue());
                assertEquals("item_" + j, nested.get(1));
                assertEquals(j % 2 == 0, nested.get(2));
                assertEquals(j * 1.5, ((Number) nested.get(3)).doubleValue(), 0.001);
            }
        }

        List<Long> sortedSerialization = serializationTime.stream().sorted().toList();
        List<Long> sortedDeserialization = deserializationTime.stream().sorted().toList();

        long serMedian = sortedSerialization.get(sortedSerialization.size() / 2);
        long deserMedian = sortedDeserialization.get(sortedDeserialization.size() / 2);
        long serP99 = sortedSerialization.get((int) (sortedSerialization.size() * 0.99));
        long deserP99 = sortedDeserialization.get((int) (sortedDeserialization.size() * 0.99));

        System.out.println("=== MessagePack Performance (1000 nested objects) ===");
        System.out.println("Serialization median time: %.3fus".formatted(serMedian / 1000.0));
        System.out.println("Serialization p99 time: %.3fus".formatted(serP99 / 1000.0));
        System.out.println("Deserialization median time: %.3fus".formatted(deserMedian / 1000.0));
        System.out.println("Deserialization p99 time: %.3fus".formatted(deserP99 / 1000.0));
    }
}
