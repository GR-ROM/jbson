package su.grinev.json.token;

public class Token {

    // Singleton tokens for types without payload
    public static final Token CURLY_OPEN_TOKEN = new Token(TokenType.CURLY_OPEN);
    public static final Token CURLY_CLOSE_TOKEN = new Token(TokenType.CURLY_CLOSE);
    public static final Token SQUARE_OPEN_TOKEN = new Token(TokenType.SQUARE_OPEN);
    public static final Token SQUARE_CLOSE_TOKEN = new Token(TokenType.SQUARE_CLOSE);
    public static final Token COLON_TOKEN = new Token(TokenType.COLON);
    public static final Token COMMA_TOKEN = new Token(TokenType.COMMA);
    public static final Token TRUE_TOKEN = new Token(TokenType.TRUE);
    public static final Token FALSE_TOKEN = new Token(TokenType.FALSE);
    public static final Token NULL_TOKEN = new Token(TokenType.NULL);

    private TokenType type;

    public Token(TokenType tokenType) {
        this.type = tokenType;
    }

    public TokenType getType() {
        return type;
    }

    @Override
    public String toString() {
        return "Token{" +
                "type=" + type +
                '}';
    }
}
