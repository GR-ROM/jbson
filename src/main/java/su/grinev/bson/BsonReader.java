package su.grinev.bson;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;

public interface BsonReader extends Position {
    String readString();
    float readFloat();
    double readDouble();
    int readInt();
    int readInt(int position);
    long readLong();
    boolean readBoolean();
    byte[] readBinaryAsArray();
    ByteBuffer readBinary(boolean readBinaryWithoutCopy);
    byte readByte();
    String readObjectId();
    Instant readDateTime();
    BigDecimal readDecimal128();
    String readCString();
}
