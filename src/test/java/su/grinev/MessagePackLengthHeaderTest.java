package su.grinev;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.MessagePackWriter;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.WriterContext;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.FastPool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Length-header / frame-offset contract. Callers read frames in-place from a shared buffer at the
 * buffer's current position (no per-frame slice()), so several length-prefixed frames can sit in one
 * buffer at non-zero offsets. The reader's over-read check must measure bytes consumed from the
 * frame's start — not the absolute buffer position — otherwise every non-first frame falsely logs
 * "Buffer is too small". This locks that behavior so we don't stumble on it again.
 */
public class MessagePackLengthHeaderTest {

    private final PoolFactory poolFactory = PoolFactory.Builder.builder()
            .setMinPoolSize(1)
            .setMaxPoolSize(10)
            .setOutOfPoolTimeout(1000)
            .setBlocking(false)
            .build();

    private final FastPool<ReaderContext> readerContextPool = poolFactory.getPool(ReaderContext::new);
    private final FastPool<ArrayDeque<ReaderContext>> stackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));
    private final FastPool<WriterContext> writerContextPool = poolFactory.getPool(WriterContext::new);
    private final FastPool<ArrayDeque<WriterContext>> writerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(16));

    private Logger readerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        readerLogger = (Logger) LoggerFactory.getLogger(MessagePackReader.class);
        appender = new ListAppender<>();
        appender.start();
        readerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        readerLogger.detachAppender(appender);
    }

    private ByteBuffer serialize(Map<Object, Object> map) {
        MessagePackWriter writer = new MessagePackWriter(writerContextPool, writerStackPool);
        DynamicByteBuffer buffer = new DynamicByteBuffer(64 * 1024, true);
        writer.serialize(buffer, new BinaryDocument(map));
        ByteBuffer src = buffer.getBuffer();              // flipped: position 0, limit = frame length
        ByteBuffer copy = ByteBuffer.allocate(src.remaining());
        copy.put(src.duplicate());
        copy.flip();
        return copy;
    }

    private boolean warnedBufferTooSmall() {
        return appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("Buffer is too small"));
    }

    @Test
    void decodesSecondFrameAtNonZeroOffsetWithoutFalseWarning() {
        Map<Object, Object> m1 = new HashMap<>();
        m1.put(0, 11);
        m1.put(1, "first");
        Map<Object, Object> m2 = new HashMap<>();
        m2.put(0, 22);
        m2.put(1, "second");

        ByteBuffer f1 = serialize(m1);
        ByteBuffer f2 = serialize(m2);

        // Two frames back-to-back in ONE buffer (as the transport delivers a batched read).
        ByteBuffer combined = ByteBuffer.allocate(f1.remaining() + f2.remaining());
        combined.put(f1).put(f2);
        combined.flip();
        int secondFrameOffset = combined.limit() - f2.capacity();
        assertTrue(secondFrameOffset > 0, "second frame must start at a non-zero offset");

        MessagePackReader reader = new MessagePackReader(readerContextPool, stackPool, false, false);

        BinaryDocument d1 = new BinaryDocument(new HashMap<>());
        reader.deserialize(combined, d1);                 // frame 1 at offset 0
        assertEquals(11, d1.get("0"));
        assertEquals("first", d1.get("1"));

        assertEquals(secondFrameOffset, combined.position(), "reader must land exactly at the next frame");

        BinaryDocument d2 = new BinaryDocument(new HashMap<>());
        reader.deserialize(combined, d2);                 // frame 2 at a NON-ZERO offset
        assertEquals(22, d2.get("0"));
        assertEquals("second", d2.get("1"));

        assertTrue(appender.list.isEmpty() || !warnedBufferTooSmall(),
                "no 'Buffer is too small' warning for a correctly-framed frame at a non-zero offset");
    }
}
