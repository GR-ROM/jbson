package su.grinev.bson;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Utility {

    private static final int EXPONENT_BIAS = 6176;

    public static BigDecimal decodeDecimal128(long low, long high) {
        boolean isNegative = (high & 0x8000000000000000L) != 0;
        int combination = (int)((high >>> 61) & 0x7); // bits 126–124

        int biasedExponent;
        long significandHigh;

        if ((combination & 0x6) == 0x6) {
            // Large combination field
            biasedExponent = (int)((high >>> 47) & 0x3FFF); // bits 110–97
            significandHigh = high & 0x7FFFFFFFFFFL;        // 49 bits
        } else {
            // Small combination field
            biasedExponent = (int)((high >>> 49) & 0x3FFF); // bits 62–49
            significandHigh = high & 0x1FFFFFFFFFFFFL;      // 49 bits
        }

        int exponent = biasedExponent - EXPONENT_BIAS;
        BigInteger significand = BigInteger.valueOf(significandHigh).shiftLeft(64).or(BigInteger.valueOf(low));

        if (isNegative) {
            significand = significand.negate();
        }

        return new BigDecimal(significand, -exponent);
    }

    public static long[] encodeDecimal128(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        BigInteger coefficient = normalized.unscaledValue().abs();
        int exponent = -normalized.scale();

        int signBit = value.signum() < 0 ? 1 : 0;
        int biasedExponent = exponent + EXPONENT_BIAS;

        if (biasedExponent < 0 || biasedExponent > 0x3FFF) {
            throw new IllegalArgumentException("Exponent out of range for Decimal128: " + exponent);
        }

        BigInteger highBits = coefficient.shiftRight(64);
        BigInteger lowBits = coefficient.and(BigInteger.valueOf(0xFFFFFFFFFFFFFFFFL));

        long low = lowBits.longValue();
        long high = highBits.longValue();

        if ((high & 0x001F000000000000L) != 0) {
            high |= 0x6000000000000000L;
            high |= ((long) biasedExponent) << 47;
        } else {
            high |= ((long) biasedExponent) << 49;
        }

        if (signBit == 1) {
            high |= 0x8000000000000000L;
        }

        return new long[]{low, high};
    }

    public static int findNullByteSimdLong(ByteBuffer buffer) {
        int start = buffer.position();
        int limit = buffer.limit();
        int i = start;

        while (i + Long.BYTES <= limit) {
            long word = buffer.getLong(i);
            if (hasZeroByte(word)) {
                return i + firstZeroByteIndex(word);
            }
            i += Long.BYTES;
        }

        while (i < limit) {
            if (buffer.get(i) == 0) {
                return i;
            }
            i++;
        }

        return i;
    }

    private static boolean hasZeroByte(long v) {
        return ((v - 0x0101010101010101L) & ~v & 0x8080808080808080L) != 0;
    }

    private static int firstZeroByteIndex(long v) {
        for (int i = 0; i < 8; i++) {
            if (((v >>> (i * 8)) & 0xFF) == 0) {
                return i;
            }
        }
        return -1;
    }
}
