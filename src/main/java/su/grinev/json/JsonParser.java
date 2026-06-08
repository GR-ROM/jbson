package su.grinev.json;

import su.grinev.Document;
import su.grinev.json.token.NumberToken;
import su.grinev.json.token.StringToken;
import su.grinev.json.token.Token;

import java.util.*;

import static su.grinev.json.token.TokenType.*;

public class JsonParser {

    private List<Token> tokens;
    private int pos;

    public Document parse(List<Token> tokenList) {
        return new Document(parseObject(tokenList));
    }

    public Map<String, Object> parseObject(List<Token> tokenList) {
        this.tokens = tokenList;
        this.pos = 0;

        if (pos >= tokens.size() || tokens.get(pos).getType() != CURLY_OPEN) {
            throw new IllegalArgumentException("Expected '{'");
        }
        pos++;

        return parseObjectContent();
    }

    public List<Object> parseArray(List<Token> tokenList) {
        this.tokens = tokenList;
        this.pos = 0;

        if (pos >= tokens.size() || tokens.get(pos).getType() != SQUARE_OPEN) {
            throw new IllegalArgumentException("Expected '['");
        }
        pos++;

        return parseArrayContent();
    }

    private Map<String, Object> parseObjectContent() {
        Map<String, Object> object = new HashMap<>();

        if (pos < tokens.size() && tokens.get(pos).getType() == CURLY_CLOSE) {
            pos++;
            return object;
        }

        while (pos < tokens.size()) {
            // Expect key
            Token keyToken = tokens.get(pos++);
            if (keyToken.getType() != STRING) {
                throw new IllegalArgumentException("Expected string key at position " + (pos - 1));
            }
            String key = ((StringToken) keyToken).getString();

            // Expect colon
            if (pos >= tokens.size() || tokens.get(pos).getType() != COLON) {
                throw new IllegalArgumentException("Expected ':' at position " + pos);
            }
            pos++;

            // Parse value
            Object value = parseValue();
            object.put(key, value);

            // Expect comma or closing brace
            if (pos >= tokens.size()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }

            Token next = tokens.get(pos);
            if (next.getType() == CURLY_CLOSE) {
                pos++;
                break;
            } else if (next.getType() == COMMA) {
                pos++;
            } else {
                throw new IllegalArgumentException("Expected ',' or '}' at position " + pos);
            }
        }

        return object;
    }

    private List<Object> parseArrayContent() {
        List<Object> array = new ArrayList<>();

        if (pos < tokens.size() && tokens.get(pos).getType() == SQUARE_CLOSE) {
            pos++;
            return array;
        }

        while (pos < tokens.size()) {
            Object value = parseValue();
            array.add(value);

            if (pos >= tokens.size()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }

            Token next = tokens.get(pos);
            if (next.getType() == SQUARE_CLOSE) {
                pos++;
                break;
            } else if (next.getType() == COMMA) {
                pos++;
            } else {
                throw new IllegalArgumentException("Expected ',' or ']' at position " + pos);
            }
        }

        return array;
    }

    private Object parseValue() {
        if (pos >= tokens.size()) {
            throw new IllegalArgumentException("Unexpected end of input");
        }

        Token token = tokens.get(pos++);
        return switch (token.getType()) {
            case STRING -> ((StringToken) token).getString();
            case NUMBER -> ((NumberToken) token).getNumber();
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case NULL -> null;
            case CURLY_OPEN -> parseObjectContent();
            case SQUARE_OPEN -> parseArrayContent();
            default -> throw new IllegalArgumentException("Unexpected token: " + token.getType());
        };
    }
}