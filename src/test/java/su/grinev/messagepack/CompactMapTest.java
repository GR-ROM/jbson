package su.grinev.messagepack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompactMapTest {

    private CompactMap map;

    @BeforeEach
    void setUp() {
        map = new CompactMap();
    }

    @Test
    void emptyMap() {
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertNull(map.get(0));
        assertFalse(map.containsKey(0));
    }

    @Test
    void putAndGetSmallKeys() {
        map.put(0, "zero");
        map.put(5, "five");
        map.put(15, "fifteen");

        assertEquals("zero", map.get(0));
        assertEquals("five", map.get(5));
        assertEquals("fifteen", map.get(15));
        assertEquals(3, map.size());
        assertFalse(map.isEmpty());
    }

    @Test
    void putAndGetDiscriminator() {
        map.put(1488, "discriminator");
        assertEquals("discriminator", map.get(1488));
        assertTrue(map.containsKey(1488));
        assertEquals(1, map.size());
    }

    @Test
    void putReturnsOldValue() {
        map.put(3, "old");
        Object old = map.put(3, "new");
        assertEquals("old", old);
        assertEquals("new", map.get(3));
        assertEquals(1, map.size());
    }

    @Test
    void putDiscriminatorReturnsOldValue() {
        map.put(1488, "old");
        Object old = map.put(1488, "new");
        assertEquals("old", old);
        assertEquals("new", map.get(1488));
        assertEquals(1, map.size());
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertNull(map.get(7));
        assertNull(map.get(1488));
        assertNull(map.get("string_key"));
    }

    @Test
    void containsKey() {
        map.put(2, "val");
        map.put(1488, "disc");

        assertTrue(map.containsKey(2));
        assertTrue(map.containsKey(1488));
        assertFalse(map.containsKey(3));
        assertFalse(map.containsKey(999));
    }

    @Test
    void containsValue() {
        map.put(0, "a");
        map.put(1488, "b");

        assertTrue(map.containsValue("a"));
        assertTrue(map.containsValue("b"));
        assertFalse(map.containsValue("c"));
    }

    @Test
    void removeSmallKey() {
        map.put(4, "four");
        assertEquals(1, map.size());

        Object removed = map.remove(4);
        assertEquals("four", removed);
        assertEquals(0, map.size());
        assertNull(map.get(4));
        assertFalse(map.containsKey(4));
    }

    @Test
    void removeDiscriminator() {
        map.put(1488, "disc");
        Object removed = map.remove(1488);
        assertEquals("disc", removed);
        assertEquals(0, map.size());
        assertNull(map.get(1488));
    }

    @Test
    void removeMissingKeyReturnsNull() {
        assertNull(map.remove(5));
        assertNull(map.remove(1488));
    }

    @Test
    void clear() {
        map.put(0, "a");
        map.put(3, "b");
        map.put(15, "c");
        map.put(1488, "d");
        assertEquals(4, map.size());

        map.clear();
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
        assertNull(map.get(0));
        assertNull(map.get(3));
        assertNull(map.get(15));
        assertNull(map.get(1488));
    }

    @Test
    void clearEmptyMapIsNoOp() {
        map.clear();
        assertTrue(map.isEmpty());
    }

    @Test
    void vpnProtocolStructure() {
        // Root map: {0: ver, 1: timestamp, 2: payload, 1488: discriminator}
        map.put(0, "0.1");
        map.put(1, 1234567890L);
        map.put(2, "payload_map");
        map.put(1488, "RequestDto");

        assertEquals(4, map.size());
        assertEquals("0.1", map.get(0));
        assertEquals(1234567890L, map.get(1));
        assertEquals("payload_map", map.get(2));
        assertEquals("RequestDto", map.get(1488));
    }

    @Test
    void allSmallKeysOccupied() {
        for (int i = 0; i < 16; i++) {
            map.put(i, "val" + i);
        }
        assertEquals(16, map.size());
        for (int i = 0; i < 16; i++) {
            assertEquals("val" + i, map.get(i));
        }
    }

    @Test
    void unsupportedKeyThrows() {
        assertThrows(UnsupportedOperationException.class, () -> map.put("string", "value"));
        assertThrows(UnsupportedOperationException.class, () -> map.put(16, "value"));
        assertThrows(UnsupportedOperationException.class, () -> map.put(-1, "value"));
        assertThrows(UnsupportedOperationException.class, () -> map.put(999, "value"));
    }

    @Test
    void putAll() {
        Map<Object, Object> source = Map.of(0, "a", 3, "b", 1488, "c");
        map.putAll(source);
        assertEquals(3, map.size());
        assertEquals("a", map.get(0));
        assertEquals("b", map.get(3));
        assertEquals("c", map.get(1488));
    }

    @Test
    void keySet() {
        map.put(1, "a");
        map.put(5, "b");
        map.put(1488, "c");

        Set<Object> keys = map.keySet();
        assertEquals(3, keys.size());
        assertTrue(keys.contains(1));
        assertTrue(keys.contains(5));
        assertTrue(keys.contains(1488));
    }

    @Test
    void values() {
        map.put(0, "x");
        map.put(1488, "y");
        var vals = map.values();
        assertEquals(2, vals.size());
        assertTrue(vals.contains("x"));
        assertTrue(vals.contains("y"));
    }

    @Test
    void entrySet() {
        map.put(2, "two");
        map.put(1488, "disc");

        Set<Map.Entry<Object, Object>> entries = map.entrySet();
        assertEquals(2, entries.size());
    }

    @Test
    void toStringFormat() {
        map.put(0, "val");
        String s = map.toString();
        assertTrue(s.contains("0=val"));
        assertTrue(s.startsWith("{"));
        assertTrue(s.endsWith("}"));
    }

    @Test
    void reuseAfterClear() {
        map.put(0, "first");
        map.put(1488, "disc1");
        map.clear();

        map.put(0, "second");
        map.put(1488, "disc2");

        assertEquals("second", map.get(0));
        assertEquals("disc2", map.get(1488));
        assertEquals(2, map.size());
    }

    @Test
    void nullValues() {
        map.put(0, null);
        assertTrue(map.containsKey(0));
        assertNull(map.get(0));
        assertEquals(1, map.size());
    }

    // --- EntryIterator tests ---

    @Test
    void entryIterator_empty() {
        var it = map.entryIterator();
        assertFalse(it.hasNext());
    }

    @Test
    void entryIterator_smallKeys() {
        map.put(0, "a");
        map.put(3, "b");
        map.put(7, "c");

        var it = map.entryIterator();
        Map<Object, Object> collected = new java.util.HashMap<>();
        while (it.hasNext()) {
            var e = it.next();
            collected.put(e.getKey(), e.getValue());
        }
        assertEquals(3, collected.size());
        assertEquals("a", collected.get(0));
        assertEquals("b", collected.get(3));
        assertEquals("c", collected.get(7));
    }

    @Test
    void entryIterator_withDiscriminator() {
        map.put(1, "val");
        map.put(1488, "disc");

        var it = map.entryIterator();
        Map<Object, Object> collected = new java.util.HashMap<>();
        while (it.hasNext()) {
            var e = it.next();
            collected.put(e.getKey(), e.getValue());
        }
        assertEquals(2, collected.size());
        assertEquals("val", collected.get(1));
        assertEquals("disc", collected.get(1488));
    }

    @Test
    void entryIterator_reusable() {
        map.put(0, "first");

        var it1 = map.entryIterator();
        assertTrue(it1.hasNext());
        it1.next();
        assertFalse(it1.hasNext());

        // Reset via entryIterator() call
        var it2 = map.entryIterator();
        assertTrue(it2.hasNext());
        assertSame(it1, it2, "Should reuse same iterator instance");
    }

    @Test
    void entryIterator_noSuchElement() {
        var it = map.entryIterator();
        assertThrows(java.util.NoSuchElementException.class, it::next);
    }

    @Test
    void entryIterator_usedBySerialization() {
        // Simulates how MessagePackWriter uses the iterator
        map.put(0, "0.1");
        map.put(1, 123L);
        map.put(2, "payload");
        map.put(1488, "RequestDto");

        var it = map.entryIterator();
        int count = 0;
        while (it.hasNext()) {
            var entry = it.next();
            assertNotNull(entry.getKey());
            assertNotNull(entry.getValue());
            count++;
        }
        assertEquals(4, count);
    }
}
