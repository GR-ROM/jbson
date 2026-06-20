package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.messagepack.MessagePackException;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.ReaderContext;
import su.grinev.pool.FastPool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that ReaderContext objects are properly released back to the pool
 * when deserialization fails mid-parse due to malformed input.
 *
 * Previously (J6 bug), the finally block did stack.clear() without releasing
 * each ReaderContext, causing permanent pool exhaustion after repeated failures.
 */
public class MessagePackPoolLeakTest {

    /**
     * Repeated malformed packets with nested structures must NOT exhaust the pool.
     * Pool limit=5, 50 failures — pool should survive all of them.
     */
    @Test
    void repeatedMalformedPackets_poolRemainsHealthy() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(1)
                .setMaxPoolSize(5)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        FastPool<ReaderContext> contextPool = poolFactory.getPool(ReaderContext::new);
        FastPool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));

        MessagePackReader reader = new MessagePackReader(contextPool, stackPool, false, false);
        reader.setReadLengthHeader(false);

        byte[] malformed = craftNestedMapThenTruncate();

        for (int i = 0; i < 50; i++) {
            ByteBuffer buf = ByteBuffer.wrap(malformed);
            try {
                reader.deserialize(buf, new BinaryDocument(new HashMap<>()));
                fail("Should have thrown on malformed data");
            } catch (MessagePackException | java.nio.BufferUnderflowException e) {
                // Expected
            }
            // No IllegalStateException("Pool overflow") should occur
        }

        // Valid payload must still work after 50 failures
        ByteBuffer valid = craftValidPayload();
        BinaryDocument doc = new BinaryDocument(new HashMap<>());
        assertDoesNotThrow(() -> reader.deserialize(valid, doc));
        assertEquals(42, doc.get("0"));
    }

    /**
     * Deeply nested malformed packets (3 levels = 3 contexts per failure)
     * must also not exhaust the pool.
     */
    @Test
    void deeplyNestedMalformed_poolRemainsHealthy() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(1)
                .setMaxPoolSize(5)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        FastPool<ReaderContext> contextPool = poolFactory.getPool(ReaderContext::new);
        FastPool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));

        MessagePackReader reader = new MessagePackReader(contextPool, stackPool, false, false);
        reader.setReadLengthHeader(false);

        byte[] deepMalformed = craftDeeplyNestedThenTruncate();

        for (int i = 0; i < 50; i++) {
            ByteBuffer buf = ByteBuffer.wrap(deepMalformed);
            try {
                reader.deserialize(buf, new BinaryDocument(new HashMap<>()));
            } catch (MessagePackException | java.nio.BufferUnderflowException e) {
                // Expected
            }
        }

        ByteBuffer valid = craftValidPayload();
        BinaryDocument doc = new BinaryDocument(new HashMap<>());
        assertDoesNotThrow(() -> reader.deserialize(valid, doc));
        assertEquals(42, doc.get("0"));
    }

    /**
     * Mix of valid and malformed packets — pool must stay healthy throughout.
     */
    @Test
    void mixedValidAndMalformed_poolRemainsHealthy() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(1)
                .setMaxPoolSize(5)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        FastPool<ReaderContext> contextPool = poolFactory.getPool(ReaderContext::new);
        FastPool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));

        MessagePackReader reader = new MessagePackReader(contextPool, stackPool, false, false);
        reader.setReadLengthHeader(false);

        for (int i = 0; i < 100; i++) {
            if (i % 3 == 0) {
                // Valid packet
                ByteBuffer valid = craftValidPayload();
                BinaryDocument doc = new BinaryDocument(new HashMap<>());
                assertDoesNotThrow(() -> reader.deserialize(valid, doc));
                assertEquals(42, doc.get("0"));
            } else {
                // Malformed packet
                ByteBuffer buf = ByteBuffer.wrap(craftNestedMapThenTruncate());
                try {
                    reader.deserialize(buf, new BinaryDocument(new HashMap<>()));
                } catch (MessagePackException | java.nio.BufferUnderflowException e) {
                    // Expected
                }
            }
        }
    }

    // --- Payload helpers ---

    /** { 0: { <truncated — 2 entries declared but 0 provided> } } — leaks 2 contexts */
    private byte[] craftNestedMapThenTruncate() {
        return new byte[]{
                (byte) 0x81,  // fixmap, 1 entry (root)
                0x00,         // key: 0
                (byte) 0x82   // fixmap, 2 entries (nested) — truncated!
        };
    }

    /** { 0: { 0: { 0: { <truncated> } } } } — leaks 4 contexts */
    private byte[] craftDeeplyNestedThenTruncate() {
        return new byte[]{
                (byte) 0x81,  // root: fixmap, 1 entry
                0x00,         // key: 0
                (byte) 0x81,  // level 1: fixmap, 1 entry
                0x00,         // key: 0
                (byte) 0x81,  // level 2: fixmap, 1 entry
                0x00,         // key: 0
                (byte) 0x82   // level 3: fixmap, 2 entries — truncated!
        };
    }

    private ByteBuffer craftValidPayload() {
        return ByteBuffer.wrap(new byte[]{
                (byte) 0x81,  // fixmap, 1 entry
                0x00,         // key: 0
                0x2A          // value: 42
        });
    }
}
