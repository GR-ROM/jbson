package su.grinev.json;

import su.grinev.Document;
import su.grinev.pool.DisposablePool;
import su.grinev.pool.DynamicByteBuffer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class JsonWriter {

    private final DisposablePool<DynamicByteBuffer> bufferPool;

    public JsonWriter(DisposablePool<DynamicByteBuffer> bufferPool) {
        this.bufferPool = bufferPool;
    }

    public DynamicByteBuffer serialize(Document document) {
        DynamicByteBuffer buffer = bufferPool.get();
        buffer.initBuffer();
        writeValue(buffer, document.getDocumentMap());
        return buffer;
    }

    private void writeValue(DynamicByteBuffer buffer, Object value) {
        switch (value) {
            case null -> writeNull(buffer);
            case String s -> writeString(buffer, s);
            case Boolean b -> writeBoolean(buffer, b);
            case Integer i -> writeNumber(buffer, i);
            case Long l -> writeNumber(buffer, l);
            case Float f -> writeNumber(buffer, f);
            case Double d -> writeNumber(buffer, d);
            case Map<?, ?> map -> writeObject(buffer, map);
            case List<?> list -> writeArray(buffer, list);
            default -> writeString(buffer, value.toString());
        }
    }

    private void writeNull(DynamicByteBuffer buffer) {
        buffer.ensureCapacity(4);
        buffer.put((byte) 'n');
        buffer.put((byte) 'u');
        buffer.put((byte) 'l');
        buffer.put((byte) 'l');
    }

    private void writeBoolean(DynamicByteBuffer buffer, boolean value) {
        if (value) {
            buffer.ensureCapacity(4);
            buffer.put((byte) 't');
            buffer.put((byte) 'r');
            buffer.put((byte) 'u');
            buffer.put((byte) 'e');
        } else {
            buffer.ensureCapacity(5);
            buffer.put((byte) 'f');
            buffer.put((byte) 'a');
            buffer.put((byte) 'l');
            buffer.put((byte) 's');
            buffer.put((byte) 'e');
        }
    }

    private void writeNumber(DynamicByteBuffer buffer, Number number) {
        byte[] bytes = number.toString().getBytes(StandardCharsets.UTF_8);
        buffer.ensureCapacity(bytes.length);
        buffer.put(bytes);
    }

    private void writeString(DynamicByteBuffer buffer, String s) {
        buffer.ensureCapacity(2);
        buffer.put((byte) '"');

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> {
                    buffer.ensureCapacity(2);
                    buffer.put((byte) '\\');
                    buffer.put((byte) '"');
                }
                case '\\' -> {
                    buffer.ensureCapacity(2);
                    buffer.put((byte) '\\');
                    buffer.put((byte) '\\');
                }
                case '\n' -> {
                    buffer.ensureCapacity(2);
                    buffer.put((byte) '\\');
                    buffer.put((byte) 'n');
                }
                case '\r' -> {
                    buffer.ensureCapacity(2);
                    buffer.put((byte) '\\');
                    buffer.put((byte) 'r');
                }
                case '\t' -> {
                    buffer.ensureCapacity(2);
                    buffer.put((byte) '\\');
                    buffer.put((byte) 't');
                }
                default -> {
                    if (c < 0x20) {
                        buffer.ensureCapacity(6);
                        buffer.put((byte) '\\');
                        buffer.put((byte) 'u');
                        buffer.put((byte) '0');
                        buffer.put((byte) '0');
                        buffer.put((byte) Character.forDigit((c >> 4) & 0xF, 16));
                        buffer.put((byte) Character.forDigit(c & 0xF, 16));
                    } else if (c < 0x80) {
                        buffer.ensureCapacity(1);
                        buffer.put((byte) c);
                    } else {
                        byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                        buffer.ensureCapacity(utf8.length);
                        buffer.put(utf8);
                    }
                }
            }
        }

        buffer.ensureCapacity(1);
        buffer.put((byte) '"');
    }

    @SuppressWarnings("unchecked")
    private void writeObject(DynamicByteBuffer buffer, Map<?, ?> map) {
        buffer.ensureCapacity(1);
        buffer.put((byte) '{');

        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                buffer.ensureCapacity(1);
                buffer.put((byte) ',');
            }
            first = false;

            writeString(buffer, entry.getKey().toString());
            buffer.ensureCapacity(1);
            buffer.put((byte) ':');
            writeValue(buffer, entry.getValue());
        }

        buffer.ensureCapacity(1);
        buffer.put((byte) '}');
    }

    private void writeArray(DynamicByteBuffer buffer, List<?> list) {
        buffer.ensureCapacity(1);
        buffer.put((byte) '[');

        boolean first = true;
        for (Object item : list) {
            if (!first) {
                buffer.ensureCapacity(1);
                buffer.put((byte) ',');
            }
            first = false;
            writeValue(buffer, item);
        }

        buffer.ensureCapacity(1);
        buffer.put((byte) ']');
    }
}
