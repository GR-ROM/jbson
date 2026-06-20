package su.grinev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.grinev.messagepack.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import su.grinev.pool.DynamicByteBuffer;
import su.grinev.pool.FastPool;
import su.grinev.pool.PoolFactory;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-threaded allocation profiling and chaos tests for MessagePack serialization.
 * Simulates VPN hot path: FORWARD_PACKET serialize/deserialize under contention.
 *
 * Run with JFR:
 *   ./gradlew test --tests "su.grinev.MessagePackAllocationProfilingTest" \
 *     -Djvm.args="-XX:StartFlightRecording=filename=alloc-test.jfr,settings=profile,duration=30s"
 *
 * Or just run to see GC-based allocation estimates printed to stdout.
 */
public class MessagePackAllocationProfilingTest {

    // --- Protocol constants matching VPN ForwardPacketFastPath ---
    private static final int TAG_VER = 0;
    private static final int TAG_TIMESTAMP = 1;
    private static final int TAG_PAYLOAD = 2;
    private static final int TAG_SEQ = 0;
    private static final int TAG_COMMAND = 1;
    private static final int TAG_RESPONSE_REQUIRED = 2;
    private static final int TAG_DATA = 3;
    private static final int TAG_PACKET = 0;
    private static final int TAG_DISCRIMINATOR = 1488;

    private static final String FORWARD_PACKET_COMMAND = "FORWARD_PACKET";
    private static final String VERSION = "0.1";
    private static final String REQUEST_DTO_DISCRIMINATOR = "RequestDto";
    private static final String FORWARD_PACKET_DTO_DISCRIMINATOR = "VpnForwardPacketRequestDto";

    private PoolFactory poolFactory;
    private MessagePackWriter writer;
    private MessagePackReader reader;

    @BeforeEach
    void setUp() {
        poolFactory = PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(200)
                .setOutOfPoolTimeout(1000)
                .setBlocking(true)
                .build();

        FastPool<WriterContext> writerCtxPool = poolFactory.getPool(WriterContext::new);
        FastPool<ArrayDeque<WriterContext>> writerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(16));
        FastPool<ReaderContext> readerCtxPool = poolFactory.getPool(ReaderContext::new);
        FastPool<ArrayDeque<ReaderContext>> readerStackPool = poolFactory.getPool(() -> new ArrayDeque<>(64));

