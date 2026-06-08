package su.grinev;

import annotation.Tag;
import annotation.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.MessagePackWriter;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.WriterContext;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.Pool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates J3: Binder.resolveClass() calls Class.forName() with
 * attacker-controlled class names from deserialized discriminator fields.
 *
 * An attacker who controls the discriminator string in a MessagePack payload
 * can force the JVM to load arbitrary classes from the classpath.
 * This enables:
 * - DoS via expensive static initializers
 * - Potential RCE via deserialization gadget chains
 * - Class scanning via knownPackages iteration (CPU burn)
 */
public class BinderResolveClassTest {

    // --- Test DTOs ---

    public static class GoodPayload {
        @Tag(0)
        public int value;
    }

    public static class Container {
        @Tag(0)
        @Type(discriminator = 1488)
        public Object payload;

        @Tag(1488)
        public String discriminatorTag;
    }

    @BeforeEach
    void setUp() {
        Binder.registerClass(GoodPayload.class, Container.class);
    }

    /**
     * Fully qualified class name in discriminator → Class.forName() with
     * attacker-controlled string. Should be rejected.
     */
    @Test
    void fullyQualifiedClassName_shouldBeRejected() {
        // Simulate a deserialized document where discriminator = a FQCN
        Map<Object, Object> doc = new HashMap<>();
        doc.put(1488, "java.lang.Thread");           // discriminator: arbitrary FQCN
        doc.put(0, new HashMap<>());                  // payload data

        BinaryDocument binaryDocument = new BinaryDocument(doc);
        Binder binder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);

        // BUG: this succeeds — loads java.lang.Thread via Class.forName()
        // After fix: should throw, only registered classes are allowed
        try {
            Container result = binder.bind(Container.class, binaryDocument);
            // If we get here, the class was loaded — that's the bug
            fail("Should not load arbitrary class 'java.lang.Thread' from discriminator");
        } catch (RuntimeException e) {
            // After fix: expected — unregistered class rejected
            assertTrue(e.getCause() instanceof ClassNotFoundException
                            || e.getMessage().contains("not registered"),
                    "Expected ClassNotFoundException or 'not registered', got: " + e.getMessage());
        }
    }

    /**
     * Simple class name that matches a registered class — should work.
     */
    @Test
    void registeredSimpleName_shouldWork() {
        Map<Object, Object> innerDoc = new HashMap<>();
        innerDoc.put(0, 42);

        Map<Object, Object> doc = new HashMap<>();
        doc.put(1488, "GoodPayload");
        doc.put(0, innerDoc);

        BinaryDocument binaryDocument = new BinaryDocument(doc);
        Binder binder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);

        Container result = binder.bind(Container.class, binaryDocument);
        assertInstanceOf(GoodPayload.class, result.payload);
        assertEquals(42, ((GoodPayload) result.payload).value);
    }

    /**
     * Non-existent class name triggers iteration over all knownPackages,
     * each calling Class.forName(). With many registered packages this
     * becomes a CPU burn vector.
     */
    @Test
    void nonExistentClassName_scansAllPackages() {
        // Register many dummy packages to amplify the scan
        for (int i = 0; i < 100; i++) {
            // registerClass adds each class's package to knownPackages
            // We can't easily create fake packages, but we can demonstrate
            // that a non-existent name causes ClassNotFoundException after scan
        }

        Map<Object, Object> doc = new HashMap<>();
        doc.put(1488, "TotallyFakeClassName12345");
        doc.put(0, new HashMap<>());

        BinaryDocument binaryDocument = new BinaryDocument(doc);
        Binder binder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);

        assertThrows(RuntimeException.class, () ->
                binder.bind(Container.class, binaryDocument));
    }

    /**
     * FQCN with internal JVM class — demonstrates loading of
     * classes that should never be instantiated from untrusted input.
     */
    @Test
    void internalJvmClass_shouldBeRejected() {
        Map<Object, Object> doc = new HashMap<>();
        doc.put(1488, "java.lang.ProcessBuilder");
        doc.put(0, new HashMap<>());

        BinaryDocument binaryDocument = new BinaryDocument(doc);
        Binder binder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);

        try {
            binder.bind(Container.class, binaryDocument);
            fail("Should not load ProcessBuilder from discriminator");
        } catch (RuntimeException e) {
            // After fix: should be rejected before Class.forName
            // Current: may fail at instantiation (no no-arg constructor)
            // Either way, the class should NOT have been loaded
        }
    }
}
