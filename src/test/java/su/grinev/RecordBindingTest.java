package su.grinev;

import annotation.Tag;
import org.junit.jupiter.api.Test;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies records are supported by the Binder: serialized via the canonical record fields and
 * deserialized via the canonical constructor (records are immutable — no no-arg ctor/setters).
 * Covers a top-level record, a record nested in a class, and a {@code List<record>}.
 */
public class RecordBindingTest {

    public record Version(@Tag(0) int major, @Tag(1) int minor) {}

    public static class Holder {
        @Tag(0)
        public String name;
        @Tag(1)
        public List<Version> versions;
    }

    public record Outer(@Tag(0) String id, @Tag(1) Version version) {}

    private Codec codec() {
        PoolFactory pf = PoolFactory.Builder.builder()
                .setMinPoolSize(1).setMaxPoolSize(100).setOutOfPoolTimeout(1000).setBlocking(true).build();
        return Codec.messagePack(pf, 4096, Binder.ClassNameMode.SIMPLE_NAME);
    }

    private <T> T roundTrip(Codec codec, Object o, Class<T> type) {
        ByteBuffer wire = codec.serialize(o).getBuffer();
        return codec.deserialize(wire, type);
    }

    @Test
    void topLevelRecordRoundTrips() {
        Codec codec = codec();
        Version out = roundTrip(codec, new Version(0, 2), Version.class);
        assertEquals(new Version(0, 2), out);
        assertEquals(0, out.major());
        assertEquals(2, out.minor());
    }

    @Test
    void recordNestedInClassRoundTrips() {
        Codec codec = codec();
        Outer out = roundTrip(codec, new Outer("x", new Version(1, 3)), Outer.class);
        assertEquals(new Outer("x", new Version(1, 3)), out);
    }

    @Test
    void listOfRecordsRoundTrips() {
        Codec codec = codec();
        Holder h = new Holder();
        h.name = "caps";
        h.versions = List.of(new Version(0, 1), new Version(0, 2));

        Holder out = roundTrip(codec, h, Holder.class);

        assertEquals("caps", out.name);
        assertEquals(2, out.versions.size());
        assertEquals(List.of(new Version(0, 1), new Version(0, 2)), out.versions);
    }
}
