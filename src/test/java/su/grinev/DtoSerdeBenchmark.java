package su.grinev;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import su.grinev.bson.BsonObjectReader;
import su.grinev.bson.BsonObjectWriter;
import su.grinev.dto.BlockingsInfoCacheableDto;
import su.grinev.dto.GetBlockingsInfoResultCacheableDto;
import su.grinev.json.JsonReader;
import su.grinev.json.JsonWriter;
import su.grinev.messagepack.MessagePackReader;
import su.grinev.messagepack.MessagePackWriter;
import su.grinev.messagepack.ReaderContext;
import su.grinev.messagepack.WriterContext;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.PoolFactory;
import su.grinev.proto.BlockingsProto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH rewrite of the old DtoBenchmarkTest (DTO part): serialize / deserialize /
 * round-trip across JSON, BSON, MessagePack, LZ4(BSON), LZ4(MsgPack), Protobuf
 * and Java native, for {@code itemCount} ∈ {10, 100}.
 *
 * Run: ./gradlew jmh --args="su.grinev.DtoSerdeBenchmark"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
public class DtoSerdeBenchmark {

    private static final int BUF = 512 * 1024;

    @Param({"10", "100"})
    public int itemCount;

    private JsonWriter jsonWriter;
    private JsonReader jsonReader;
    private BsonObjectWriter bsonWriter;
    private BsonObjectReader bsonReader;
    private MessagePackWriter msgpackWriter;
    private MessagePackReader msgpackReader;
    private LZ4Compressor lz4Compressor;
    private LZ4FastDecompressor lz4Decompressor;
    private Binder binder;

    private GetBlockingsInfoResultCacheableDto dto;
    private Document jsonDocument;
    private BinaryDocument binaryDocument;
    private BlockingsProto.GetBlockingsInfoResultCacheableDto protoDto;

    private byte[] jsonBytes;
    private byte[] bsonBytes;
    private byte[] msgpackBytes;
    private byte[] protoBytes;
    private byte[] javaBytes;
    private byte[] lz4BsonCompressed;
    private byte[] lz4MsgpackCompressed;

    private DynamicByteBuffer bsonReuseBuf;
    private DynamicByteBuffer mpReuseBuf;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        PoolFactory pf = PoolFactory.Builder.builder()
                .setMinPoolSize(100)
                .setMaxPoolSize(2000)
                .setBlocking(true)
                .setOutOfPoolTimeout(1000)
                .build();

        jsonWriter = new JsonWriter(pf.getDisposablePool(() -> new DynamicByteBuffer(BUF, true)));
        jsonReader = new JsonReader();

        bsonWriter = new BsonObjectWriter(pf, BUF, true);
        bsonReader = new BsonObjectReader(pf, BUF, true, () -> ByteBuffer.allocateDirect(4096));

        msgpackWriter = new MessagePackWriter(pf.getPool(WriterContext::new), pf.getPool(() -> new ArrayDeque<>(16)));
        msgpackReader = new MessagePackReader(pf.getPool(ReaderContext::new), pf.getPool(() -> new ArrayDeque<>(64)), true, true);

        LZ4Factory f = LZ4Factory.fastestInstance();
        lz4Compressor = f.fastCompressor();
        lz4Decompressor = f.fastDecompressor();

        binder = new Binder(Binder.ClassNameMode.FULL_NAME);

        dto = createTestDto(itemCount);
        jsonDocument = dtoToDocument(dto);
        binaryDocument = dtoToBinaryDocument(dto);
        protoDto = dtoToProto(dto);

        // pre-serialize reference bytes for the deserialize benchmarks
        DynamicByteBuffer jb = jsonWriter.serialize(jsonDocument);
        jb.flip();
        jsonBytes = new byte[jb.getBuffer().remaining()];
        jb.getBuffer().get(jsonBytes);
        jb.dispose();

        DynamicByteBuffer bb = new DynamicByteBuffer(BUF, true);
        bsonWriter.serialize(bb, binaryDocument);
        bsonBytes = new byte[bb.getBuffer().remaining()];
        bb.getBuffer().get(bsonBytes);

        DynamicByteBuffer mb = new DynamicByteBuffer(BUF, true);
        msgpackWriter.serialize(mb, binaryDocument);
        msgpackBytes = new byte[mb.getBuffer().remaining()];
        mb.getBuffer().get(msgpackBytes);

