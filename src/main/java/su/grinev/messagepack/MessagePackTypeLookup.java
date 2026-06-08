package su.grinev.messagepack;

public final class MessagePackTypeLookup {

    private static final MessagePackType[] TYPES = new MessagePackType[256];

    static {
        // Positive fixint: 0x00-0x7F
        for (int i = 0x00; i <= 0x7F; i++) TYPES[i] = MessagePackType.POSITIVE_FIXINT;

        // Fixmap: 0x80-0x8F
        for (int i = 0x80; i <= 0x8F; i++) TYPES[i] = MessagePackType.FIXMAP;

        // Fixarray: 0x90-0x9F
        for (int i = 0x90; i <= 0x9F; i++) TYPES[i] = MessagePackType.FIXARRAY;

        // Fixstr: 0xA0-0xBF
        for (int i = 0xA0; i <= 0xBF; i++) TYPES[i] = MessagePackType.FIXSTR;

        // Negative fixint: 0xE0-0xFF
        for (int i = 0xE0; i <= 0xFF; i++) TYPES[i] = MessagePackType.NEGATIVE_FIXINT;

        // Fixed codes
        TYPES[0xC0] = MessagePackType.NIL;
        TYPES[0xC1] = MessagePackType.NEVER_USED;
        TYPES[0xC2] = MessagePackType.FALSE;
        TYPES[0xC3] = MessagePackType.TRUE;
        TYPES[0xC4] = MessagePackType.BIN8;
        TYPES[0xC5] = MessagePackType.BIN16;
        TYPES[0xC6] = MessagePackType.BIN32;
        TYPES[0xC7] = MessagePackType.EXT8;
        TYPES[0xC8] = MessagePackType.EXT16;
        TYPES[0xC9] = MessagePackType.EXT32;
        TYPES[0xCA] = MessagePackType.FLOAT32;
        TYPES[0xCB] = MessagePackType.FLOAT64;
        TYPES[0xCC] = MessagePackType.UINT8;
        TYPES[0xCD] = MessagePackType.UINT16;
        TYPES[0xCE] = MessagePackType.UINT32;
        TYPES[0xCF] = MessagePackType.UINT64;
        TYPES[0xD0] = MessagePackType.INT8;
        TYPES[0xD1] = MessagePackType.INT16;
        TYPES[0xD2] = MessagePackType.INT32;
        TYPES[0xD3] = MessagePackType.INT64;
        TYPES[0xD4] = MessagePackType.FIXEXT1;
        TYPES[0xD5] = MessagePackType.FIXEXT2;
        TYPES[0xD6] = MessagePackType.FIXEXT4;
        TYPES[0xD7] = MessagePackType.FIXEXT8;
        TYPES[0xD8] = MessagePackType.FIXEXT16;
        TYPES[0xD9] = MessagePackType.STR8;
        TYPES[0xDA] = MessagePackType.STR16;
        TYPES[0xDB] = MessagePackType.STR32;
        TYPES[0xDC] = MessagePackType.ARRAY16;
        TYPES[0xDD] = MessagePackType.ARRAY32;
        TYPES[0xDE] = MessagePackType.MAP16;
        TYPES[0xDF] = MessagePackType.MAP32;
    }

    private MessagePackTypeLookup() {}

    public static MessagePackType get(byte b) {
        return TYPES[b & 0xFF];
    }
}
