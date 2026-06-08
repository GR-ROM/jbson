package su.grinev.json.token;

public class BooleanToken {

    private final Boolean aBoolean;

    public BooleanToken(Boolean aBoolean) {
        this.aBoolean = aBoolean;
    }

    public Boolean getaBoolean() {
        return aBoolean;
    }

    @Override
    public String toString() {
        return "BooleanToken{" +
                "aBoolean=" + aBoolean +
                '}';
    }
}
