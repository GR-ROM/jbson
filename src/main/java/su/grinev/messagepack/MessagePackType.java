package su.grinev.messagepack;

public enum MessagePackType {
    NIL((byte) 0xC0),
    FALSE((byte) 0xC2),
    TRUE((byte) 0xC3),
    POSITIVE_FIXINT((byte) 0x00),
    NEGATIVE_FIXINT((byte) 0xE0),
    UINT8((byte) 0xCC),
    UINT16((byte) 0xCD),
    UINT32((byte) 0xCE),
    UINT64((byte) 0xCF),
    INT8((byte) 0xD0),
    INT16((byte) 0xD1),
    INT32((byte) 0xD2),
    INT64((byte) 0xD3),
    FLOAT32((byte) 0xCA),
    FLOAT64((byte) 0xCB),
    FIXSTR((byte) 0xA0),
    STR8((byte) 0xD9),
    STR16((byte) 0xDA),
    STR32((byte) 0xDB),
    BIN8((byte) 0xC4),
    BIN16((byte) 0xC5),
    BIN32((byte) 0xC6),
    FIXARRAY((byte) 0x90),
    ARRAY16((byte) 0xDC),
    ARRAY32((byte) 0xDD),
    FIXMAP((byte) 0x80),
    MAP16((byte) 0xDE),
    MAP32((byte) 0xDF),
    FIXEXT1((byte) 0xD4),
    FIXEXT2((byte) 0xD5),
    FIXEXT4((byte) 0xD6),
    FIXEXT8((byte) 0xD7),
    FIXEXT16((byte) 0xD8),
    EXT8((byte) 0xC7),
    EXT16((byte) 0xC8),
    EXT32((byte) 0xC9),
    NEVER_USED((byte) 0xC1);

    private final byte code;

    MessagePackType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }
}
