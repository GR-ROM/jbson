package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.bson.BsonObjectReader;
import su.grinev.exception.BsonException;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for BSON deserialization (J4, J5, J8, J9, J6-bson).
 *
 * BSON is little-endian. Document format:
 *   [4 bytes: total doc length (LE)] [elements...] [0x00 terminator]
 * Element format:
 *   [1 byte: type] [cstring: key] [value]
 * String value: [4 bytes: length including \0 (LE)] [bytes] [0x00]
 * Binary value: [4 bytes: length (LE)] [1 byte: subtype] [bytes]
 * Nested doc/array: same as document
 */
public class BsonSecurityTest {

    private BsonObjectReader createReader(int docSizeLimit) {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(1)
                .setMaxPoolSize(10)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();
        return new BsonObjectReader(poolFactory, docSizeLimit, true, null);
    }

    // =========================================================================
    // J4: readBinaryAsArray — no length validation
    // =========================================================================

    /**
     * J4: readBinaryAsArray with huge positive length → OOM via new byte[len].
     * Binary element: type=0x05, key="0\0", len=0x7FFFFFFF (LE), subtype=0x00
     */
    @Test
    void j4_binaryAsArray_hugeLength_rejected() {
        ByteBuffer buf = buildBsonDoc(b -> {
            b.put((byte) 0x05);              // type: binary
            b.put((byte) '0'); b.put((byte) 0); // key: "0\0"
            b.putInt(Integer.MAX_VALUE);      // binary length (LE)
            b.put((byte) 0x00);              // subtype: generic
            // no actual data follows — truncated
        });

        BsonObjectReader reader = createReader(64000);
        assertThrows(BsonException.class, () ->
                        reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "readBinaryAsArray with length=MAX_INT should throw BsonException, not OOM");
    }

    /**
     * J4: readBinaryAsArray with negative length → NegativeArraySizeException.
     */
    @Test
    void j4_binaryAsArray_negativeLength_rejected() {
        ByteBuffer buf = buildBsonDoc(b -> {
            b.put((byte) 0x05);              // type: binary
            b.put((byte) '0'); b.put((byte) 0); // key
            b.putInt(-1);                    // binary length: -1
            b.put((byte) 0x00);              // subtype
        });

        BsonObjectReader reader = createReader(64000);
        assertThrows(BsonException.class, () ->
                        reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "readBinaryAsArray with negative length should throw BsonException");
    }

    // =========================================================================
    // J5: readCString — scan without null terminator
    // =========================================================================

    /**
     * J5: CString with no null terminator → uncontrolled IndexOutOfBoundsException.
     * We craft a document where the key field has no \0 terminator.
     */
    @Test
    void j5_cstring_noTerminator_rejected() {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(12);                      // doc length
        buf.put((byte) 0x10);               // type: int32
        // Key without null terminator — fill remaining with non-zero
        buf.put((byte) 'A');
        buf.put((byte) 'B');
        buf.put((byte) 'C');
        buf.put((byte) 'D');
        buf.put((byte) 'E');
        // No 0x00 terminator before buffer ends
        buf.flip();

        BsonObjectReader reader = createReader(64000);
        assertThrows(BsonException.class, () ->
                        reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "CString without terminator should throw BsonException, not IndexOutOfBoundsException");
    }

    // =========================================================================
    // J8: Nested document with negative length passes parent length check
    // =========================================================================

    /**
     * J8: Nested doc length is negative → passes `len > ctx.getLength()` check
     * because negative < positive is always true.
     */
    @Test
    void j8_nestedDoc_negativeLength_rejected() {
        ByteBuffer buf = buildBsonDoc(b -> {
            b.put((byte) 0x03);              // type: embedded document
            b.put((byte) '0'); b.put((byte) 0); // key: "0\0"
            b.putInt(-1);                    // nested doc length: -1 (negative)
            // No actual nested doc data
        });

        BsonObjectReader reader = createReader(64000);
        assertThrows(BsonException.class, () ->
                        reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "Nested document with negative length should throw BsonException");
    }

    /**
     * J8: Same for array type (0x04).
     */
    @Test
    void j8_nestedArray_negativeLength_rejected() {
        ByteBuffer buf = buildBsonDoc(b -> {
            b.put((byte) 0x04);              // type: array
            b.put((byte) '0'); b.put((byte) 0); // key
            b.putInt(-100);                  // array length: -100
        });

        BsonObjectReader reader = createReader(64000);
        assertThrows(BsonException.class, () ->
                        reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "Nested array with negative length should throw BsonException");
    }

    /**
     * J8: Nested doc length exceeds buffer remaining — should be caught.
     */
    @Test
    void j8_nestedDoc_lengthExceedsBuffer_rejected() {
        ByteBuffer buf = buildBsonDoc(b -> {
            b.put((byte) 0x03);              // type: embedded document
            b.put((byte) '0'); b.put((byte) 0); // key
            b.putInt(999999);                // nested doc length: way beyond buffer
        });

        BsonObjectReader reader = createReader(999999);  // allow big docs
        assertThrows(Exception.class, () ->
                reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "Nested document length exceeding buffer should fail");
    }

    // =========================================================================
    // J9: readString integer overflow (getInt() - 1)
    // =========================================================================

