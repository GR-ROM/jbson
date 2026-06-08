package su.grinev.json;

public enum ParserState {
    EXPECT_KEY,
    EXPECT_COLON,
    EXPECT_VALUE,
    EXPECT_COMMA_OR_CURLY_CLOSE
}
