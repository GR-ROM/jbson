package su.grinev;

import annotation.Pattern;
import annotation.Range;
import annotation.Size;
import annotation.Tag;
import org.junit.jupiter.api.Test;
import su.grinev.exception.ValidationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link annotation.Size} bounds enforced by {@link Binder} during binding (deserialization).
 */
public class SizeValidationTest {

    public static class SizedDto {
        @Tag(0) @Size(max = 5)             public String name;
        @Tag(1) @Size(max = 3)             public List<String> items;
        @Tag(2) @Size(min = 2, max = 4)    public String code;
        @Tag(3) @Range(min = 0, max = 65535) public int port;
        @Tag(4) @Pattern("\\d+\\.\\d+")    public String version;
    }

    private final Binder binder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);

    private static BinaryDocument doc(Object... kv) {
        Map<Object, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return new BinaryDocument(m);
    }

    @Test
    void withinBounds_binds() {
        SizedDto d = binder.bind(SizedDto.class, doc(0, "abc", 1, List.of("a", "b"), 2, "xy"));
        assertEquals("abc", d.name);
        assertEquals(2, d.items.size());
        assertEquals("xy", d.code);
    }

    @Test
    void atBoundary_binds() {
        SizedDto d = binder.bind(SizedDto.class, doc(0, "abcde", 1, List.of("a", "b", "c"), 2, "wxyz"));
        assertEquals("abcde", d.name);
        assertEquals(3, d.items.size());
        assertEquals("wxyz", d.code);
    }

    @Test
    void stringOverMax_throws() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> binder.bind(SizedDto.class, doc(0, "abcdef")));
        assertTrue(ex.getMessage().contains("tag 0"), ex.getMessage());
    }

    @Test
    void collectionOverMax_throws() {
        assertThrows(ValidationException.class,
                () -> binder.bind(SizedDto.class, doc(1, List.of("a", "b", "c", "d"))));
    }

    @Test
    void stringUnderMin_throws() {
        assertThrows(ValidationException.class,
                () -> binder.bind(SizedDto.class, doc(2, "x")));
    }

    @Test
    void absentFields_noFailure() {
        SizedDto d = binder.bind(SizedDto.class, doc(0, "abc"));
        assertEquals("abc", d.name);
        assertNull(d.items);
        assertNull(d.code);
    }

    // ---- @Range ----

    @Test
    void numberInRange_binds() {
        SizedDto d = binder.bind(SizedDto.class, doc(3, 8080));
        assertEquals(8080, d.port);
    }

    @Test
    void numberOverMax_throws() {
        assertThrows(ValidationException.class, () -> binder.bind(SizedDto.class, doc(3, 70000)));
    }

    @Test
    void numberUnderMin_throws() {
        assertThrows(ValidationException.class, () -> binder.bind(SizedDto.class, doc(3, -1)));
    }

    @Test
    void rangeBoundaries_bind() {
        assertEquals(0, binder.bind(SizedDto.class, doc(3, 0)).port);
        assertEquals(65535, binder.bind(SizedDto.class, doc(3, 65535)).port);
    }

    // ---- @Pattern ----

    @Test
    void patternMatches_binds() {
        SizedDto d = binder.bind(SizedDto.class, doc(4, "0.2"));
        assertEquals("0.2", d.version);
    }

    @Test
    void patternNoMatch_throws() {
        assertThrows(ValidationException.class, () -> binder.bind(SizedDto.class, doc(4, "v0.2-beta")));
    }

    // ---- record components ----

    public record Ver(@Tag(0) @Range(min = 0, max = 9999) int major, @Tag(1) int minor) {}

    @Test
    void recordComponentInRange_binds() {
        Ver v = binder.bind(Ver.class, doc(0, 5, 1, 9));
        assertEquals(5, v.major());
        assertEquals(9, v.minor());
    }

    @Test
    void recordComponentOverMax_throws() {
        assertThrows(ValidationException.class, () -> binder.bind(Ver.class, doc(0, 10000, 1, 2)));
    }
}
