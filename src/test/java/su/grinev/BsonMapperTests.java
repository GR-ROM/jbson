package su.grinev;

import org.junit.jupiter.api.Test;
import su.grinev.bson.BsonObjectReader;
import su.grinev.bson.BsonObjectWriter;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.PoolFactory;
import su.grinev.test.VpnForwardPacketDto;
import su.grinev.test.VpnRequestDto;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static su.grinev.test.Command.FOO;

public class BsonMapperTests {

    @Test
    public void serializeAndDeserializeObjectTest() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(100)
                .setMaxPoolSize(1000)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        Codec codec = Codec.bson(poolFactory, 4096, () -> ByteBuffer.allocateDirect(4096), false);
        VpnRequestDto<VpnForwardPacketDto> vpnRequestDto = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder()
                .packet(ByteBuffer.allocateDirect(1024))
                .build());

        for (int i = 0; i < vpnRequestDto.getData().getPacket().limit(); i++) {
            vpnRequestDto.getData().getPacket().put(i, (byte) i);
        }

        vpnRequestDto.setTimestamp(Instant.ofEpochMilli(Instant.now().toEpochMilli()));

        DynamicByteBuffer b = codec.serialize(vpnRequestDto);
        VpnRequestDto<?> deserialized = codec.deserialize(b.getBuffer(), VpnRequestDto.class);

        b.dispose();
        assertEquals(vpnRequestDto, deserialized);
    }

    /**
     * Verifies that Codec.serialize() returns a buffer already flipped in read mode.
     * The serialize method now calls flip() internally, so the caller can read immediately.
     */
    @Test
    public void serializeReturnsFlippedBufferTest() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(100)
                .setMaxPoolSize(1000)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        Codec codec = Codec.bson(poolFactory, 4096, () -> ByteBuffer.allocateDirect(4096), false);

        // Serialize a document (simulates PING-like small request)
        VpnRequestDto<VpnForwardPacketDto> request = VpnRequestDto.wrap(FOO, null);
        request.setTimestamp(Instant.ofEpochMilli(Instant.now().toEpochMilli()));

        DynamicByteBuffer buf = codec.serialize(request);
        ByteBuffer rawBuffer = buf.getBuffer();

        // After serialize(): buffer is already in READ mode (flipped internally)
        assertEquals(0, rawBuffer.position(), "Position should be 0 after serialize (already flipped)");
        int dataSize = rawBuffer.limit();
        assertTrue(dataSize > 0, "Serialized data should have non-zero size");
        assertEquals(dataSize, rawBuffer.remaining(), "Remaining should equal exactly the serialized data size");

        // Verify deserialization works directly without extra flip
        VpnRequestDto<?> deserialized = codec.deserialize(rawBuffer, VpnRequestDto.class);
        assertEquals(request.getCommand(), deserialized.getCommand());
        assertEquals(request.getTimestamp(), deserialized.getTimestamp());
        assertNull(deserialized.getData());

        buf.dispose();
    }

    /**
     * Verifies that repeated serialize -> flip -> deserialize -> dispose cycles with
     * alternating large/small documents produce correct results every time.
     * With pooling, buffers are reused and may contain stale data from previous
     * serializations - flip() ensures only valid data is read.
     */
    @Test
    public void repeatedPooledSerializationRoundTripTest() {
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(100)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        Codec codec = Codec.bson(poolFactory, 4096, () -> ByteBuffer.allocateDirect(4096), false);

        for (int i = 0; i < 200; i++) {
            // Alternate between large (FORWARD_PACKET-like) and small (PING-like) documents
            VpnRequestDto<?> request;
            if (i % 2 == 0) {
                ByteBuffer payload = ByteBuffer.allocateDirect(512);
                for (int j = 0; j < 512; j++) payload.put(j, (byte) (j + i));
                request = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder().packet(payload).build());
            } else {
                request = VpnRequestDto.wrap(FOO, null);
            }
            request.setTimestamp(Instant.ofEpochMilli(1000000L + i));

            DynamicByteBuffer buf = codec.serialize(request);
            VpnRequestDto<?> deserialized = codec.deserialize(buf.getBuffer(), VpnRequestDto.class);

            assertEquals(request.getCommand(), deserialized.getCommand(), "Command mismatch at iteration " + i);
            assertEquals(request.getTimestamp(), deserialized.getTimestamp(), "Timestamp mismatch at iteration " + i);

            buf.dispose();
        }
    }

    @Test
    public void performanceTest() {
        final int WARMUP_ITERATIONS = 5000;
        final int BENCHMARK_ITERATIONS = 10000;

        Binder binder = new Binder(Binder.ClassNameMode.FULL_NAME);
        PoolFactory poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(1000)
                .setBlocking(true)
                .setOutOfPoolTimeout(1000)
                .build();

        BsonObjectWriter bsonObjectWriter = new BsonObjectWriter(poolFactory, 129 * 1024, true);
        BsonObjectReader bsonObjectReader = new BsonObjectReader(poolFactory, 129  * 1024, true, () -> ByteBuffer.allocateDirect(4096));
        bsonObjectReader.setReadBinaryAsByteArray(false);

        VpnRequestDto<VpnForwardPacketDto> requestDto = VpnRequestDto.wrap(FOO, VpnForwardPacketDto.builder()
                .packet(ByteBuffer.allocateDirect(128 * 1024))
                .build());

        DynamicByteBuffer buf = new DynamicByteBuffer(129 * 1024, true);

        // Warm-up phase: allow JIT to optimize hot paths
        System.out.println("Warming up for " + WARMUP_ITERATIONS + " iterations...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            requestDto.getData().getPacket().clear();
            for (int b = 0; b < requestDto.getData().getPacket().limit(); b++) {
                requestDto.getData().getPacket().put(b, (byte) ((byte) b % 128));
            }

            BinaryDocument documentMap = binder.unbind(requestDto);
            bsonObjectWriter.serialize(buf, documentMap);
            BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
            bsonObjectReader.deserialize(buf.getBuffer(), deserialized);
            binder.bind(VpnRequestDto.class, deserialized);
        }
        System.out.println("Warm-up complete. Running benchmark...");

        // Benchmark phase
        List<Long> serializationTime = new ArrayList<>();
        List<Long> deserializationTime = new ArrayList<>();
        BinaryDocument deserialized = new BinaryDocument(new HashMap<>());
        Object request1;

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            requestDto.getData().getPacket().clear();
            for (int b = 0; b < requestDto.getData().getPacket().limit(); b++) {
                requestDto.getData().getPacket().put(b, (byte) ((byte) b % 128));
            }

            BinaryDocument documentMap = binder.unbind(requestDto);
            long delta = System.nanoTime();
            bsonObjectWriter.serialize(buf, documentMap);
            serializationTime.add(System.nanoTime() - delta);
            delta = System.nanoTime();
            deserialized = new BinaryDocument(new HashMap<>());
            bsonObjectReader.deserialize(buf.getBuffer(), deserialized);
            deserializationTime.add(System.nanoTime() - delta);
            request1 = binder.bind(VpnRequestDto.class, deserialized);

            for (int j = 0; j < requestDto.getData().getPacket().limit(); j++) {
                assertEquals(requestDto.getData().getPacket().get(j), ((VpnRequestDto<VpnForwardPacketDto>)request1).getData().getPacket().get(j));
            }
        }
        System.out.println(deserialized);

        List<Long> sortedSerialization = serializationTime.stream().sorted().toList();
        List<Long> sortedDeserialization = deserializationTime.stream().sorted().toList();

        long serMedian = sortedSerialization.get(sortedSerialization.size() / 2);
        long deserMedian = sortedDeserialization.get(sortedDeserialization.size() / 2);

        System.out.println("Serialization median time: %.3fus".formatted(serMedian / 1000.0));
        System.out.println("Deserialization median time: %.3fus".formatted(deserMedian / 1000.0));
    }

}
