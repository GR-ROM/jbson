package su.grinev.json;

import su.grinev.json.token.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static su.grinev.json.token.TokenType.*;

public class Tokenizer {
    private static final char[] TRUE = "true".toCharArray();
    private static final char[] FALSE = "false".toCharArray();
    private static final char[] NULL = "null".toCharArray();
    private static final int NUMBER_BUFFER_SIZE = 32;

    // Thread-local buffer for number parsing - avoids StringBuilder allocation
    private static final ThreadLocal<char[]> numberBuffer = ThreadLocal.withInitial(() -> new char[NUMBER_BUFFER_SIZE]);

    private final Buffer buffer;
    private final StringParser stringParser;

    public Tokenizer(byte[] jsonString) {
        this.buffer = new Buffer(ByteBuffer.wrap(jsonString));
        this.stringParser = new StringParser(buffer);
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (buffer.hasNext()) {
            skipWhitespace();
            if (!buffer.hasNext()) break;
            char c = buffer.peek();

            Token token = switch (c) {
                case '{' -> { buffer.next(); yield new Token(CURLY_OPEN); }
                case '}' -> { buffer.next(); yield new Token(CURLY_CLOSE); }
                case '[' -> { buffer.next(); yield new Token(SQUARE_OPEN); }
                case ']' -> { buffer.next(); yield new Token(SQUARE_CLOSE); }
                case ':' -> { buffer.next(); yield new Token(COLON); }
                case ',' -> { buffer.next(); yield new Token(COMMA); }
                case 't' -> parseLiteral(TRUE, TokenType.TRUE);
                case 'f' -> parseLiteral(FALSE, TokenType.FALSE);
                case 'n' -> parseLiteral(NULL, TokenType.NULL);
                case '"' -> stringParser.parseString();
                default -> {
                    if ((c == '-') || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character at pos: %s character: '%s'".formatted(buffer.getPos(), c));
                }
            };

            tokens.add(token);
        }
        return tokens;
    }

    private void skipWhitespace() {
        while (buffer.hasNext() && Character.isWhitespace(buffer.peek())) {
            buffer.next();
        }
    }

    private Token parseLiteral(char[] expected, TokenType type) {
        for (char ec : expected) {
            if (!buffer.hasNext() || buffer.next() != ec) {
                throw new IllegalArgumentException("Invalid literal at pos: " + buffer.getPos());
            }
        }
        return new Token(type);
    }

    private NumberToken parseNumber() {
        char[] buf = numberBuffer.get();
        int len = 0;
        boolean isFloatingPoint = false;

        if (buffer.peek() == '-') {
            buf[len++] = buffer.next();
        }

        // Parse integer part
        if (buffer.peek() == '0') {
            buf[len++] = buffer.next();
        } else {
            while (buffer.hasNext() && Character.isDigit(buffer.peek())) {
                buf[len++] = buffer.next();
            }
        }

        // Parse fraction part
        if (buffer.hasNext() && buffer.peek() == '.') {
            isFloatingPoint = true;
            buf[len++] = buffer.next();
            int fractionStart = len;
            while (buffer.hasNext() && Character.isDigit(buffer.peek())) {
                buf[len++] = buffer.next();
            }
            if (len == fractionStart) {
                throw new IllegalArgumentException("Invalid fraction at pos: " + buffer.getPos());
            }
        }

        // Parse exponent part
        if (buffer.hasNext() && (buffer.peek() == 'e' || buffer.peek() == 'E')) {
            isFloatingPoint = true;
            buf[len++] = buffer.next();
            int expStart = len;
            if (buffer.hasNext() && (buffer.peek() == '+' || buffer.peek() == '-')) {
                buf[len++] = buffer.next();
                expStart++;
            }
            while (buffer.hasNext() && Character.isDigit(buffer.peek())) {
                buf[len++] = buffer.next();
            }
            if (len == expStart) {
                throw new IllegalArgumentException("Invalid exponent at pos: " + buffer.getPos());
            }
        }

        String numStr = new String(buf, 0, len);
        if (isFloatingPoint) {
            return new NumberToken(Double.parseDouble(numStr));
        } else {
            long longVal = Long.parseLong(numStr);
            if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                return new NumberToken((int) longVal);
            }
            return new NumberToken(longVal);
        }
    }
}