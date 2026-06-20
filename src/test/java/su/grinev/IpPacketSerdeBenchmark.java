package su.grinev;

import com.google.protobuf.ByteString;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import su.grinev.bson.BsonObjectReader;
import su.grinev.bson.BsonObjectWriter;
import su.grinev.json.JsonReader;
import su.grinev.json.JsonWriter;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.MessagePackWriter;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.WriterContext;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.PoolFactory;
import su.grinev.proto.PayloadProto;
import su.grinev.test.VpnForwardPacketDto;
import su.grinev.test.VpnRequestDto;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static su.grinev.test.Command.FOO;

/**
 * JMH rewrite of the old DtoBenchmarkTest IP-packet case: serialize / deserialize
 * a {@code VpnRequestDto} carrying a 1500-byte pseudo-random packet across JSON
 * (base64), BSON, MessagePack and Protobuf.
 *
 * Run: ./gradlew jmh --args="su.grinev.IpPacketSerdeBenchmark"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
public class IpPacketSerdeBenchmark {

    private static final int PACKET_SIZE = 1500;
    private static final int BUF = 4096;

    private JsonWriter jsonWriter;
    private JsonReader jsonReader;
    private BsonObjectWriter bsonWriter;
    private BsonObjectReader bsonReader;
    private MessagePackWriter msgpackWriter;
    private MessagePackReader msgpackReader;

    private ByteBuffer packetBuf;
    private Document jsonDocument;
    private BinaryDocument binaryDoc;
    private PayloadProto.PayloadRequest protoDto;

    private byte[] jsonBytes;
    private byte[] bsonBytes;
    private byte[] msgpackBytes;
    private byte[] protoBytes;

    private DynamicByteBuffer bsonReuseBuf;
    private DynamicByteBuffer mpReuseBuf;

    @Setup(Level.Trial)
    public void setup() {
        PoolFactory pf = PoolFactory.Builder.builder()
                .setMinPoolSize(100)
                .setMaxPoolSize(2000)
                .setBlocking(true)
                .setOutOfPoolTimeout(1000)
                .build();

        jsonWriter = new JsonWriter(pf.getDisposablePool(() -> new DynamicByteBuffer(BUF, true)));
        jsonReader = new JsonReader();
        bsonWriter = new BsonObjectWriter(pf, BUF, true);
        bsonReader = new BsonObjectReader(pf, BUF, true, () -> ByteBuffer.allocateDirect(BUF));
        bsonReader.setReadBinaryAsByteArray(false);
        msgpackWriter = new MessagePackWriter(pf.getPool(WriterContext::new), pf.getPool(() -> new ArrayDeque<>(16)));
        msgpackReader = new MessagePackReader(pf.getPool(ReaderContext::new), pf.getPool(() -> new ArrayDeque<>(64)), true, true);

        Binder binder = new Binder(Binder.ClassNameMode.FULL_NAME);

        byte[] randomPayload = new byte[PACKET_SIZE];
        ThreadLocalRandom.current().nextBytes(randomPayload);
        packetBuf = ByteBuffer.allocateDirect(PACKET_SIZE);
        packetBuf.put(randomPayload);
        packetBuf.flip();

        VpnRequestDto<VpnForwardPacketDto> dto = VpnRequestDto.wrap(FOO,
                VpnForwardPacketDto.builder().packet(packetBuf).build());
        dto.setTimestamp(null);

        String base64Payload = Base64.getEncoder().encodeToString(randomPayload);
        Map<String, Object> jsonPacketMap = new HashMap<>();
        jsonPacketMap.put("packet", base64Payload);
        Map<String, Object> jsonRootMap = new HashMap<>();
        jsonRootMap.put("command", "FOO");
        jsonRootMap.put("protocolVersion", "0.1");
        jsonRootMap.put("data", jsonPacketMap);
        jsonDocument = new Document(jsonRootMap);

        protoDto = PayloadProto.PayloadRequest.newBuilder()
                .setCommand("FOO")
                .setData(PayloadProto.PayloadData.newBuilder()
                        .setPacket(ByteString.copyFrom(randomPayload))
                        .build())
                .build();

        binaryDoc = binder.unbind(dto);

        DynamicByteBuffer jb = jsonWriter.serialize(jsonDocument);
        jb.flip();
        jsonBytes = new byte[jb.getBuffer().remaining()];
        jb.getBuffer().get(jsonBytes);
        jb.dispose();

        packetBuf.rewind();
        DynamicByteBuffer bb = new DynamicByteBuffer(BUF, true);
        bsonWriter.serialize(bb, binaryDoc);
        bsonBytes = new byte[bb.getBuffer().remaining()];
        bb.getBuffer().get(bsonBytes);

        packetBuf.rewind();
        DynamicByteBuffer mb = new DynamicByteBuffer(BUF, true);
        msgpackWriter.serialize(mb, binaryDoc);
        msgpackBytes = new byte[mb.getBuffer().remaining()];
        mb.getBuffer().get(msgpackBytes);

        protoBytes = protoDto.toByteArray();

        bsonReuseBuf = new DynamicByteBuffer(BUF, true);
        mpReuseBuf = new DynamicByteBuffer(BUF, true);
    }

    // ---------------------------------------------------------------- serialize

    @Benchmark
    public int json_serialize() {
        DynamicByteBuffer b = jsonWriter.serialize(jsonDocument);
        int n = b.getBuffer().position();
        b.dispose();
        return n;
    }

    @Benchmark
    public int bson_serialize() {
        packetBuf.rewind();
        bsonReuseBuf.getBuffer().clear();
        bsonWriter.serialize(bsonReuseBuf, binaryDoc);
        return bsonReuseBuf.getBuffer().remaining();
    }

    @Benchmark
    public int msgpack_serialize() {
        packetBuf.rewind();
        mpReuseBuf.getBuffer().clear();
        msgpackWriter.serialize(mpReuseBuf, binaryDoc);
        return mpReuseBuf.getBuffer().remaining();
    }

    @Benchmark
    public byte[] proto_serialize() {
        return protoDto.toByteArray();
    }

    // -------------------------------------------------------------- deserialize

    @Benchmark
    public Object json_deserialize() {
        return jsonReader.deserialize(jsonBytes);
    }

    @Benchmark
    public BinaryDocument bson_deserialize() {
        BinaryDocument d = new BinaryDocument(new HashMap<>());
        bsonReader.deserialize(ByteBuffer.wrap(bsonBytes), d);
        return d;
    }

    @Benchmark
    public BinaryDocument msgpack_deserialize() {
        BinaryDocument d = new BinaryDocument(new HashMap<>());
        msgpackReader.deserialize(ByteBuffer.wrap(msgpackBytes), d);
        return d;
    }

    @Benchmark
    public Object proto_deserialize() throws Exception {
        return PayloadProto.PayloadRequest.parseFrom(protoBytes);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(IpPacketSerdeBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