    /**
     * J9: String with length field = 0 → getInt()-1 = -1 → IndexOutOfBoundsException.
     */
    @Test
    void j9_readString_zeroLength_rejected() {
        ByteBuffer buf = buildBsonDoc(b -> {
            b.put((byte) 0x02);              // type: string
            b.put((byte) '0'); b.put((byte) 0); // key
            b.putInt(0);                     // string length: 0 → len-1 = -1
        });

        BsonObjectReader reader = createReader(64000);
        assertThrows(BsonException.class, () ->
                        reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "String with length=0 should throw BsonException (len-1 = -1)");
    }

    /**
     * J9: String with length field = Integer.MIN_VALUE → overflow:
     * MIN_VALUE - 1 = MAX_VALUE → 2GB allocation.
     */
    @Test
    void j9_readString_intOverflow_rejected() {
        ByteBuffer buf = buildBsonDoc(b -> {
            b.put((byte) 0x02);              // type: string
            b.put((byte) '0'); b.put((byte) 0); // key
            b.putInt(Integer.MIN_VALUE);     // MIN_VALUE - 1 = MAX_VALUE → 2GB alloc
        });

        BsonObjectReader reader = createReader(64000);
        assertThrows(BsonException.class, () ->
                        reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "String length overflow should throw BsonException, not OOM");
    }

    /**
     * J9: String with huge positive length → OOM via new byte[len].
     */
    @Test
    void j9_readString_hugeLength_rejected() {
        ByteBuffer buf = buildBsonDoc(b -> {
            b.put((byte) 0x02);              // type: string
            b.put((byte) '0'); b.put((byte) 0); // key
            b.putInt(Integer.MAX_VALUE);     // len-1 = MAX_VALUE-1 → ~2GB alloc
        });

        BsonObjectReader reader = createReader(64000);
        assertThrows(BsonException.class, () ->
                        reader.deserialize(buf, new BinaryDocument(new HashMap<>())),
                "String with huge length should throw BsonException, not OOM");
    }

    // =========================================================================
    // J6-bson: Pool leak in BsonObjectReader.deserialize() finally block
    // =========================================================================

    /**
     * Same as MessagePack J6: BsonObjectReader.finally does stack.clear()
     * without releasing ReaderContext objects back to contextPool.
     */
    @Test
    void j6bson_repeatedMalformed_poolRemainsHealthy() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(1)
                .setMaxPoolSize(5)
                .setOutOfPoolTimeout(1000)
                .setBlocking(false)
                .build();

        BsonObjectReader reader = new BsonObjectReader(poolFactory, 64000, true, null);

        // Craft a BSON doc with nested doc that causes mid-parse failure
        // This will allocate ReaderContexts that must be released
        byte[] malformed = buildMalformedNestedBson();

        for (int i = 0; i < 50; i++) {
            ByteBuffer buf = ByteBuffer.wrap(malformed);
            try {
                reader.deserialize(buf, new BinaryDocument(new HashMap<>()));
            } catch (Exception e) {
                // Expected
            }
        }

        // After 50 failures, a valid BSON doc should still deserialize
        ByteBuffer valid = buildValidBsonDoc();
        BinaryDocument doc = new BinaryDocument(new HashMap<>());
        assertDoesNotThrow(() -> reader.deserialize(valid, doc),
                "Pool should still be healthy after 50 malformed packets");
    }

    // =========================================================================
    // Positive test: legitimate BSON doc works
    // =========================================================================

    @Test
    void legitimateBsonDoc_works() {
        ByteBuffer buf = buildValidBsonDoc();

        BsonObjectReader reader = createReader(64000);
        BinaryDocument doc = new BinaryDocument(new HashMap<>());
        assertDoesNotThrow(() -> reader.deserialize(buf, doc));
        assertEquals(42, doc.get("0"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Build a minimal BSON document with custom content via consumer. */
    private ByteBuffer buildBsonDoc(java.util.function.Consumer<ByteBuffer> elementWriter) {
        ByteBuffer buf = ByteBuffer.allocate(256);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0);  // placeholder for doc length
        elementWriter.accept(buf);
        buf.put((byte) 0x00);  // doc terminator

        // Patch document length
        int docLen = buf.position();
        buf.putInt(0, docLen);
        buf.flip();
        return buf;
    }

    /** Valid BSON doc: { "0": 42 } */
    private ByteBuffer buildValidBsonDoc() {
        return buildBsonDoc(b -> {
            b.put((byte) 0x10);              // type: int32
            b.put((byte) '0'); b.put((byte) 0); // key: "0\0"
            b.putInt(42);                    // value: 42
        });
    }

    /** Malformed: nested doc with truncated data, leaks ReaderContext */
    private byte[] buildMalformedNestedBson() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(30);                       // doc length (doesn't matter, we'll truncate)
        buf.put((byte) 0x03);                // type: embedded document
        buf.put((byte) '0'); buf.put((byte) 0); // key
        buf.putInt(20);                       // nested doc length
        // Nested doc content: type byte for an element, but truncated
        buf.put((byte) 0x10);                // type: int32
        buf.put((byte) '0'); buf.put((byte) 0); // key
        // Missing int32 value + doc terminator → BufferUnderflowException
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }
}
