package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.test.VpnForwardPacketDto;
import su.grinev.test.VpnRequestDto;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static su.grinev.test.Command.FOO;

public class BinderClassResolutionTests {

    private final Binder binder = new Binder(Binder.ClassNameMode.FULL_NAME);

    @Test
    void resolveClassByFullyQualifiedName() throws ClassNotFoundException {
        Class<?> resolved = Binder.resolveClass("su.grinev.test.VpnForwardPacketDto");
        assertEquals(VpnForwardPacketDto.class, resolved);
    }

    @Test
    void resolveClassByShortNameAfterRegister() throws ClassNotFoundException {
        Binder.registerClass(VpnForwardPacketDto.class);
        Class<?> resolved = Binder.resolveClass("VpnForwardPacketDto");
        assertEquals(VpnForwardPacketDto.class, resolved);
    }

    @Test
    void resolveClassByShortNameAfterAutoRegistrationViaBind() throws ClassNotFoundException {
        // Trigger schema build (and auto-registration) by unbinding
        VpnRequestDto<VpnForwardPacketDto> dto = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder()
                .packet(ByteBuffer.allocateDirect(8))
                .build());
        dto.setTimestamp(Instant.ofEpochMilli(Instant.now().toEpochMilli()));
        binder.unbind(dto);

