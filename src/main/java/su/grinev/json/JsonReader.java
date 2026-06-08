package su.grinev.json;

import su.grinev.Document;
import su.grinev.json.token.Token;

import java.util.List;
import java.util.Map;

/**
 * JSON reader - wraps Tokenizer and JsonParser for convenience.
 * The Tokenizer and JsonParser are lightweight, so no complex pooling needed.
 */
public class JsonReader {

    private final JsonParser parser = new JsonParser();

    public Document deserialize(byte[] jsonBytes) {
        Tokenizer tokenizer = new Tokenizer(jsonBytes);
        List<Token> tokens = tokenizer.tokenize();
        return parser.parse(tokens);
    }

    public Map<String, Object> deserializeToMap(byte[] jsonBytes) {
        Tokenizer tokenizer = new Tokenizer(jsonBytes);
        List<Token> tokens = tokenizer.tokenize();
        return parser.parseObject(tokens);
    }
}
