package su.grinev;

import annotation.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forward-compat enum binding: an unknown wire constant maps to {@code UNKNOWN} when the enum
 * declares one, otherwise binding stays strict (throws). See {@link Binder}.
 */
public class EnumFallbackTest {

    enum WithUnknown { IOS, ANDROID, UNKNOWN }
    enum Strict { RED, GREEN }

    public static class Lenient { @Tag(0) public WithUnknown type; }
    public static class StrictDto { @Tag(0) public Strict color; }
    public record Rec(@Tag(0) WithUnknown type) {}

    private final Binder binder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);

    private static BinaryDocument doc(int key, Object val) {
        Map<Object, Object> m = new HashMap<>();
        m.put(key, val);
        return new BinaryDocument(m);
    }

    @Test
    void knownValue_binds() {
        assertEquals(WithUnknown.IOS, binder.bind(Lenient.class, doc(0, "IOS")).type);
    }

    @Test
    void unknownValue_fallsBackToUnknown() {
        assertEquals(WithUnknown.UNKNOWN, binder.bind(Lenient.class, doc(0, "WATCHOS")).type);
    }

    @Test
    void unknownValue_onEnumWithoutUnknown_throws() {
        assertThrows(RuntimeException.class, () -> binder.bind(StrictDto.class, doc(0, "BLUE")));
    }

    @Test
    void unknownValue_recordComponent_fallsBackToUnknown() {
        Rec r = binder.bind(Rec.class, doc(0, "FOO"));
        assertEquals(WithUnknown.UNKNOWN, r.type());
    }
}
