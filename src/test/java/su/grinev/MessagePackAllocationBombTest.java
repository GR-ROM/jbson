package su.grinev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.grinev.messagepack.MessagePackException;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.pool.FastPool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that MessagePackReader rejects malicious payloads with oversized
 * length headers (allocation bombs) by throwing MessagePackException instead
 * of OutOfMemoryError or other uncontrolled exceptions.
 */
public class MessagePackAllocationBombTest {

    private MessagePackReader reader;

    @BeforeEach
    void setUp() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(1)
                .setMaxPoolSize(10)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();
        FastPool<ReaderContext> readerContextPool = poolFactory.getPool(ReaderContext::new);
        FastPool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));
        reader = new MessagePackReader(readerContextPool, stackPool, false, false);
        reader.setReadLengthHeader(false);
    }

    // --- Allocation bombs: must throw MessagePackException, not OOM ---

    @Test
    void array32AllocationBomb_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);       // fixmap, 1 entry
        payload.put((byte) 0x00);       // key: 0
        payload.put((byte) 0xDD);       // ARRAY32
        payload.putInt(Integer.MAX_VALUE);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds maximum"), ex.getMessage());
    }

    @Test
    void map32AllocationBomb_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xDF);       // MAP32
        payload.putInt(0x10000000);      // 268M
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds maximum"), ex.getMessage());
    }

    @Test
    void str32AllocationBomb_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xDB);       // STR32
        payload.putInt(Integer.MAX_VALUE);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds remaining buffer"), ex.getMessage());
    }

    @Test
    void bin32AllocationBomb_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xC6);       // BIN32
        payload.putInt(Integer.MAX_VALUE);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds remaining buffer"), ex.getMessage());
    }

    @Test
    void ext32AllocationBomb_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xC9);       // EXT32
        payload.putInt(Integer.MAX_VALUE);
        payload.put((byte) 0x01);       // ext type
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds remaining buffer"), ex.getMessage());
    }

    @Test
    void rootMap32AllocationBomb_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0xDF);       // MAP32 as root
        payload.putInt(0x10000000);      // 268M
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds maximum"), ex.getMessage());
    }

    // --- Negative sizes: must throw MessagePackException, not IllegalArgumentException ---

    @Test
    void array32NegativeSize_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xDD);       // ARRAY32
        payload.putInt(0xFFFFFFFF);      // -1 signed
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("Negative"), ex.getMessage());
    }

    @Test
    void str32NegativeLength_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xDB);       // STR32
        payload.putInt(Integer.MIN_VALUE);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("Negative"), ex.getMessage());
    }

    @Test
    void bin32NegativeLength_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xC6);       // BIN32
        payload.putInt(-100);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("Negative"), ex.getMessage());
    }

    @Test
    void map32NegativeSize_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xDF);       // MAP32
        payload.putInt(-1);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("Negative"), ex.getMessage());
    }

    @Test
    void ext32NegativeLength_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xC9);       // EXT32
        payload.putInt(-42);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("Negative"), ex.getMessage());
    }

    // --- Moderate sizes: still over buffer remaining ---

    @Test
    void str16ExceedsBuffer_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xDA);       // STR16
        payload.putShort((short) 10000); // 10KB string, buffer has ~58 bytes
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds remaining buffer"), ex.getMessage());
    }

    @Test
    void bin16ExceedsBuffer_rejected() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xC5);       // BIN16
        payload.putShort((short) 5000);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                reader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds remaining buffer"), ex.getMessage());
    }

    // --- Custom maxCollectionSize ---

    @Test
    void customMaxCollectionSize() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(1)
                .setMaxPoolSize(10)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();
        FastPool<ReaderContext> readerContextPool = poolFactory.getPool(ReaderContext::new);
        FastPool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));

        // maxCollectionSize = 16
        MessagePackReader strictReader = new MessagePackReader(
                readerContextPool, stackPool, false, false, 16);
        strictReader.setReadLengthHeader(false);

        // Array of 100 elements — exceeds limit of 16
        ByteBuffer payload = ByteBuffer.allocate(256);
        payload.put((byte) 0x81);
        payload.put((byte) 0x00);
        payload.put((byte) 0xDC);       // ARRAY16
        payload.putShort((short) 100);
        payload.flip();

        MessagePackException ex = assertThrows(MessagePackException.class, () ->
                strictReader.deserialize(payload, new BinaryDocument(new HashMap<>())));
        assertTrue(ex.getMessage().contains("exceeds maximum"), ex.getMessage());
    }

    // --- Legitimate payloads still work ---

    @Test
    void legitimateSmallPayload_works() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x82);               // fixmap, 2 entries
        payload.put((byte) 0x00);               // key: 0
        payload.put((byte) 0x2A);               // value: 42
        payload.put((byte) 0x01);               // key: 1
        payload.put((byte) 0xA4);               // fixstr, len 4
        payload.put("test".getBytes());
        payload.flip();

        BinaryDocument doc = new BinaryDocument(new HashMap<>());
        assertDoesNotThrow(() -> reader.deserialize(payload, doc));
        assertEquals(42, doc.get("0"));
        assertEquals("test", doc.get("1"));
    }

    @Test
    void legitimateArrayPayload_works() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        payload.put((byte) 0x81);               // fixmap, 1 entry
        payload.put((byte) 0x00);               // key: 0
        payload.put((byte) 0x93);               // fixarray, 3 elements
        payload.put((byte) 0x01);               // 1
        payload.put((byte) 0x02);               // 2
        payload.put((byte) 0x03);               // 3
        payload.flip();

        BinaryDocument doc = new BinaryDocument(new HashMap<>());
        assertDoesNotThrow(() -> reader.deserialize(payload, doc));
    }
}
