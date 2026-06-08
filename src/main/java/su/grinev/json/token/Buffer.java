package su.grinev.json.token;

import java.nio.ByteBuffer;

public class Buffer {

    private final ByteBuffer byteBuffer;
    private int pos;

    public Buffer(ByteBuffer byteBuffer) {
        this.byteBuffer = byteBuffer;
    }

    public boolean hasNext() {
        return pos < byteBuffer.limit();
    }

    public char peek() {
        return (char) byteBuffer.get(pos);
    }

    public char next() {
        return (char) byteBuffer.get(pos++);
    }

    public long getLong() {
        return byteBuffer.getLong(pos);
    }

    public long setPost(int newPos) {
        this.pos = newPos;
        return newPos;
    }

    public int size() {
        return byteBuffer.limit();
    }

    public String getString(int startPos, int count) {
        return new String(byteBuffer.array(), startPos, count);
    }

    public int remaining() {
        return byteBuffer.limit() - pos;
    }

    public int getPos() {
        return pos;
    }
}
