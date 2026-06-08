package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.json.JsonParser;
import su.grinev.json.JsonWriter;
import su.grinev.json.Tokenizer;
import su.grinev.json.token.Token;
import su.grinev.pool.DisposablePool;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.PoolFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JsonMapperTests {

    @Test
    public void serializeAndDeserializeObjectTest() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(100)
                .setMaxPoolSize(1000)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        DisposablePool<DynamicByteBuffer> bufferPool = poolFactory.getDisposablePool(() -> new DynamicByteBuffer(8192, true));
        JsonWriter writer = new JsonWriter(bufferPool);
        JsonParser parser = new JsonParser();

        // Create test data
        Map<String, Object> data = new HashMap<>();
        data.put("name", "test");
        data.put("value", 42);
        data.put("active", true);
        data.put("score", 3.14);

        Map<String, Object> nested = new HashMap<>();
        nested.put("id", 1);
        nested.put("label", "nested");
        data.put("nested", nested);

        Document original = new Document(data);

        // Serialize
        DynamicByteBuffer buffer = writer.serialize(original);
        buffer.flip();

        // Get bytes and parse
        byte[] jsonBytes = new byte[buffer.getBuffer().remaining()];
        buffer.getBuffer().get(jsonBytes);
        buffer.dispose();

        List<Token> tokens = new Tokenizer(jsonBytes).tokenize();
        Document deserialized = parser.parse(tokens);

        // Verify
        assertEquals(original.getDocumentMap().get("name"), deserialized.getDocumentMap().get("name"));
        assertEquals(original.getDocumentMap().get("value"), deserialized.getDocumentMap().get("value"));
        assertEquals(original.getDocumentMap().get("active"), deserialized.getDocumentMap().get("active"));
        assertEquals(((Number) original.getDocumentMap().get("score")).doubleValue(),
                ((Number) deserialized.getDocumentMap().get("score")).doubleValue(), 0.001);

        @SuppressWarnings("unchecked")
        Map<String, Object> deserializedNested = (Map<String, Object>) deserialized.getDocumentMap().get("nested");
        assertEquals(nested.get("id"), deserializedNested.get("id"));
        assertEquals(nested.get("label"), deserializedNested.get("label"));
    }

    @Test
    public void performanceTest1kbStringPayload() {
        final int WARMUP_ITERATIONS = 5000;
        final int BENCHMARK_ITERATIONS = 10000;

        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(1000)
                .setBlocking(true)
                .setOutOfPoolTimeout(1000)
                .build();

        DisposablePool<DynamicByteBuffer> bufferPool = poolFactory.getDisposablePool(() -> new DynamicByteBuffer(256 * 1024, true));
        JsonWriter writer = new JsonWriter(bufferPool);
        JsonParser parser = new JsonParser();

        // Create 1KB string payload (JSON doesn't have binary, so use base64-like string)
        StringBuilder sb = new StringBuilder(1024);
        for (int i = 0; i < 1024; i++) {
            sb.append((char) ('A' + (i % 26)));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("packet", sb.toString());

        Map<String, Object> request = new HashMap<>();
        request.put("command", "FOO");
        request.put("data", data);

        Document document = new Document(request);

        // Warm-up phase
        System.out.println("Warming up for " + WARMUP_ITERATIONS + " iterations...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            DynamicByteBuffer b = writer.serialize(document);
            b.flip();
            byte[] jsonBytes = new byte[b.getBuffer().remaining()];
            b.getBuffer().get(jsonBytes);
            b.dispose();

            List<Token> tokens = new Tokenizer(jsonBytes).tokenize();
            parser.parse(tokens);
        }
        System.out.println("Warm-up complete. Running benchmark...");

        // Benchmark phase
        List<Long> serializationTime = new ArrayList<>();
        List<Long> deserializationTime = new ArrayList<>();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long delta = System.nanoTime();
            DynamicByteBuffer b = writer.serialize(document);
            serializationTime.add(System.nanoTime() - delta);
            b.flip();

            byte[] jsonBytes = new byte[b.getBuffer().remaining()];
            b.getBuffer().get(jsonBytes);
            b.dispose();

            delta = System.nanoTime();
            List<Token> tokens = new Tokenizer(jsonBytes).tokenize();
            Document deserialized = parser.parse(tokens);
            deserializationTime.add(System.nanoTime() - delta);

            // Verify
            assertEquals(document.getDocumentMap().get("command"), deserialized.getDocumentMap().get("command"));
        }

        List<Long> sortedSerialization = serializationTime.stream().sorted().toList();
        List<Long> sortedDeserialization = deserializationTime.stream().sorted().toList();

        long serMedian = sortedSerialization.get(sortedSerialization.size() / 2);
        long deserMedian = sortedDeserialization.get(sortedDeserialization.size() / 2);
        long serP99 = sortedSerialization.get((int) (sortedSerialization.size() * 0.99));
        long deserP99 = sortedDeserialization.get((int) (sortedDeserialization.size() * 0.99));

        System.out.println("=== JSON Performance (1KB payload) ===");
        System.out.println("Serialization median time: %.3fus".formatted(serMedian / 1000.0));
        System.out.println("Serialization p99 time: %.3fus".formatted(serP99 / 1000.0));
        System.out.println("Deserialization median time: %.3fus".formatted(deserMedian / 1000.0));
        System.out.println("Deserialization p99 time: %.3fus".formatted(deserP99 / 1000.0));
    }

    @Test
    public void verifySerializationCorrectness() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(1000)
                .setBlocking(true)
                .setOutOfPoolTimeout(1000)
                .build();

        DisposablePool<DynamicByteBuffer> bufferPool = poolFactory.getDisposablePool(() -> new DynamicByteBuffer(512 * 1024, true));
        JsonWriter writer = new JsonWriter(bufferPool);
        JsonParser parser = new JsonParser();

        // Create 1000 nested objects
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> nested = new HashMap<>();
            nested.put("id", i);
            nested.put("name", "item_" + i);
            nested.put("active", i % 2 == 0);
            nested.put("score", i * 1.5);
            fields.put("field_" + i, nested);
        }
        Document document = new Document(fields);

        // Serialize
        DynamicByteBuffer b = writer.serialize(document);
        b.flip();
        byte[] jsonBytes = new byte[b.getBuffer().remaining()];
        b.getBuffer().get(jsonBytes);
        b.dispose();

        String jsonString = new String(jsonBytes, StandardCharsets.UTF_8);
        System.out.println("=== JSON Serialization Verification ===");
        System.out.println("JSON size: " + jsonBytes.length + " bytes");
        System.out.println("First 500 chars: " + jsonString.substring(0, Math.min(500, jsonString.length())));
        System.out.println("Last 200 chars: " + jsonString.substring(Math.max(0, jsonString.length() - 200)));

        // Verify JSON structure
        assertTrue(jsonString.startsWith("{"), "JSON should start with {");
        assertTrue(jsonString.endsWith("}"), "JSON should end with }");

        // Count occurrences of "field_" to verify all fields are present
        int fieldCount = 0;
        int idx = 0;
        while ((idx = jsonString.indexOf("\"field_", idx)) != -1) {
            fieldCount++;
            idx++;
        }
        System.out.println("Field count in JSON: " + fieldCount);
        assertEquals(1000, fieldCount, "Should have 1000 fields");

        // Deserialize and verify
        List<Token> tokens = new Tokenizer(jsonBytes).tokenize();
        System.out.println("Token count: " + tokens.size());

        Document deserialized = parser.parse(tokens);
        System.out.println("Deserialized document size: " + deserialized.getDocumentMap().size());
        assertEquals(1000, deserialized.getDocumentMap().size(), "Deserialized should have 1000 fields");

        // Verify ALL fields match original
        for (int i = 0; i < 1000; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> originalNested = (Map<String, Object>) fields.get("field_" + i);
            @SuppressWarnings("unchecked")
            Map<String, Object> deserializedNested = (Map<String, Object>) deserialized.getDocumentMap().get("field_" + i);

            assertNotNull(deserializedNested, "field_" + i + " should exist");
            assertEquals(4, deserializedNested.size(), "field_" + i + " should have 4 properties");
            assertEquals(originalNested.get("id"), ((Number) deserializedNested.get("id")).intValue(), "id mismatch for field_" + i);
            assertEquals(originalNested.get("name"), deserializedNested.get("name"), "name mismatch for field_" + i);
            assertEquals(originalNested.get("active"), deserializedNested.get("active"), "active mismatch for field_" + i);
            assertEquals((Double) originalNested.get("score"), ((Number) deserializedNested.get("score")).doubleValue(), 0.001, "score mismatch for field_" + i);
        }
        System.out.println("All 1000 fields verified successfully!");
    }

    @Test
    public void performanceTestManyFields() {
        final int WARMUP_ITERATIONS = 5000;
        final int BENCHMARK_ITERATIONS = 10000;

        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(1000)
                .setBlocking(true)
                .setOutOfPoolTimeout(1000)
                .build();

        DisposablePool<DynamicByteBuffer> bufferPool = poolFactory.getDisposablePool(() -> new DynamicByteBuffer(512 * 1024, true));
        JsonWriter writer = new JsonWriter(bufferPool);
        JsonParser parser = new JsonParser();

        // Create 1000 nested objects
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> nested = new HashMap<>();
            nested.put("id", i);
            nested.put("name", "item_" + i);
            nested.put("active", i % 2 == 0);
            nested.put("score", i * 1.5);
            fields.put("field_" + i, nested);
        }
        Document document = new Document(fields);

        // Warm-up phase
        System.out.println("Warming up for " + WARMUP_ITERATIONS + " iterations...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            DynamicByteBuffer b = writer.serialize(document);
            b.flip();
            byte[] jsonBytes = new byte[b.getBuffer().remaining()];
            b.getBuffer().get(jsonBytes);
            b.dispose();

            List<Token> tokens = new Tokenizer(jsonBytes).tokenize();
            parser.parse(tokens);
        }
        System.out.println("Warm-up complete. Running benchmark...");

        // Benchmark phase
        List<Long> serializationTime = new ArrayList<>();
        List<Long> deserializationTime = new ArrayList<>();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long delta = System.nanoTime();
            DynamicByteBuffer b = writer.serialize(document);
            serializationTime.add(System.nanoTime() - delta);
            b.flip();

            byte[] jsonBytes = new byte[b.getBuffer().remaining()];
            b.getBuffer().get(jsonBytes);
            b.dispose();

            delta = System.nanoTime();
            List<Token> tokens = new Tokenizer(jsonBytes).tokenize();
            Document deserialized = parser.parse(tokens);
            deserializationTime.add(System.nanoTime() - delta);

            // Validate ALL deserialized data
            assertEquals(1000, deserialized.getDocumentMap().size(), "Document should have 1000 fields");
            for (int j = 0; j < 1000; j++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) deserialized.getDocumentMap().get("field_" + j);
                assertEquals(j, ((Number) nested.get("id")).intValue());
                assertEquals("item_" + j, nested.get("name"));
                assertEquals(j % 2 == 0, nested.get("active"));
                assertEquals(j * 1.5, ((Number) nested.get("score")).doubleValue(), 0.001);
            }
        }

        List<Long> sortedSerialization = serializationTime.stream().sorted().toList();
        List<Long> sortedDeserialization = deserializationTime.stream().sorted().toList();

        long serMedian = sortedSerialization.get(sortedSerialization.size() / 2);
        long deserMedian = sortedDeserialization.get(sortedDeserialization.size() / 2);
        long serP99 = sortedSerialization.get((int) (sortedSerialization.size() * 0.99));
        long deserP99 = sortedDeserialization.get((int) (sortedDeserialization.size() * 0.99));

        System.out.println("=== JSON Performance (1000 nested objects) ===");
        System.out.println("Serialization median time: %.3fus".formatted(serMedian / 1000.0));
        System.out.println("Serialization p99 time: %.3fus".formatted(serP99 / 1000.0));
        System.out.println("Deserialization median time: %.3fus".formatted(deserMedian / 1000.0));
        System.out.println("Deserialization p99 time: %.3fus".formatted(deserP99 / 1000.0));
    }
}
