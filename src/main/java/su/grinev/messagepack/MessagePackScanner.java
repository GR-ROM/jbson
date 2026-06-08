package su.grinev.messagepack;

import java.nio.ByteBuffer;

/**
 * Zero-allocation MessagePack scanner for fast-path extraction.
 * All methods operate on raw bytes without creating Java objects.
 * <p>
 * Designed for hot-path protocols where the message structure is known
 * and only specific fields need to be extracted.
 */
public final class MessagePackScanner {

    private MessagePackScanner() {}

    /**
     * Read a map header and return the number of entries.
     * Supports fixmap, map16, map32.
     */
    public static int readMapHeader(ByteBuffer buffer) {
        int b = buffer.get() & 0xFF;
        if (b >= 0x80 && b <= 0x8F) return b & 0x0F;
        if (b == 0xDE) return buffer.getShort() & 0xFFFF;
        if (b == 0xDF) return buffer.getInt();
        throw new MessagePackException("Expected map header, got 0x" + Integer.toHexString(b));
    }

    /**
     * Read a MessagePack integer as primitive int. No boxing.
     * Supports fixint (positive/negative), int8/16/32, uint8/16.
     */
    public static int readInt(ByteBuffer buffer) {
        byte b = buffer.get();
        if ((b & 0x80) == 0) return b;           // positive fixint 0x00-0x7F
        int unsigned = b & 0xFF;
        if (unsigned >= 0xE0) return b;           // negative fixint 0xE0-0xFF
        return switch (unsigned) {
            case 0xCC -> buffer.get() & 0xFF;              // uint8
            case 0xCD -> buffer.getShort() & 0xFFFF;       // uint16
            case 0xCE -> buffer.getInt();                   // uint32 (may overflow int)
            case 0xD0 -> buffer.get();                      // int8
            case 0xD1 -> buffer.getShort();                 // int16
            case 0xD2 -> buffer.getInt();                   // int32
            default -> throw new MessagePackException("Expected integer, got 0x" + Integer.toHexString(unsigned));
        };
    }

    /**
     * Check if the next value is a string matching the given UTF-8 bytes.
     * Always advances the buffer past the string value.
     * Returns true if the string matches, false otherwise.
     * Zero allocation.
     */
    public static boolean matchString(ByteBuffer buffer, byte[] expectedUtf8) {
        int b = buffer.get() & 0xFF;
        int len;
        if (b >= 0xA0 && b <= 0xBF) {
            len = b & 0x1F;  // fixstr
        } else if (b == 0xD9) {
            len = buffer.get() & 0xFF;  // str8
        } else if (b == 0xDA) {
            len = buffer.getShort() & 0xFFFF;  // str16
        } else if (b == 0xDB) {
            len = buffer.getInt();  // str32
        } else {
            throw new MessagePackException("Expected string, got 0x" + Integer.toHexString(b));
        }

        int pos = buffer.position();
        buffer.position(pos + len);

        if (len != expectedUtf8.length) return false;
        for (int i = 0; i < len; i++) {
            if (buffer.get(pos + i) != expectedUtf8[i]) return false;
        }
        return true;
    }

    /**
     * Read a binary data header and return the data length.
     * After this call, buffer.position() points to the start of the binary data.
     * Caller is responsible for advancing past the data.
     */
    public static int readBinaryHeader(ByteBuffer buffer) {
        int b = buffer.get() & 0xFF;
        return switch (b) {
            case 0xC4 -> buffer.get() & 0xFF;          // bin8
            case 0xC5 -> buffer.getShort() & 0xFFFF;   // bin16
            case 0xC6 -> buffer.getInt();                // bin32
            default -> throw new MessagePackException("Expected binary, got 0x" + Integer.toHexString(b));
        };
    }

    /**
     * Skip one MessagePack value of any type without allocating.
     * Handles all types including nested maps and arrays (iteratively, no recursion).
     */
    public static void skip(ByteBuffer buffer) {
        int remaining = 1;
        while (remaining > 0) {
            remaining--;
            int b = buffer.get() & 0xFF;

            if (b <= 0x7F) continue;                    // positive fixint
            if (b >= 0xE0) continue;                    // negative fixint

            if (b >= 0xA0 && b <= 0xBF) {              // fixstr
                buffer.position(buffer.position() + (b & 0x1F));
                continue;
            }
            if (b >= 0x80 && b <= 0x8F) {              // fixmap
                remaining += (b & 0x0F) * 2;
                continue;
            }
            if (b >= 0x90 && b <= 0x9F) {              // fixarray
                remaining += (b & 0x0F);
                continue;
            }

            switch (b) {
                case 0xC0, 0xC2, 0xC3 -> {}                                                    // nil, false, true
                case 0xCC, 0xD0 -> buffer.position(buffer.position() + 1);                      // uint8, int8
                case 0xCD, 0xD1 -> buffer.position(buffer.position() + 2);                      // uint16, int16
                case 0xCE, 0xD2, 0xCA -> buffer.position(buffer.position() + 4);                // uint32, int32, float32
                case 0xCF, 0xD3, 0xCB -> buffer.position(buffer.position() + 8);                // uint64, int64, float64
                case 0xD9 -> { int l = buffer.get() & 0xFF; buffer.position(buffer.position() + l); }            // str8
                case 0xDA -> { int l = buffer.getShort() & 0xFFFF; buffer.position(buffer.position() + l); }     // str16
                case 0xDB -> { int l = buffer.getInt(); buffer.position(buffer.position() + l); }                 // str32
                case 0xC4 -> { int l = buffer.get() & 0xFF; buffer.position(buffer.position() + l); }            // bin8
                case 0xC5 -> { int l = buffer.getShort() & 0xFFFF; buffer.position(buffer.position() + l); }     // bin16
                case 0xC6 -> { int l = buffer.getInt(); buffer.position(buffer.position() + l); }                 // bin32
                case 0xDC -> remaining += buffer.getShort() & 0xFFFF;                            // array16
                case 0xDD -> remaining += buffer.getInt();                                        // array32
                case 0xDE -> remaining += (buffer.getShort() & 0xFFFF) * 2;                      // map16
                case 0xDF -> remaining += buffer.getInt() * 2;                                    // map32
                case 0xD4 -> buffer.position(buffer.position() + 2);                              // fixext1
                case 0xD5 -> buffer.position(buffer.position() + 3);                              // fixext2
                case 0xD6 -> buffer.position(buffer.position() + 5);                              // fixext4
                case 0xD7 -> buffer.position(buffer.position() + 9);                              // fixext8
                case 0xD8 -> buffer.position(buffer.position() + 17);                             // fixext16
                case 0xC7 -> { int l = buffer.get() & 0xFF; buffer.position(buffer.position() + 1 + l); }        // ext8
                case 0xC8 -> { int l = buffer.getShort() & 0xFFFF; buffer.position(buffer.position() + 1 + l); } // ext16
                case 0xC9 -> { int l = buffer.getInt(); buffer.position(buffer.position() + 1 + l); }             // ext32
                default -> throw new MessagePackException("Unknown type 0x" + Integer.toHexString(b));
            }
        }
    }
}
