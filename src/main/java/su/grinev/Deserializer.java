package su.grinev;

import java.nio.ByteBuffer;

public interface Deserializer {
    void deserialize(ByteBuffer buffer, BinaryDocument document);
}
