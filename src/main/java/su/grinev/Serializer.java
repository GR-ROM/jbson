package su.grinev;

import su.grinev.pool.DynamicByteBuffer;

public interface Serializer {
    void serialize(DynamicByteBuffer buffer, BinaryDocument document);
}
