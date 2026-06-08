package su.grinev.json.token;

public class NumberToken extends Token {

    private final Number number;

    public NumberToken(Number number) {
        super(TokenType.NUMBER);
        this.number = number;
    }

    public Number getNumber() {
        return number;
    }

    @Override
    public String toString() {
        return "NumberToken{" +
                "number=" + number +
                '}';
    }
}