        protoBytes = protoDto.toByteArray();

        byte[] dst = new byte[lz4Compressor.maxCompressedLength(bsonBytes.length)];
        int l = lz4Compressor.compress(bsonBytes, 0, bsonBytes.length, dst, 0, dst.length);
        lz4BsonCompressed = Arrays.copyOf(dst, l);

        dst = new byte[lz4Compressor.maxCompressedLength(msgpackBytes.length)];
        l = lz4Compressor.compress(msgpackBytes, 0, msgpackBytes.length, dst, 0, dst.length);
        lz4MsgpackCompressed = Arrays.copyOf(dst, l);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(dto);
        oos.close();
        javaBytes = baos.toByteArray();

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
        bsonReuseBuf.getBuffer().clear();
        bsonWriter.serialize(bsonReuseBuf, binaryDocument);
        return bsonReuseBuf.getBuffer().remaining();
    }

    @Benchmark
    public int msgpack_serialize() {
        mpReuseBuf.getBuffer().clear();
        msgpackWriter.serialize(mpReuseBuf, binaryDocument);
        return mpReuseBuf.getBuffer().remaining();
    }

    @Benchmark
    public int lz4Bson_serialize() {
        bsonReuseBuf.getBuffer().clear();
        bsonWriter.serialize(bsonReuseBuf, binaryDocument);
        int len = bsonReuseBuf.getBuffer().remaining();
        byte[] raw = new byte[len];
        bsonReuseBuf.getBuffer().get(raw);
        byte[] dst = new byte[lz4Compressor.maxCompressedLength(len)];
        return lz4Compressor.compress(raw, 0, len, dst, 0, dst.length);
    }

    @Benchmark
    public int lz4Msgpack_serialize() {
        mpReuseBuf.getBuffer().clear();
        msgpackWriter.serialize(mpReuseBuf, binaryDocument);
        int len = mpReuseBuf.getBuffer().remaining();
        byte[] raw = new byte[len];
        mpReuseBuf.getBuffer().get(raw);
        byte[] dst = new byte[lz4Compressor.maxCompressedLength(len)];
        return lz4Compressor.compress(raw, 0, len, dst, 0, dst.length);
    }

    @Benchmark
    public byte[] proto_serialize() {
        return protoDto.toByteArray();
    }

    @Benchmark
    public byte[] java_serialize() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(dto);
        out.close();
        return bos.toByteArray();
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
    public BinaryDocument lz4Bson_deserialize() {
        byte[] dec = new byte[bsonBytes.length];
        lz4Decompressor.decompress(lz4BsonCompressed, 0, dec, 0, bsonBytes.length);
        BinaryDocument d = new BinaryDocument(new HashMap<>());
        bsonReader.deserialize(ByteBuffer.wrap(dec), d);
        return d;
    }

    @Benchmark
    public BinaryDocument lz4Msgpack_deserialize() {
        byte[] dec = new byte[msgpackBytes.length];
        lz4Decompressor.decompress(lz4MsgpackCompressed, 0, dec, 0, msgpackBytes.length);
        BinaryDocument d = new BinaryDocument(new HashMap<>());
        msgpackReader.deserialize(ByteBuffer.wrap(dec), d);
        return d;
    }

    @Benchmark
    public Object proto_deserialize() throws Exception {
        return BlockingsProto.GetBlockingsInfoResultCacheableDto.parseFrom(protoBytes);
    }

    @Benchmark
    public Object java_deserialize() throws Exception {
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(javaBytes));
        Object o = ois.readObject();
        ois.close();
        return o;
    }

    // ---------------------------------------------------------------- round-trip

    @Benchmark
    public Object bson_roundtrip() {
        BinaryDocument doc = binder.unbind(dto);
        bsonReuseBuf.getBuffer().clear();
        bsonWriter.serialize(bsonReuseBuf, doc);
        BinaryDocument deser = new BinaryDocument(new HashMap<>());
        bsonReader.deserialize(bsonReuseBuf.getBuffer(), deser);
        return binder.bind(GetBlockingsInfoResultCacheableDto.class, deser);
    }

    @Benchmark
    public Object msgpack_roundtrip() {
        BinaryDocument doc = binder.unbind(dto);
        mpReuseBuf.getBuffer().clear();
        msgpackWriter.serialize(mpReuseBuf, doc);
        BinaryDocument deser = new BinaryDocument(new HashMap<>());
        msgpackReader.deserialize(mpReuseBuf.getBuffer(), deser);
        return binder.bind(GetBlockingsInfoResultCacheableDto.class, deser);
    }

    @Benchmark
    public Object proto_roundtrip() throws Exception {
        byte[] pb = dtoToProto(dto).toByteArray();
        return BlockingsProto.GetBlockingsInfoResultCacheableDto.parseFrom(pb);
    }

    @Benchmark
    public Object java_roundtrip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(dto);
        out.close();
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
        Object o = ois.readObject();
        ois.close();
        return o;
    }

    // ---------------------------------------------------------------- fixtures

    private GetBlockingsInfoResultCacheableDto createTestDto(int blockingsCount) {
        List<BlockingsInfoCacheableDto> blockings = new ArrayList<>();
        for (int i = 0; i < blockingsCount; i++) {
            blockings.add(new BlockingsInfoCacheableDto(
                    i + 1,
                    "2024-01-" + String.format("%02d", (i % 28) + 1),
                    "Federal Tax Service Department #" + (i % 10),
                    "Tax debt collection order #" + (10000 + i),
                    100000L + i * 1000,
                    i % 2 == 0 ? "FULL" : "PARTIAL"
            ));
        }
        return new GetBlockingsInfoResultCacheableDto("CUST-12345678901234", "40817810099910004567", blockings);
    }

    private Document dtoToDocument(GetBlockingsInfoResultCacheableDto dto) {
        Map<String, Object> root = new HashMap<>();
        root.put("customerId", dto.getCustomerId());
        root.put("accountNumber", dto.getAccountNumber());
        List<Map<String, Object>> blockingsList = new ArrayList<>();
        for (BlockingsInfoCacheableDto blocking : dto.getBlockingsInfo()) {
            Map<String, Object> map = new HashMap<>();
            map.put("number", blocking.getNumber());
            map.put("date", blocking.getDate());
            map.put("authorityName", blocking.getAuthorityName());
            map.put("blockReason", blocking.getBlockReason());
            map.put("blockAmount", blocking.getBlockAmount());
            map.put("blockType", blocking.getBlockType());
            blockingsList.add(map);
        }
        root.put("blockingsInfo", blockingsList);
        return new Document(root);
    }

    private BinaryDocument dtoToBinaryDocument(GetBlockingsInfoResultCacheableDto dto) {
        Map<Object, Object> root = new HashMap<>();
        root.put(2, dto.getCustomerId());
        root.put(0, dto.getAccountNumber());
        List<Map<Integer, Object>> blockingsList = new ArrayList<>();
        for (BlockingsInfoCacheableDto blocking : dto.getBlockingsInfo()) {
            Map<Integer, Object> map = new HashMap<>();
            map.put(5, blocking.getNumber());
            map.put(4, blocking.getDate());
            map.put(0, blocking.getAuthorityName());
            map.put(2, blocking.getBlockReason());
            map.put(1, blocking.getBlockAmount());
            map.put(3, blocking.getBlockType());
            blockingsList.add(map);
        }
        root.put(1, blockingsList);
        return new BinaryDocument(root);
    }

    private BlockingsProto.GetBlockingsInfoResultCacheableDto dtoToProto(GetBlockingsInfoResultCacheableDto dto) {
        BlockingsProto.GetBlockingsInfoResultCacheableDto.Builder builder =
                BlockingsProto.GetBlockingsInfoResultCacheableDto.newBuilder()
                        .setCustomerId(dto.getCustomerId())
                        .setAccountNumber(dto.getAccountNumber());
        for (BlockingsInfoCacheableDto blocking : dto.getBlockingsInfo()) {
            builder.addBlockingsInfo(
                    BlockingsProto.BlockingsInfoCacheableDto.newBuilder()
                            .setNumber(blocking.getNumber())
                            .setDate(blocking.getDate())
                            .setAuthorityName(blocking.getAuthorityName())
                            .setBlockReason(blocking.getBlockReason())
                            .setBlockAmount(blocking.getBlockAmount())
                            .setBlockType(blocking.getBlockType())
                            .build());
        }
        return builder.build();
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(DtoSerdeBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