        writer = new MessagePackWriter(writerCtxPool, writerStackPool);
        reader = new MessagePackReader(readerCtxPool, readerStackPool, true, true);
    }

    /**
     * Build a FORWARD_PACKET document matching the real VPN protocol structure.
     */
    private BinaryDocument buildForwardPacketDoc(ByteBuffer ipPacket) {
        CompactMap dataMap = new CompactMap();
        dataMap.put(TAG_PACKET, ipPacket);

        CompactMap payloadMap = new CompactMap();
        payloadMap.put(TAG_SEQ, 0);
        payloadMap.put(TAG_COMMAND, FORWARD_PACKET_COMMAND);
        payloadMap.put(TAG_RESPONSE_REQUIRED, false);
        payloadMap.put(TAG_DISCRIMINATOR, FORWARD_PACKET_DTO_DISCRIMINATOR);
        payloadMap.put(TAG_DATA, dataMap);

        CompactMap rootMap = new CompactMap();
        rootMap.put(TAG_VER, VERSION);
        rootMap.put(TAG_TIMESTAMP, Instant.now());
        rootMap.put(TAG_DISCRIMINATOR, REQUEST_DTO_DISCRIMINATOR);
        rootMap.put(TAG_PAYLOAD, payloadMap);

        return new BinaryDocument(rootMap);
    }

    /**
     * Create a fake 1500-byte IP packet.
     */
    private ByteBuffer createIpPacket() {
        byte[] data = new byte[1500];
        ThreadLocalRandom.current().nextBytes(data);
        // Minimal IPv4 header
        data[0] = 0x45; // version=4, IHL=5
        data[9] = 6;    // TCP
        // src IP = 10.0.0.2
        data[12] = 10; data[13] = 0; data[14] = 0; data[15] = 2;
        // dst IP = 8.8.8.8
        data[16] = 8; data[17] = 8; data[18] = 8; data[19] = 8;
        return ByteBuffer.wrap(data);
    }

    // ==========================================================================
    //  Single-thread steady-state allocation test
    // ==========================================================================

    @Test
    void steadyStateAllocations() {
        System.out.println("=== Steady-State Allocation Test (single thread) ===");

        DynamicByteBuffer serBuf = new DynamicByteBuffer(8192, true);
        CompactMap deserMap = new CompactMap();
        BinaryDocument deserDoc = new BinaryDocument(deserMap);

        int warmupOps = 10_000;
        int measureOps = 100_000;

        // Warmup — populates all caches
        for (int i = 0; i < warmupOps; i++) {
            ByteBuffer pkt = createIpPacket();
            BinaryDocument doc = buildForwardPacketDoc(pkt);
            writer.serialize(serBuf, doc);
            ByteBuffer serialized = serBuf.getBuffer();

            deserMap.clear();
            reader.deserialize(serialized, deserDoc);
        }

        // Force GC, measure baseline
        System.gc();
        System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        long allocBefore = getAllocatedBytes();
        long gcCountBefore = getGcCount();
        long startNanos = System.nanoTime();

        for (int i = 0; i < measureOps; i++) {
            ByteBuffer pkt = createIpPacket();
            BinaryDocument doc = buildForwardPacketDoc(pkt);
            writer.serialize(serBuf, doc);
            ByteBuffer serialized = serBuf.getBuffer();

            deserMap.clear();
            reader.deserialize(serialized, deserDoc);

            // Verify correctness every 10000th iteration
            if (i % 10000 == 0) {
                Object payloadObj = deserDoc.getDocumentMap().get(TAG_PAYLOAD);
                assertInstanceOf(Map.class, payloadObj);
                @SuppressWarnings("unchecked")
                Map<Object, Object> payload = (Map<Object, Object>) payloadObj;
                assertEquals(FORWARD_PACKET_COMMAND, payload.get(TAG_COMMAND));
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        long allocAfter = getAllocatedBytes();
        long gcCountAfter = getGcCount();

        double opsPerSec = measureOps / (elapsedNanos / 1_000_000_000.0);
        long totalAlloc = allocAfter - allocBefore;
        long bytesPerOp = totalAlloc / measureOps;
        long gcsDuringTest = gcCountAfter - gcCountBefore;

        System.out.printf("  Operations:      %,d%n", measureOps);
        System.out.printf("  Throughput:      %,.0f ops/sec%n", opsPerSec);
        System.out.printf("  Total allocated: %,d bytes (%.1f MB)%n", totalAlloc, totalAlloc / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", bytesPerOp);
        System.out.printf("  GCs during test: %d%n", gcsDuringTest);
        System.out.println();

        // Note: createIpPacket() allocates ~1500 bytes + wrapper per call,
        // plus buildForwardPacketDoc creates CompactMaps.
        // The SERIALIZATION itself should be near-zero alloc after warmup.
    }

    // ==========================================================================
    //  Serialize-only allocation test (no document creation noise)
    // ==========================================================================

    @Test
    void serializeOnlyAllocations() {
        System.out.println("=== Serialize-Only Allocation Test ===");

        DynamicByteBuffer serBuf = new DynamicByteBuffer(8192, true);
        ByteBuffer pkt = createIpPacket();
        BinaryDocument doc = buildForwardPacketDoc(pkt);

        int warmup = 10_000;
        int measure = 500_000;

        for (int i = 0; i < warmup; i++) {
            writer.serialize(serBuf, doc);
        }

        System.gc(); System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        long allocBefore = getAllocatedBytes();
        long startNanos = System.nanoTime();

        for (int i = 0; i < measure; i++) {
            writer.serialize(serBuf, doc);
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        long totalAlloc = getAllocatedBytes() - allocBefore;

        System.out.printf("  Operations:      %,d%n", measure);
        System.out.printf("  Throughput:      %,.0f ops/sec%n", measure / (elapsedNanos / 1e9));
        System.out.printf("  Total allocated: %,d bytes (%.2f MB)%n", totalAlloc, totalAlloc / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAlloc / measure);
        System.out.println();
    }

    // ==========================================================================
    //  Deserialize-only allocation test
    // ==========================================================================

    @Test
    void deserializeOnlyAllocationsTimestampAsLong() {
        System.out.println("=== Deserialize-Only Allocation Test (timestampAsEpochMillis=true) ===");

        reader.setTimestampAsEpochMillis(true);

        DynamicByteBuffer serBuf = new DynamicByteBuffer(8192, true);
        ByteBuffer pkt = createIpPacket();
        BinaryDocument doc = buildForwardPacketDoc(pkt);
        writer.serialize(serBuf, doc);

        ByteBuffer serialized = serBuf.getBuffer();
        byte[] serializedBytes = new byte[serialized.remaining()];
        serialized.get(serializedBytes);

        // Pre-allocate a reusable HeapByteBuffer to avoid ByteBuffer.wrap() noise
        ByteBuffer reusableBuf = ByteBuffer.wrap(serializedBytes);

        CompactMap deserMap = new CompactMap();
        BinaryDocument deserDoc = new BinaryDocument(deserMap);

        int warmup = 10_000;
        int measure = 500_000;

        for (int i = 0; i < warmup; i++) {
            deserMap.clear();
            reusableBuf.position(0).limit(serializedBytes.length);
            reader.deserialize(reusableBuf, deserDoc);
        }

        System.gc(); System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        long allocBefore = getAllocatedBytes();
        long startNanos = System.nanoTime();

        for (int i = 0; i < measure; i++) {
            deserMap.clear();
            reusableBuf.position(0).limit(serializedBytes.length);
            reader.deserialize(reusableBuf, deserDoc);
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        long totalAlloc = getAllocatedBytes() - allocBefore;

        Object ts = deserDoc.getDocumentMap().get(TAG_TIMESTAMP);
        assertInstanceOf(Long.class, ts, "Timestamp should be Long with flag on");

        System.out.printf("  Operations:      %,d%n", measure);
        System.out.printf("  Throughput:      %,.0f ops/sec%n", measure / (elapsedNanos / 1e9));
        System.out.printf("  Total allocated: %,d bytes (%.2f MB)%n", totalAlloc, totalAlloc / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAlloc / measure);
        System.out.println();

        reader.setTimestampAsEpochMillis(false);
    }

    @Test
    void deserializeOnlyAllocationsNoWrap() {
        System.out.println("=== Deserialize-Only (reusable buffer, timestampAsEpochMillis=true) ===");

        reader.setTimestampAsEpochMillis(true);

        DynamicByteBuffer serBuf = new DynamicByteBuffer(8192, true);
        ByteBuffer pkt = createIpPacket();
        BinaryDocument doc = buildForwardPacketDoc(pkt);
        writer.serialize(serBuf, doc);

        // Copy to heap buffer for reuse
        ByteBuffer serialized = serBuf.getBuffer();
        byte[] serializedBytes = new byte[serialized.remaining()];
        serialized.get(serializedBytes);
        ByteBuffer reusableBuf = ByteBuffer.wrap(serializedBytes);

        CompactMap deserMap = new CompactMap();
        BinaryDocument deserDoc = new BinaryDocument(deserMap);

        int warmup = 10_000;
        int measure = 500_000;

        for (int i = 0; i < warmup; i++) {
            deserMap.clear();
            reusableBuf.position(0).limit(serializedBytes.length);
            reader.deserialize(reusableBuf, deserDoc);
        }

        System.gc(); System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        long allocBefore = getAllocatedBytes();

        for (int i = 0; i < measure; i++) {
            deserMap.clear();
            reusableBuf.position(0).limit(serializedBytes.length);
            reader.deserialize(reusableBuf, deserDoc);
        }

        long totalAlloc = getAllocatedBytes() - allocBefore;

        System.out.printf("  Operations:      %,d%n", measure);
        System.out.printf("  Total allocated: %,d bytes (%.2f MB)%n", totalAlloc, totalAlloc / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAlloc / measure);
        System.out.println("  (no ByteBuffer.wrap noise — pure deserializer allocations)");
        System.out.println();

        reader.setTimestampAsEpochMillis(false);
    }

    @Test
    void deserializeOnlyAllocations() {
        System.out.println("=== Deserialize-Only Allocation Test (Instant, baseline) ===");

        DynamicByteBuffer serBuf = new DynamicByteBuffer(8192, true);
        ByteBuffer pkt = createIpPacket();
        BinaryDocument doc = buildForwardPacketDoc(pkt);
        writer.serialize(serBuf, doc);

        // Capture serialized bytes for replay
        ByteBuffer serialized = serBuf.getBuffer();
        byte[] serializedBytes = new byte[serialized.remaining()];
        serialized.get(serializedBytes);

        CompactMap deserMap = new CompactMap();
        BinaryDocument deserDoc = new BinaryDocument(deserMap);

        int warmup = 10_000;
        int measure = 500_000;

        for (int i = 0; i < warmup; i++) {
            deserMap.clear();
            reader.deserialize(ByteBuffer.wrap(serializedBytes), deserDoc);
        }

        System.gc(); System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        long allocBefore = getAllocatedBytes();
        long startNanos = System.nanoTime();

        for (int i = 0; i < measure; i++) {
            deserMap.clear();
            reader.deserialize(ByteBuffer.wrap(serializedBytes), deserDoc);
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        long totalAlloc = getAllocatedBytes() - allocBefore;

        System.out.printf("  Operations:      %,d%n", measure);
        System.out.printf("  Throughput:      %,.0f ops/sec%n", measure / (elapsedNanos / 1e9));
        System.out.printf("  Total allocated: %,d bytes (%.2f MB)%n", totalAlloc, totalAlloc / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAlloc / measure);
        System.out.println();
    }

    // ==========================================================================
    //  Multi-threaded contention test (simulates sslWorker + TunHandler)
    // ==========================================================================

    @Test
    void multiThreadContentionTest() throws Exception {
        System.out.println("=== Multi-Thread Contention Test (4 sslWorkers + 1 TunHandler) ===");

        int sslWorkerCount = 4;
        int tunHandlerCount = 1;
        int totalThreads = sslWorkerCount + tunHandlerCount;
        int opsPerThread = 100_000;
        int warmupPerThread = 5_000;

        CountDownLatch warmupLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicLong totalOps = new AtomicLong();
        AtomicBoolean failed = new AtomicBoolean(false);

        // Serialize a reference packet for deserialization threads
        DynamicByteBuffer refBuf = new DynamicByteBuffer(8192, true);
        ByteBuffer refPkt = createIpPacket();
        BinaryDocument refDoc = buildForwardPacketDoc(refPkt);
        writer.serialize(refBuf, refDoc);
        byte[] serializedRef = new byte[refBuf.getBuffer().remaining()];
        refBuf.getBuffer().get(serializedRef);

        List<Thread> threads = new ArrayList<>();

        // sslWorker threads — deserialize inbound packets
        for (int t = 0; t < sslWorkerCount; t++) {
            final int threadId = t;
            Thread thread = Thread.ofPlatform().name("sslWorker-" + t).start(() -> {
                try {
                    CompactMap localMap = new CompactMap();
                    BinaryDocument localDoc = new BinaryDocument(localMap);

                    // Warmup
                    for (int i = 0; i < warmupPerThread; i++) {
                        localMap.clear();
                        reader.deserialize(ByteBuffer.wrap(serializedRef), localDoc);
                    }

                    warmupLatch.countDown();
                    startLatch.await();

                    for (int i = 0; i < opsPerThread; i++) {
                        localMap.clear();
                        reader.deserialize(ByteBuffer.wrap(serializedRef), localDoc);

                        // Verify correctness periodically
                        if (i % 25000 == 0) {
                            @SuppressWarnings("unchecked")
                            Map<Object, Object> payload = (Map<Object, Object>) localDoc.getDocumentMap().get(TAG_PAYLOAD);
                            if (!FORWARD_PACKET_COMMAND.equals(payload.get(TAG_COMMAND))) {
                                System.err.printf("sslWorker-%d: CORRUPT DATA at iteration %d!%n", threadId, i);
                                failed.set(true);
                                return;
                            }
                        }
                        totalOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.printf("sslWorker-%d: EXCEPTION: %s%n", threadId, e.getMessage());
                    e.printStackTrace();
                    failed.set(true);
                } finally {
                    doneLatch.countDown();
                }
            });
            threads.add(thread);
        }

        // TunHandler thread — serialize outbound packets
        for (int t = 0; t < tunHandlerCount; t++) {
            final int threadId = t;
            Thread thread = Thread.ofPlatform().name("TunHandler-" + t).start(() -> {
                try {
                    DynamicByteBuffer localBuf = new DynamicByteBuffer(8192, true);
                    ByteBuffer pkt = createIpPacket();
                    BinaryDocument localDoc = buildForwardPacketDoc(pkt);

                    // Warmup
                    for (int i = 0; i < warmupPerThread; i++) {
                        writer.serialize(localBuf, localDoc);
                    }

                    warmupLatch.countDown();
                    startLatch.await();

                    for (int i = 0; i < opsPerThread; i++) {
                        writer.serialize(localBuf, localDoc);
                        totalOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.printf("TunHandler-%d: EXCEPTION: %s%n", threadId, e.getMessage());
                    e.printStackTrace();
                    failed.set(true);
                } finally {
                    doneLatch.countDown();
                }
            });
            threads.add(thread);
        }

        // Wait for warmup
        warmupLatch.await(30, TimeUnit.SECONDS);

        System.gc(); System.gc();
        Thread.sleep(200);

        long allocBefore = getAllocatedBytes();
        long startNanos = System.nanoTime();

        // GO!
        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "Threads did not finish — possible deadlock!");

        long elapsedNanos = System.nanoTime() - startNanos;
        long totalAllocBytes = getAllocatedBytes() - allocBefore;
        long ops = totalOps.get();

        assertFalse(failed.get(), "Data corruption or exception detected!");

        System.out.printf("  Threads:         %d sslWorkers + %d TunHandler%n", sslWorkerCount, tunHandlerCount);
        System.out.printf("  Total ops:       %,d%n", ops);
        System.out.printf("  Throughput:      %,.0f ops/sec%n", ops / (elapsedNanos / 1e9));
        System.out.printf("  Total allocated: %,d bytes (%.1f MB)%n", totalAllocBytes, totalAllocBytes / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAllocBytes / Math.max(ops, 1));
        System.out.printf("  Wall time:       %.1f ms%n", elapsedNanos / 1e6);
        System.out.println();

        for (Thread t : threads) t.join(5000);
    }

    // ==========================================================================
    //  Virtual thread chaos test (simulates many short-lived sslWorkers)
    // ==========================================================================

    @Test
    void virtualThreadChaosTest() throws Exception {
        System.out.println("=== Virtual Thread Chaos Test (100 virtual threads, short-lived) ===");

        int threadCount = 100;
        int opsPerThread = 10_000;
        AtomicLong totalOps = new AtomicLong();
        AtomicBoolean failed = new AtomicBoolean(false);

        // Serialize reference packet
        DynamicByteBuffer refBuf = new DynamicByteBuffer(8192, true);
        writer.serialize(refBuf, buildForwardPacketDoc(createIpPacket()));
        byte[] serializedRef = new byte[refBuf.getBuffer().remaining()];
        refBuf.getBuffer().get(serializedRef);

        // Warmup on main thread
        CompactMap warmupMap = new CompactMap();
        BinaryDocument warmupDoc = new BinaryDocument(warmupMap);
        for (int i = 0; i < 1000; i++) {
            warmupMap.clear();
            reader.deserialize(ByteBuffer.wrap(serializedRef), warmupDoc);
        }

        System.gc(); System.gc();
        Thread.sleep(200);

        long allocBefore = getAllocatedBytes();
        long startNanos = System.nanoTime();

        // Launch virtual threads — each one creates fresh ThreadLocal caches
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        CompactMap localMap = new CompactMap();
                        BinaryDocument localDoc = new BinaryDocument(localMap);
                        DynamicByteBuffer localBuf = new DynamicByteBuffer(8192, false);

                        for (int i = 0; i < opsPerThread; i++) {
                            // Alternate serialize/deserialize
                            if (i % 2 == 0) {
                                localMap.clear();
                                reader.deserialize(ByteBuffer.wrap(serializedRef), localDoc);
                            } else {
                                ByteBuffer pkt = createIpPacket();
                                writer.serialize(localBuf, buildForwardPacketDoc(pkt));
                            }
                            totalOps.incrementAndGet();
                        }

                        // Final correctness check
                        localMap.clear();
                        reader.deserialize(ByteBuffer.wrap(serializedRef), localDoc);
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> payload = (Map<Object, Object>) localDoc.getDocumentMap().get(TAG_PAYLOAD);
                        if (!FORWARD_PACKET_COMMAND.equals(payload.get(TAG_COMMAND))) {
                            System.err.printf("vthread-%d: CORRUPT DATA!%n", threadId);
                            failed.set(true);
                        }
                    } catch (Exception e) {
                        System.err.printf("vthread-%d: EXCEPTION: %s%n", threadId, e.getMessage());
                        failed.set(true);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        long totalAllocBytes = getAllocatedBytes() - allocBefore;
        long ops = totalOps.get();

        assertFalse(failed.get(), "Data corruption detected in virtual threads!");

        System.out.printf("  Virtual threads: %d (each does %,d ops)%n", threadCount, opsPerThread);
        System.out.printf("  Total ops:       %,d%n", ops);
        System.out.printf("  Throughput:      %,.0f ops/sec%n", ops / (elapsedNanos / 1e9));
        System.out.printf("  Total allocated: %,d bytes (%.1f MB)%n", totalAllocBytes, totalAllocBytes / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAllocBytes / Math.max(ops, 1));
        System.out.printf("  Wall time:       %.1f ms%n", elapsedNanos / 1e6);
        System.out.println();
    }

    // ==========================================================================
    //  Long-running stability test (detect 10-minute deadlock scenario)
    // ==========================================================================

    @Test
    void longRunningStabilityTest() throws Exception {
        System.out.println("=== Long-Running Stability Test (30s, mixed read/write) ===");

        int threadCount = 6; // 4 sslWorkers + 2 TunHandlers
        long durationMs = 30_000;
        AtomicLong totalOps = new AtomicLong();
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);

        DynamicByteBuffer refBuf = new DynamicByteBuffer(8192, true);
        writer.serialize(refBuf, buildForwardPacketDoc(createIpPacket()));
        byte[] serializedRef = new byte[refBuf.getBuffer().remaining()];
        refBuf.getBuffer().get(serializedRef);

        List<Thread> threads = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            final boolean isWriter = t >= 4; // last 2 are TunHandlers
            Thread thread = Thread.ofPlatform().name(isWriter ? "TunHandler-" + t : "sslWorker-" + t).start(() -> {
                try {
                    CompactMap localMap = new CompactMap();
                    BinaryDocument localDoc = new BinaryDocument(localMap);
                    DynamicByteBuffer localBuf = new DynamicByteBuffer(8192, true);
                    ByteBuffer pkt = createIpPacket();
                    BinaryDocument writeDoc = buildForwardPacketDoc(pkt);

                    // Warmup
                    for (int i = 0; i < 5000; i++) {
                        if (isWriter) {
                            writer.serialize(localBuf, writeDoc);
                        } else {
                            localMap.clear();
                            reader.deserialize(ByteBuffer.wrap(serializedRef), localDoc);
                        }
                    }

                    long lastCheckpoint = System.currentTimeMillis();
                    long opsCount = 0;

                    while (!stop.get()) {
                        if (isWriter) {
                            writer.serialize(localBuf, writeDoc);
                        } else {
                            localMap.clear();
                            reader.deserialize(ByteBuffer.wrap(serializedRef), localDoc);
                        }
                        opsCount++;
                        totalOps.incrementAndGet();

                        // Print progress every 5 seconds
                        long now = System.currentTimeMillis();
                        if (now - lastCheckpoint > 5000) {
                            System.out.printf("  [%s] %,d ops so far%n",
                                    Thread.currentThread().getName(), opsCount);
                            lastCheckpoint = now;
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("%s: EXCEPTION: %s%n", Thread.currentThread().getName(), e.getMessage());
                    e.printStackTrace();
                    failed.set(true);
                }
            });
            threads.add(thread);
        }

        System.gc(); System.gc();
        Thread.sleep(200);

        long allocBefore = getAllocatedBytes();
        long startNanos = System.nanoTime();

        // Let it run
        Thread.sleep(durationMs);
        stop.set(true);

        // Wait for threads to finish (with deadlock detection)
        for (Thread t : threads) {
            boolean joined = false;
            t.join(5000);
            joined = !t.isAlive();
            if (!joined) {
                System.err.printf("  WARNING: Thread %s did not stop — possible deadlock!%n", t.getName());
                failed.set(true);
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        long totalAllocBytes = getAllocatedBytes() - allocBefore;
        long ops = totalOps.get();

        System.out.printf("  Duration:        %.1f s%n", elapsedNanos / 1e9);
        System.out.printf("  Total ops:       %,d%n", ops);
        System.out.printf("  Throughput:      %,.0f ops/sec%n", ops / (elapsedNanos / 1e9));
        System.out.printf("  Total allocated: %,d bytes (%.1f MB)%n", totalAllocBytes, totalAllocBytes / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAllocBytes / Math.max(ops, 1));
        System.out.println();

        assertFalse(failed.get(), "Failures detected during long-running test!");
    }

    // ==========================================================================
    //  CompactMap entryIterator boxing test
    // ==========================================================================

    @Test
    void compactMapIteratorBoxingTest() {
        System.out.println("=== CompactMap entryIterator Boxing Test ===");

        CompactMap map = new CompactMap();
        map.put(TAG_VER, VERSION);
        map.put(TAG_TIMESTAMP, Instant.now());
        map.put(TAG_DISCRIMINATOR, REQUEST_DTO_DISCRIMINATOR);
        map.put(TAG_PAYLOAD, "payload");

        int warmup = 10_000;
        int measure = 1_000_000;

        // Use the MessagePackWriter serialize path which calls entryIterator() internally
        DynamicByteBuffer buf = new DynamicByteBuffer(4096, true);
        BinaryDocument doc = new BinaryDocument(map);

        // Warmup
        for (int i = 0; i < warmup; i++) {
            writer.serialize(buf, doc);
        }

        System.gc(); System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        long allocBefore = getAllocatedBytes();

        for (int i = 0; i < measure; i++) {
            writer.serialize(buf, doc);
        }

        long totalAlloc = getAllocatedBytes() - allocBefore;

        System.out.printf("  Iterations:      %,d (4 entries each = serialize full map)%n", measure);
        System.out.printf("  Total allocated: %,d bytes%n", totalAlloc);
        System.out.printf("  Per iteration:   %,d bytes%n", totalAlloc / measure);
        System.out.println();
    }

    // ==========================================================================
    //  StringCache collision stress test
    // ==========================================================================

    @Test
    void stringCacheCollisionStressTest() {
        System.out.println("=== StringCache Collision Stress Test ===");

        // Create documents with many different string values to stress the 64-slot cache
        DynamicByteBuffer serBuf = new DynamicByteBuffer(65536, true);
        CompactMap deserMap = new CompactMap();
        BinaryDocument deserDoc = new BinaryDocument(deserMap);

        // Build document with protocol strings + extra strings
        String[] extraStrings = new String[100];
        for (int i = 0; i < extraStrings.length; i++) {
            extraStrings[i] = "cmd_" + i;
        }

        int measure = 50_000;

        // Warmup with varied strings
        for (int i = 0; i < 5000; i++) {
            CompactMap root = new CompactMap();
            root.put(TAG_VER, VERSION);
            root.put(TAG_COMMAND, extraStrings[i % extraStrings.length]);
            root.put(TAG_DISCRIMINATOR, REQUEST_DTO_DISCRIMINATOR);
            writer.serialize(serBuf, new BinaryDocument(root));
            deserMap.clear();
            reader.deserialize(serBuf.getBuffer(), deserDoc);
        }

        System.gc(); System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        long allocBefore = getAllocatedBytes();

        for (int i = 0; i < measure; i++) {
            CompactMap root = new CompactMap();
            root.put(TAG_VER, VERSION);
            root.put(TAG_COMMAND, extraStrings[i % extraStrings.length]);
            root.put(TAG_DISCRIMINATOR, REQUEST_DTO_DISCRIMINATOR);
            writer.serialize(serBuf, new BinaryDocument(root));

            deserMap.clear();
            reader.deserialize(serBuf.getBuffer(), deserDoc);

            // Verify
            assertEquals(extraStrings[i % extraStrings.length], deserDoc.getDocumentMap().get(TAG_COMMAND));
        }

        long totalAlloc = getAllocatedBytes() - allocBefore;

        System.out.printf("  Iterations:      %,d (100 unique strings rotating)%n", measure);
        System.out.printf("  Total allocated: %,d bytes (%.1f MB)%n", totalAlloc, totalAlloc / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAlloc / measure);
        System.out.println("  (high per-op = cache collisions forcing new String allocation)");
        System.out.println();
    }

    // ==========================================================================
    //  Scanner zero-alloc fast path test
    // ==========================================================================

    @Test
    void scannerZeroAllocTest() {
        System.out.println("=== Scanner Zero-Alloc Fast Path Test ===");

        DynamicByteBuffer serBuf = new DynamicByteBuffer(8192, true);
        ByteBuffer pkt = createIpPacket();
        BinaryDocument doc = buildForwardPacketDoc(pkt);
        writer.serialize(serBuf, doc);

        byte[] serializedBytes = new byte[serBuf.getBuffer().remaining()];
        serBuf.getBuffer().get(serializedBytes);

        byte[] forwardPacketBytes = FORWARD_PACKET_COMMAND.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer reusableBuf = ByteBuffer.wrap(serializedBytes);

        int warmup = 10_000;
        int measure = 500_000;

        // Warmup
        for (int i = 0; i < warmup; i++) {
            reusableBuf.position(4); // skip length header
            int rootSize = MessagePackScanner.readMapHeader(reusableBuf);
            ByteBuffer ipPacket = null;
            boolean found = false;
            for (int r = 0; r < rootSize; r++) {
                int key = MessagePackScanner.readInt(reusableBuf);
                if (key == TAG_PAYLOAD) {
                    int payloadSize = MessagePackScanner.readMapHeader(reusableBuf);
                    for (int p = 0; p < payloadSize; p++) {
                        int pkey = MessagePackScanner.readInt(reusableBuf);
                        if (pkey == TAG_COMMAND) {
                            found = MessagePackScanner.matchString(reusableBuf, forwardPacketBytes);
                        } else if (pkey == TAG_DATA) {
                            int dataSize = MessagePackScanner.readMapHeader(reusableBuf);
                            for (int d = 0; d < dataSize; d++) {
                                int dkey = MessagePackScanner.readInt(reusableBuf);
                                if (dkey == TAG_PACKET) {
                                    int binLen = MessagePackScanner.readBinaryHeader(reusableBuf);
                                    ipPacket = reusableBuf.slice(reusableBuf.position(), binLen);
                                    reusableBuf.position(reusableBuf.position() + binLen);
                                } else {
                                    MessagePackScanner.skip(reusableBuf);
                                }
                            }
                        } else {
                            MessagePackScanner.skip(reusableBuf);
                        }
                    }
                } else {
                    MessagePackScanner.skip(reusableBuf);
                }
            }
            assertTrue(found);
            assertNotNull(ipPacket);
            assertEquals(1500, ipPacket.remaining());
        }

        System.gc(); System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        long allocBefore = getAllocatedBytes();
        long startNanos = System.nanoTime();

        for (int i = 0; i < measure; i++) {
            reusableBuf.position(4);
            int rootSize = MessagePackScanner.readMapHeader(reusableBuf);
            for (int r = 0; r < rootSize; r++) {
                int key = MessagePackScanner.readInt(reusableBuf);
                if (key == TAG_PAYLOAD) {
                    int payloadSize = MessagePackScanner.readMapHeader(reusableBuf);
                    for (int p = 0; p < payloadSize; p++) {
                        int pkey = MessagePackScanner.readInt(reusableBuf);
                        if (pkey == TAG_COMMAND) {
                            MessagePackScanner.matchString(reusableBuf, forwardPacketBytes);
                        } else if (pkey == TAG_DATA) {
                            int dataSize = MessagePackScanner.readMapHeader(reusableBuf);
                            for (int d = 0; d < dataSize; d++) {
                                int dkey = MessagePackScanner.readInt(reusableBuf);
                                if (dkey == TAG_PACKET) {
                                    int binLen = MessagePackScanner.readBinaryHeader(reusableBuf);
                                    // Only allocation: ByteBuffer.slice() — unavoidable
                                    reusableBuf.position(reusableBuf.position() + binLen);
                                } else {
                                    MessagePackScanner.skip(reusableBuf);
                                }
                            }
                        } else {
                            MessagePackScanner.skip(reusableBuf);
                        }
                    }
                } else {
                    MessagePackScanner.skip(reusableBuf);
                }
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        long totalAlloc = getAllocatedBytes() - allocBefore;

        System.out.printf("  Operations:      %,d%n", measure);
        System.out.printf("  Throughput:      %,.0f ops/sec%n", measure / (elapsedNanos / 1e9));
        System.out.printf("  Total allocated: %,d bytes (%.2f MB)%n", totalAlloc, totalAlloc / (1024.0 * 1024));
        System.out.printf("  Per operation:   %,d bytes%n", totalAlloc / measure);
        System.out.println("  (0 = true zero-alloc, skipped ByteBuffer.slice)");
        System.out.println();
    }

    // ==========================================================================
    //  Helpers
    // ==========================================================================

    private static long getAllocatedBytes() {
        // Use ThreadMXBean for current thread allocation tracking
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sunBean) {
            return sunBean.getCurrentThreadAllocatedBytes();
        }
        return 0;
    }

    private static long getGcCount() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> gc.getCollectionCount())
                .sum();
    }
}
