# JBson

A high-performance, zero-dependency binary serialization library for Java. Supports **BSON**, **MessagePack**, and **JSON** formats with annotation-based POJO binding, pool-backed buffer management, and VarHandle-optimized field access.

[![Java](https://img.shields.io/badge/java-21-blue?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Build](https://img.shields.io/github/actions/workflow/status/GR-ROM/JBson/build.yml?branch=main)](https://github.com/GR-ROM/JBson/actions)
[![License](https://img.shields.io/github/license/GR-ROM/JBson)](LICENSE)
[![Version](https://img.shields.io/github/v/tag/GR-ROM/JBson)](https://github.com/GR-ROM/JBson/releases)

## Key Features

- **Multi-format**: BSON v1.1, MessagePack, and JSON serialization out of the box
- **High performance**: VarHandle field access, MethodHandle constructors, pre-computed schemas, dense tag arrays for O(1) lookups
- **Thread-safe**: Pool-backed buffer management with configurable pool strategies (Pool, FastPool, DisposablePool)
- **POJO binding**: Annotation-driven mapping (`@Tag`, `@BsonType`, `@Transient`) with support for generics, collections, maps, enums, and polymorphic types
- **No dependencies**: Self-contained — no `org.bson`, `jackson`, or `gson` required
- **NIO-friendly**: Works with `ByteBuffer`, `InputStream`, and `OutputStream`

## Quick Start

### Define a DTO

```java
public class Order {
    @Tag(0) private String orderId;
    @Tag(1) private String customerName;
    @Tag(2) private List<Item> items;
    @Tag(3) private Instant createdAt;
    @Tag(4) private BigDecimal totalAmount;
    @Transient private String internalNote;
}
```

### Serialize / Deserialize (BSON)

```java
var codec = Codec.bson();

// Serialize
ByteBuffer buffer = codec.serialize(order);

// Deserialize
Order result = codec.deserialize(buffer, Order.class);
```

### Serialize / Deserialize (MessagePack)

```java
var codec = Codec.messagePack();

ByteBuffer buffer = codec.serialize(order);
Order result = codec.deserialize(buffer, Order.class);
```

### Polymorphic Types

Use `@BsonType` to handle generic type fields with a discriminator:

```java
public class Request<T> {
    @Tag(0) private String action;
    @Tag(1) private Instant timestamp;
    @Tag(2) @BsonType(discriminator = 100) private T payload;
}
```

## Annotations

| Annotation | Target | Description |
|---|---|---|
| `@Tag(int)` | Field | Maps a field to a numeric tag for serialization |
| `@BsonType(discriminator)` | Field | Enables polymorphic deserialization via a discriminator value |
| `@Transient` | Field | Excludes a field from serialization |

## Supported Types

- **Primitives**: `byte`, `short`, `int`, `long`, `float`, `double`, `boolean`, `char` and their wrappers
- **Strings**: `String`
- **Temporal**: `Instant`, `LocalDateTime`, `BigDecimal`
- **Binary**: `byte[]`, `ByteBuffer`
- **Collections**: `List`, `Set`, `Queue` (with generic type preservation)
- **Maps**: `Map<K, V>`
- **Enums**: full enum value serialization
- **Nested objects**: recursive POJO binding
- **Polymorphic types**: `@BsonType` discriminator-based resolution

## Architecture

```
su.grinev
├── Codec                  # Unified serialization API (Codec.bson(), Codec.messagePack())
├── Binder                 # POJO <-> BinaryDocument mapping (VarHandle + ClassSchema)
├── BinaryDocument         # Integer-keyed document (BSON / MessagePack)
├── Document               # String-keyed document (JSON)
├── annotation/
│   ├── Tag                # Field tag mapping
│   ├── BsonType           # Polymorphic discriminator
│   └── Transient          # Skip field
├── bson/
│   ├── BsonObjectWriter   # BSON serialization
│   └── BsonObjectReader   # BSON deserialization
├── messagepack/
│   ├── MessagePackWriter  # MessagePack serialization
│   └── MessagePackReader  # MessagePack deserialization
├── json/
│   ├── JsonWriter         # JSON serialization
│   └── JsonReader         # JSON deserialization
└── pool/
    ├── PoolFactory        # Configurable pool builder
    ├── Pool               # Standard thread-safe pool
    ├── FastPool           # Lock-free pool
    ├── DisposablePool     # Auto-closeable pool
    └── DynamicByteBuffer  # Self-expanding buffer
```

## Performance

The Binder uses several optimizations to minimize serialization overhead:

- **VarHandle** for direct field access (no reflection at runtime)
- **MethodHandle** constructor cache for fast instantiation
- **Pre-computed ClassSchema** with `FieldKind` enum to avoid runtime type checks
- **Dense tag-indexed array** (`tagLookup`) for O(1) field resolution during deserialization
- **Pool-backed buffers** to reduce GC pressure

Benchmarks compare BSON, MessagePack, JSON, Protobuf, and Java serialization across various payload sizes (small packets, 1500-byte IP packets, 128KB payloads, complex nested structures).

Run benchmarks:

```bash
# Correctness test
./gradlew test --tests "su.grinev.BsonMapperTests.serializeAndDeserializeObjectTest"

# Performance test
./gradlew test --tests "su.grinev.BsonMapperTests.performanceTest"

# Multi-format benchmark (BSON vs MessagePack vs JSON vs Protobuf vs Java serialization)
./gradlew test --tests "su.grinev.DtoBenchmarkTest"

# JMH benchmarks
./gradlew jmh
```

## Build

```bash
./gradlew build
```

Requires Java 21.

## License

See [LICENSE](LICENSE) for details.