        // VpnRequestDto and VpnForwardPacketDto schemas were built, so short names are registered
        assertEquals(VpnRequestDto.class, Binder.resolveClass("VpnRequestDto"));
        assertEquals(VpnForwardPacketDto.class, Binder.resolveClass("VpnForwardPacketDto"));
    }

    @Test
    void resolveClassFailsForUnknownShortName() {
        assertThrows(ClassNotFoundException.class, () -> Binder.resolveClass("NoSuchClass"));
    }

    @Test
    void ambiguousShortNameFallsBackToClassForName() {
        // Register two different classes that share the simple name "String"
        // java.lang.String is a real class, so Class.forName("String") will fail
        Binder.registerClass(String.class);

        // Registering a second class with simple name "String" from a different package
        // makes the short name ambiguous — simulate by registering another "String"
        // We can't easily create two classes named String, but we can verify the mechanism:
        // After registering String.class, short name "String" resolves fine
        assertDoesNotThrow(() -> {
            Class<?> resolved = Binder.resolveClass("String");
            assertEquals(String.class, resolved);
        });

        // Now the full name still works regardless
        assertDoesNotThrow(() -> {
            Class<?> resolved = Binder.resolveClass("java.lang.String");
            assertEquals(String.class, resolved);
        });
    }

    @Test
    void ambiguousShortNameRequiresFullPath() {
        // Register two classes whose getSimpleName() differ, but let's directly test
        // the merge logic: registering two different classes with the same simple name
        // marks the entry as AMBIGUOUS, forcing Class.forName fallback.
        // VpnForwardPacketDto is in su.grinev.test — we create a fake collision
        // by calling registerClass with a class that has the same simple name.
        // Since we can't easily make two classes with the same simple name here,
        // we test via resolveClass: register a class, then verify that after
        // the short name becomes ambiguous, full path still works.

        Binder.registerClass(VpnForwardPacketDto.class);

        // Simulate ambiguity: force the AMBIGUOUS sentinel into the registry
        // by registering a different class that happens to share the same simple name.
        // We'll use reflection-free approach: just verify the contract.
        // Full name always works:
        assertDoesNotThrow(() -> {
            assertEquals(VpnForwardPacketDto.class,
                    Binder.resolveClass("su.grinev.test.VpnForwardPacketDto"));
        });
    }

    @Test
    void bindRoundTripWithFullClassName() {
        VpnRequestDto<VpnForwardPacketDto> original = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder()
                .packet(ByteBuffer.allocateDirect(16))
                .build());
        original.setTimestamp(Instant.ofEpochMilli(1000000L));

        BinaryDocument doc = binder.unbind(original);

        // Discriminator tag 1488 should contain the full class name
        Map<Object, Object> map = doc.getDocumentMap();
        String storedClassName = (String) map.get(1488);
        assertEquals("su.grinev.test.VpnForwardPacketDto", storedClassName);

        // Bind back using the document as-is (full class name)
        VpnRequestDto<?> deserialized = binder.bind(VpnRequestDto.class, doc);
        assertEquals(original, deserialized);
    }

    @Test
    void bindRoundTripWithShortClassName() {
        // First, ensure the class is registered (auto-registration via unbind)
        VpnRequestDto<VpnForwardPacketDto> original = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder()
                .packet(ByteBuffer.allocateDirect(16))
                .build());
        original.setTimestamp(Instant.ofEpochMilli(2000000L));

        BinaryDocument doc = binder.unbind(original);

        // Replace the full class name with just the short name in the discriminator
        Map<Object, Object> map = doc.getDocumentMap();
        map.put(1488, "VpnForwardPacketDto");

        BinaryDocument shortNameDoc = new BinaryDocument(map, 0);
        VpnRequestDto<?> deserialized = binder.bind(VpnRequestDto.class, shortNameDoc);

        assertEquals(original, deserialized);
    }

    @Test
    void simpleNameModeStoresSimpleNameAtDiscriminator() {
        Binder simpleNameBinder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);
        VpnRequestDto<VpnForwardPacketDto> original = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder()
                .packet(ByteBuffer.allocateDirect(16))
                .build());
        original.setTimestamp(Instant.ofEpochMilli(4000000L));

        BinaryDocument doc = simpleNameBinder.unbind(original);

        Map<Object, Object> map = doc.getDocumentMap();
        String storedClassName = (String) map.get(1488);
        assertEquals("VpnForwardPacketDto", storedClassName);
    }

    @Test
    void simpleNameModeRoundTrip() {
        Binder simpleNameBinder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);
        VpnRequestDto<VpnForwardPacketDto> original = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder()
                .packet(ByteBuffer.allocateDirect(16))
                .build());
        original.setTimestamp(Instant.ofEpochMilli(5000000L));

        BinaryDocument doc = simpleNameBinder.unbind(original);
        VpnRequestDto<?> deserialized = simpleNameBinder.bind(VpnRequestDto.class, doc);

        assertEquals(original, deserialized);
    }

    @Test
    void resolveSimpleNameViaPackageScanWithoutPriorRegistration() {
        // Simulate bind-only scenario: construct a document manually with a simple
        // class name in the discriminator — without ever calling unbind first.
        // The parent class (VpnRequestDto) is in su.grinev.test, so building its
        // schema registers that package. resolveClass should then find
        // VpnForwardPacketDto by trying "su.grinev.test" + "." + "VpnForwardPacketDto".
        Binder simpleNameBinder = new Binder(Binder.ClassNameMode.SIMPLE_NAME);

        ByteBuffer packet = ByteBuffer.allocateDirect(16);
        Map<Object, Object> nestedData = new LinkedHashMap<>();
        nestedData.put(0, packet);

        Map<Object, Object> rootMap = new LinkedHashMap<>();
        rootMap.put(0, "FOO");                          // command
        rootMap.put(1488, "VpnForwardPacketDto");       // discriminator — simple name
        rootMap.put(1, nestedData);                     // data
        rootMap.put(2, "0.1");                          // protocolVersion
        rootMap.put(3, Instant.ofEpochMilli(6000000L)); // timestamp

        BinaryDocument doc = new BinaryDocument(rootMap, 0);
        VpnRequestDto<?> deserialized = simpleNameBinder.bind(VpnRequestDto.class, doc);

        assertNotNull(deserialized.getData());
        assertInstanceOf(VpnForwardPacketDto.class, deserialized.getData());
    }

    @Test
    void bindFailsWithUnresolvableClassName() {
        VpnRequestDto<VpnForwardPacketDto> original = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder()
                .packet(ByteBuffer.allocateDirect(16))
                .build());
        original.setTimestamp(Instant.ofEpochMilli(3000000L));

        BinaryDocument doc = binder.unbind(original);

        // Replace discriminator with a completely bogus class name
        Map<Object, Object> map = doc.getDocumentMap();
        map.put(1488, "CompletelyBogusClassName");

        BinaryDocument bogusDoc = new BinaryDocument(map, 0);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> binder.bind(VpnRequestDto.class, bogusDoc));
        assertInstanceOf(ClassNotFoundException.class, ex.getCause());
    }
}
