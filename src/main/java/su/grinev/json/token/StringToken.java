package su.grinev.json.token;

public class StringToken extends Token {

    private final String string;

    public StringToken(String string) {
        super(TokenType.STRING);
        this.string = string;
    }

    public String getString() {
        return string;
    }

    @Override
    public String toString() {
        return "StringToken{" +
                "string='" + string + '\'' +
                '}';
    }
}
