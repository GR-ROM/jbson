package su.grinev.messagepack;

import java.util.*;

/**
 * Zero-allocation map for small integer keys (0..15) plus a discriminator slot (1488).
 * Uses flat arrays — no HashMap$Node allocation on put/get.
 * Implements Map<Object, Object> for compatibility with BinaryDocument.
 */
public final class CompactMap implements Map<Object, Object> {

    private static final int SMALL_LIMIT = 16;
    private static final int DISCRIMINATOR_KEY = 1488;
    private static final Integer DISCRIMINATOR_KEY_BOXED = DISCRIMINATOR_KEY;

    private final Object[] values = new Object[SMALL_LIMIT];
    private long presentBits;
    private Object discriminatorValue;
    private boolean discriminatorPresent;
    private int size;

    @Override
    public Object put(Object key, Object value) {
        if (key instanceof Integer k) {
            int i = k;
            if (i >= 0 && i < SMALL_LIMIT) {
                Object old = values[i];
                values[i] = value;
                long bit = 1L << i;
                if ((presentBits & bit) == 0) {
                    presentBits |= bit;
                    size++;
                }
                return old;
            }
            if (i == DISCRIMINATOR_KEY) {
                Object old = discriminatorValue;
                discriminatorValue = value;
                if (!discriminatorPresent) {
                    discriminatorPresent = true;
                    size++;
                }
                return old;
            }
        }
        throw new UnsupportedOperationException("Unsupported key: " + key + " (" + (key == null ? "null" : key.getClass().getName()) + ")");
    }

    @Override
    public Object get(Object key) {
        if (key instanceof Integer k) {
            int i = k;
            if (i >= 0 && i < SMALL_LIMIT) {
                return (presentBits & (1L << i)) != 0 ? values[i] : null;
            }
            if (i == DISCRIMINATOR_KEY) {
                return discriminatorPresent ? discriminatorValue : null;
            }
        }
        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Integer k) {
            int i = k;
            if (i >= 0 && i < SMALL_LIMIT) return (presentBits & (1L << i)) != 0;
            if (i == DISCRIMINATOR_KEY) return discriminatorPresent;
        }
        return false;
    }

    @Override
    public Object remove(Object key) {
        if (key instanceof Integer k) {
            int i = k;
            if (i >= 0 && i < SMALL_LIMIT) {
                long bit = 1L << i;
                if ((presentBits & bit) != 0) {
                    Object old = values[i];
                    values[i] = null;
                    presentBits &= ~bit;
                    size--;
                    return old;
                }
                return null;
            }
            if (i == DISCRIMINATOR_KEY && discriminatorPresent) {
                Object old = discriminatorValue;
                discriminatorValue = null;
                discriminatorPresent = false;
                size--;
                return old;
            }
        }
        return null;
    }

    @Override
    public void clear() {
        if (presentBits != 0) {
            long bits = presentBits;
            while (bits != 0) {
                int i = Long.numberOfTrailingZeros(bits);
                values[i] = null;
                bits &= bits - 1;
            }
            presentBits = 0;
        }
        discriminatorValue = null;
        discriminatorPresent = false;
        size = 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean containsValue(Object value) {
        long bits = presentBits;
        while (bits != 0) {
            int i = Long.numberOfTrailingZeros(bits);
            if (Objects.equals(values[i], value)) return true;
            bits &= bits - 1;
        }
        return discriminatorPresent && Objects.equals(discriminatorValue, value);
    }

    @Override
    public void putAll(Map<?, ?> m) {
        for (Entry<?, ?> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public Set<Object> keySet() {
        Set<Object> keys = new LinkedHashSet<>(size);
        long bits = presentBits;
        while (bits != 0) {
            keys.add(Long.numberOfTrailingZeros(bits));
            bits &= bits - 1;
        }
        if (discriminatorPresent) keys.add(DISCRIMINATOR_KEY);
        return keys;
    }

    @Override
    public Collection<Object> values() {
        List<Object> vals = new ArrayList<>(size);
        long bits = presentBits;
        while (bits != 0) {
            vals.add(values[Long.numberOfTrailingZeros(bits)]);
            bits &= bits - 1;
        }
        if (discriminatorPresent) vals.add(discriminatorValue);
        return vals;
    }

    /**
     * Returns a reusable iterator over entries. Zero allocation.
     * NOT thread-safe — single consumer only.
     */
    public EntryIterator entryIterator() {
        reusableIterator.reset();
        return reusableIterator;
    }

    private final EntryIterator reusableIterator = new EntryIterator();
    private final ReusableEntry reusableEntry = new ReusableEntry();

    @Override
    public Set<Entry<Object, Object>> entrySet() {
        Set<Entry<Object, Object>> entries = new LinkedHashSet<>(size);
        long bits = presentBits;
        while (bits != 0) {
            int i = Long.numberOfTrailingZeros(bits);
            entries.add(new AbstractMap.SimpleImmutableEntry<>(i, values[i]));
            bits &= bits - 1;
        }
        if (discriminatorPresent) {
            entries.add(new AbstractMap.SimpleImmutableEntry<>(DISCRIMINATOR_KEY, discriminatorValue));
        }
        return entries;
    }

    final class ReusableEntry implements Entry<Object, Object> {
        int key;
        Object value;

        @Override public Object getKey() {
            // keys 0-15: Integer.valueOf returns JVM-cached instances (range -128..127)
            // key 1488: use pre-cached boxed constant to avoid allocation
            return key == DISCRIMINATOR_KEY ? DISCRIMINATOR_KEY_BOXED : Integer.valueOf(key);
        }
        @Override public Object getValue() { return value; }
        @Override public Object setValue(Object value) { throw new UnsupportedOperationException(); }
    }

    final class EntryIterator implements Iterator<Entry<Object, Object>> {
        private long remainingBits;
        private boolean discriminatorRemaining;

        void reset() {
            remainingBits = presentBits;
            discriminatorRemaining = discriminatorPresent;
        }

        @Override
        public boolean hasNext() {
            return remainingBits != 0 || discriminatorRemaining;
        }

        @Override
        public Entry<Object, Object> next() {
            if (remainingBits != 0) {
                int i = Long.numberOfTrailingZeros(remainingBits);
                remainingBits &= remainingBits - 1;
                reusableEntry.key = i;
                reusableEntry.value = values[i];
                return reusableEntry;
            }
            if (discriminatorRemaining) {
                discriminatorRemaining = false;
                reusableEntry.key = DISCRIMINATOR_KEY;
                reusableEntry.value = discriminatorValue;
                return reusableEntry;
            }
            throw new NoSuchElementException();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        long bits = presentBits;
        while (bits != 0) {
            int i = Long.numberOfTrailingZeros(bits);
            if (!first) sb.append(", ");
            sb.append(i).append("=").append(values[i]);
            first = false;
            bits &= bits - 1;
        }
        if (discriminatorPresent) {
            if (!first) sb.append(", ");
            sb.append(DISCRIMINATOR_KEY).append("=").append(discriminatorValue);
        }
        sb.append("}");
        return sb.toString();
    }
}
