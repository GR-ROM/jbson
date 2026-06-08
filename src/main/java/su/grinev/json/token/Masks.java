package su.grinev.json.token;

public class Masks {

    public static long maskCurlyOpen(long word) {
        long cmp = word ^ 0x7B7B7B7B7B7B7B7BL;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    public static long maskCurlyClose(long word) {
        long cmp = word ^ 0x7D7D7D7D7D7D7D7DL;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    public static long maskSquareOpen(long word) {
        long cmp = word ^ 0x5B5B5B5B5B5B5B5BL;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    public static long maskSquareClose(long word) {
        long cmp = word ^ 0x5D5D5D5D5D5D5D5DL;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    public static long maskColon(long word) {
        long cmp = word ^ 0x3A3A3A3A3A3A3A3AL;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    public static long maskComma(long word) {
        long cmp = word ^ 0x2C2C2C2C2C2C2C2CL;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    // Литералы true, false, null
    public static long maskLiteralT(long word) {
        long cmp = word ^ 0x7474747474747474L;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    public static long maskLiteralF(long word) {
        long cmp = word ^ 0x6666666666666666L;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    public static long maskLiteralN(long word) {
        long cmp = word ^ 0x6E6E6E6E6E6E6E6EL;
        return ((cmp - 0x0101010101010101L) & ~cmp & 0x8080808080808080L);
    }

    private static int getPosition(long masked) {
        for (int i = 0; i < 8; i++) {
            if ((masked & 0x8000000000000000L) != 0) {
                return i;
            }
            masked <<= 8;
        }
        return 0;
    }
}

