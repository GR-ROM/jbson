package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.MessagePackWriter;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.WriterContext;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.FastPool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: the msgpack writer used to perform unguarded writes into the fixed-capacity
 * arena buffer — a message larger than the initial capacity threw BufferOverflowException
 * (the BSON/JSON writers guarded every write; msgpack guarded none). And ArenaByteBuffer's
 * growth path rebuilt the buffer hardcoded LITTLE_ENDIAN, silently flipping the byte order
 * of the (big-endian) msgpack stream mid-write. This locks both: a document far larger than
 * the initial buffer must serialize without error and round-trip byte-for-byte.
 */
public class MessagePackBufferGrowthTest {

    private final PoolFactory poolFactory = PoolFactory.Builder.builder()
            .setMinPoolSize(1)
            .setMaxPoolSize(10)
            .setOutOfPoolTimeout(1000)
            .setBlocking(false)
            .build();

    private final FastPool<ReaderContext> readerContextPool = poolFactory.getPool(ReaderContext::new);
    private final FastPool<ArrayDeque<ReaderContext>> readerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));
    private final FastPool<WriterContext> writerContextPool = poolFactory.getPool(WriterContext::new);
    private final FastPool<ArrayDeque<WriterContext>> writerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(16));

    private BinaryDocument roundTrip(Map<Object, Object> map, int initialCapacity) {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        DynamicByteBuffer buffer = new DynamicByteBuffer(initialCapacity, true);
        writer.serialize(buffer, new BinaryDocument(map));

        ByteBuffer wire = buffer.getBuffer();
        MessagePackReader reader = new MessagePackReader(readerContextPool, readerStackPool, false, false);
        BinaryDocument out = new BinaryDocument(new HashMap<>());
        reader.deserialize(wire, out);
        return out;
    }

    @Test
    void messageLargerThanInitialBufferGrowsInsteadOfOverflowing() {
        byte[] payload = new byte[5000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        Map<Object, Object> map = new HashMap<>();
        map.put(0, "0.1");
        map.put(1, 0x12345678);            // multi-byte int: flips if byte order is lost mid-growth
        map.put(2, payload);
        map.put(3, 0x1122334455667788L);

        // 64-byte initial buffer forces growth several times over.
        BinaryDocument doc = assertDoesNotThrow(() -> roundTrip(map, 64));

        assertEquals("0.1", doc.get("0"));
        // Compare as Number: the reader currently returns uint32-encoded values as Long
        // (known type instability — audit finding; the byte value itself is what matters here).
        assertEquals(0x12345678L, ((Number) doc.get("1")).longValue());
        Object bin = doc.get("2");
        byte[] binBytes = bin instanceof ByteBuffer bb ? toArray(bb) : (byte[]) bin;
        assertArrayEquals(payload, binBytes);
        assertEquals(0x1122334455667788L, ((Number) doc.get("3")).longValue());
    }

    @Test
    void longUniqueStringsSerializeWithoutRetentionAndRoundTrip() {
        // Long strings bypass the bounded per-thread UTF-8 cache (they used to be retained
        // forever); this pins that the bypass path still produces correct output.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("segment-").append(i).append('.');
        }
        String longToken = sb.toString();
        assertTrue(longToken.length() > 64);

        Map<Object, Object> map = new HashMap<>();
        map.put(0, longToken);
        map.put(1, "short");

        BinaryDocument doc = roundTrip(map, 64);
        assertEquals(longToken, doc.get("0"));
        assertEquals("short", doc.get("1"));
    }

    private static byte[] toArray(ByteBuffer bb) {
        byte[] out = new byte[bb.remaining()];
        bb.duplicate().get(out);
        return out;
    }
}
