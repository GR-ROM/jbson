# MessagePack Specification

MessagePack is a binary serialization format. It lets you exchange data among multiple languages like JSON, but it's faster and smaller.

## Format Overview

MessagePack uses a type-length-value (TLV) encoding where the first byte(s) determine the type and potentially the value or length of the data that follows.

---

## Type System

### Format Byte Categories

The first byte of any MessagePack value determines its type. The byte is divided into several ranges:

| Byte Range       | Hex Range       | Type           | Description                    |
|------------------|-----------------|----------------|--------------------------------|
| `0x00` - `0x7F`  | `0xxxxxxx`      | positive fixint| 7-bit positive integer (0-127) |
| `0x80` - `0x8F`  | `1000xxxx`      | fixmap         | Map with 0-15 elements         |
| `0x90` - `0x9F`  | `1001xxxx`      | fixarray       | Array with 0-15 elements       |
| `0xA0` - `0xBF`  | `101xxxxx`      | fixstr         | String with 0-31 bytes         |
| `0xC0`           | -               | nil            | null/nil value                 |
| `0xC1`           | -               | (never used)   | Reserved                       |
| `0xC2`           | -               | false          | Boolean false                  |
| `0xC3`           | -               | true           | Boolean true                   |
| `0xC4` - `0xC6`  | -               | bin            | Binary data (8/16/32-bit len)  |
| `0xC7` - `0xC9`  | -               | ext            | Extension type (8/16/32-bit)   |
| `0xCA`           | -               | float 32       | IEEE 754 single precision      |
| `0xCB`           | -               | float 64       | IEEE 754 double precision      |
| `0xCC` - `0xCF`  | -               | uint           | Unsigned int (8/16/32/64-bit)  |
| `0xD0` - `0xD3`  | -               | int            | Signed int (8/16/32/64-bit)    |
| `0xD4` - `0xD8`  | -               | fixext         | Fixed-length extension         |
| `0xD9` - `0xDB`  | -               | str            | String (8/16/32-bit length)    |
| `0xDC` - `0xDD`  | -               | array          | Array (16/32-bit length)       |
| `0xDE` - `0xDF`  | -               | map            | Map (16/32-bit length)         |
| `0xE0` - `0xFF`  | `111xxxxx`      | negative fixint| 5-bit negative int (-32 to -1) |

---

## Bitfield Breakdown

### Positive Fixint (0x00 - 0x7F)

```
+--------+
|0XXXXXXX|
+--------+
 │└─────┴── 7-bit value (0-127)
 └──────── always 0
```

The value IS the byte itself (no additional bytes needed).

### Negative Fixint (0xE0 - 0xFF)

```
+--------+
|111XXXXX|
+--------+
 │││└───┴── 5-bit value (stored as two's complement)
 └┴┴────── always 111
```

Value = byte as signed int8 (yields -32 to -1).

### Fixmap (0x80 - 0x8F)

```
+--------+
|1000XXXX|
+--------+
 ││││└──┴── 4-bit element count (0-15 pairs)
 └┴┴┴───── always 1000
```

Followed by N×2 objects (key-value pairs).

### Fixarray (0x90 - 0x9F)

```
+--------+
|1001XXXX|
+--------+
 ││││└──┴── 4-bit element count (0-15)
 └┴┴┴───── always 1001
```

Followed by N objects.

### Fixstr (0xA0 - 0xBF)

```
+--------+
|101XXXXX|
+--------+
 │││└───┴── 5-bit length (0-31 bytes)
 └┴┴────── always 101
```

Followed by N bytes of UTF-8 string data.

---

## Complete Type Table

### Nil, Boolean

| Format   | First Byte | Additional Bytes | Value        |
|----------|------------|------------------|--------------|
| nil      | `0xC0`     | 0                | null         |
| false    | `0xC2`     | 0                | false        |
| true     | `0xC3`     | 0                | true         |

### Integer Types

| Format           | First Byte | Additional Bytes | Range                          |
|------------------|------------|------------------|--------------------------------|
| positive fixint  | `0x00-0x7F`| 0                | 0 to 127                       |
| negative fixint  | `0xE0-0xFF`| 0                | -32 to -1                      |
| uint 8           | `0xCC`     | 1                | 0 to 255                       |
| uint 16          | `0xCD`     | 2 (big-endian)   | 0 to 65,535                    |
| uint 32          | `0xCE`     | 4 (big-endian)   | 0 to 4,294,967,295             |
| uint 64          | `0xCF`     | 8 (big-endian)   | 0 to 18,446,744,073,709,551,615|
| int 8            | `0xD0`     | 1                | -128 to 127                    |
| int 16           | `0xD1`     | 2 (big-endian)   | -32,768 to 32,767              |
| int 32           | `0xD2`     | 4 (big-endian)   | -2,147,483,648 to 2,147,483,647|
| int 64           | `0xD3`     | 8 (big-endian)   | -2^63 to 2^63-1                |

**Integer Encoding Rules:**
- Use the smallest format that can represent the value
- Positive values 0-127: use positive fixint
- Negative values -32 to -1: use negative fixint
- Otherwise use the smallest int/uint format

### Float Types

| Format    | First Byte | Additional Bytes | Precision    |
|-----------|------------|------------------|--------------|
| float 32  | `0xCA`     | 4 (IEEE 754)     | Single       |
| float 64  | `0xCB`     | 8 (IEEE 754)     | Double       |

**Float 32 Layout (IEEE 754):**
```
+--------+--------+--------+--------+--------+
|  0xCA  |SEEEEEEE|EMMMMMMM|MMMMMMMM|MMMMMMMM|
+--------+--------+--------+--------+--------+
          S = Sign (1 bit)
          E = Exponent (8 bits)
          M = Mantissa (23 bits)
```

**Float 64 Layout (IEEE 754):**
```
+--------+--------+--------+--------+--------+--------+--------+--------+--------+
|  0xCB  |SEEEEEEE|EEEEMMMM|MMMMMMMM|MMMMMMMM|MMMMMMMM|MMMMMMMM|MMMMMMMM|MMMMMMMM|
+--------+--------+--------+--------+--------+--------+--------+--------+--------+
          S = Sign (1 bit)
          E = Exponent (11 bits)
          M = Mantissa (52 bits)
```

### String Types

| Format   | First Byte  | Length Bytes | Max Length    |
|----------|-------------|--------------|---------------|
| fixstr   | `0xA0-0xBF` | 0 (in type)  | 31 bytes      |
| str 8    | `0xD9`      | 1            | 255 bytes     |
| str 16   | `0xDA`      | 2            | 65,535 bytes  |
| str 32   | `0xDB`      | 4            | 4,294,967,295 |

**str 8 Layout:**
```
+--------+--------+========+
|  0xD9  |XXXXXXXX|  data  |
+--------+--------+========+
          └──────── length (1 byte)
```

**str 16 Layout:**
```
+--------+--------+--------+========+
|  0xDA  |XXXXXXXX|XXXXXXXX|  data  |
+--------+--------+--------+========+
          └───────────────── length (2 bytes, big-endian)
```

**str 32 Layout:**
```
+--------+--------+--------+--------+--------+========+
|  0xDB  |XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|  data  |
+--------+--------+--------+--------+--------+========+
          └─────────────────────────────────── length (4 bytes, big-endian)
```

### Binary Types

| Format  | First Byte | Length Bytes | Max Length    |
|---------|------------|--------------|---------------|
| bin 8   | `0xC4`     | 1            | 255 bytes     |
| bin 16  | `0xC5`     | 2            | 65,535 bytes  |
| bin 32  | `0xC6`     | 4            | 4,294,967,295 |

**bin 8 Layout:**
```
+--------+--------+========+
|  0xC4  |XXXXXXXX|  data  |
+--------+--------+========+
```

**bin 16 Layout:**
```
+--------+--------+--------+========+
|  0xC5  |XXXXXXXX|XXXXXXXX|  data  |
+--------+--------+--------+========+
```

**bin 32 Layout:**
```
+--------+--------+--------+--------+--------+========+
|  0xC6  |XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|  data  |
+--------+--------+--------+--------+--------+========+
```

### Array Types

| Format    | First Byte  | Length Bytes | Max Elements  |
|-----------|-------------|--------------|---------------|
| fixarray  | `0x90-0x9F` | 0 (in type)  | 15            |
| array 16  | `0xDC`      | 2            | 65,535        |
| array 32  | `0xDD`      | 4            | 4,294,967,295 |

**array 16 Layout:**
```
+--------+--------+--------+~~~~~~~~~~~~~~~~~+
|  0xDC  |XXXXXXXX|XXXXXXXX|    N objects    |
+--------+--------+--------+~~~~~~~~~~~~~~~~~+
          └───────────────── element count (2 bytes, big-endian)
```

**array 32 Layout:**
```
+--------+--------+--------+--------+--------+~~~~~~~~~~~~~~~~~+
|  0xDD  |XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|    N objects    |
+--------+--------+--------+--------+--------+~~~~~~~~~~~~~~~~~+
          └─────────────────────────────────── element count (4 bytes, big-endian)
```

### Map Types

| Format  | First Byte  | Length Bytes | Max Pairs     |
|---------|-------------|--------------|---------------|
| fixmap  | `0x80-0x8F` | 0 (in type)  | 15            |
| map 16  | `0xDE`      | 2            | 65,535        |
| map 32  | `0xDF`      | 4            | 4,294,967,295 |

**map 16 Layout:**
```
+--------+--------+--------+~~~~~~~~~~~~~~~~~+
|  0xDE  |XXXXXXXX|XXXXXXXX|  N*2 objects    |
+--------+--------+--------+~~~~~~~~~~~~~~~~~+
          └───────────────── pair count (2 bytes, big-endian)
```

Maps contain N key-value pairs (2N objects total).

### Extension Types

Extension types allow application-specific data with a type code.

| Format    | First Byte | Type Byte | Data Length   |
|-----------|------------|-----------|---------------|
| fixext 1  | `0xD4`     | 1         | 1 byte        |
| fixext 2  | `0xD5`     | 1         | 2 bytes       |
| fixext 4  | `0xD6`     | 1         | 4 bytes       |
| fixext 8  | `0xD7`     | 1         | 8 bytes       |
| fixext 16 | `0xD8`     | 1         | 16 bytes      |
| ext 8     | `0xC7`     | 1         | 0-255 bytes   |
| ext 16    | `0xC8`     | 1         | 0-65,535      |
| ext 32    | `0xC9`     | 1         | 0-4,294,967,295|

**fixext 1 Layout:**
```
+--------+--------+--------+
|  0xD4  |  type  |  data  |
+--------+--------+--------+
          │        └─────── 1 byte of data
          └──────────────── type code (signed int8, -128 to 127)
```

**ext 8 Layout:**
```
+--------+--------+--------+========+
|  0xC7  |XXXXXXXX|  type  |  data  |
+--------+--------+--------+========+
          │        │        └─────── N bytes of data
          │        └──────────────── type code (signed int8)
          └───────────────────────── length (1 byte)
```

**Reserved Extension Type Codes:**
| Type Code | Description       |
|-----------|-------------------|
| -1        | Timestamp         |
| 0 to 127  | Application use   |
| -128 to -2| Reserved (future) |

---

## Timestamp Extension (Type -1)

MessagePack defines a timestamp extension for representing time.

### Timestamp 32 (fixext 4)

```
+--------+--------+--------+--------+--------+--------+
|  0xD6  |   -1   |XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|
+--------+--------+--------+--------+--------+--------+
                   └─────────────────────────────────── seconds (32-bit, big-endian)
```
Range: 1970-01-01 00:00:00 to 2106-02-07 06:28:16 UTC

### Timestamp 64 (fixext 8)

```
+--------+--------+--------+--------+--------+--------+--------+--------+--------+--------+
|  0xD7  |   -1   |XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|XXXXXXXX|
+--------+--------+--------+--------+--------+--------+--------+--------+--------+--------+
                   │                          └────────────────────────────────────────────
                   │                           seconds in lower 34 bits
                   └──────────────────────────────────────────────────────────────────────
                    nanoseconds in upper 30 bits
```

**Bit layout of 8-byte data:**
```
+--------+--------+--------+--------+--------+--------+--------+--------+
|NNNNNNNN|NNNNNNNN|NNNNNNNN|NNSSSSSS|SSSSSSSS|SSSSSSSS|SSSSSSSS|SSSSSSSS|
+--------+--------+--------+--------+--------+--------+--------+--------+
 N = nanoseconds (30 bits, 0-999999999)
 S = seconds (34 bits)
```

### Timestamp 96 (ext 8 with 12 bytes)

```
+--------+--------+--------+--------+--------+--------+--------+--------+
|  0xC7  |   12   |   -1   |NNNNNNNN|NNNNNNNN|NNNNNNNN|NNNNNNNN|
+--------+--------+--------+--------+--------+--------+--------+--------+
+--------+--------+--------+--------+--------+--------+--------+--------+
|SSSSSSSS|SSSSSSSS|SSSSSSSS|SSSSSSSS|SSSSSSSS|SSSSSSSS|SSSSSSSS|SSSSSSSS|
+--------+--------+--------+--------+--------+--------+--------+--------+
 N = nanoseconds (32 bits, 0-999999999)
 S = seconds (64 bits, signed)
```

---

## Format Code Quick Reference

| Hex    | Binary     | Type             |
|--------|------------|------------------|
| `0x00` | `00000000` | positive fixint  |
| `0x7F` | `01111111` | positive fixint  |
| `0x80` | `10000000` | fixmap (0 elem)  |
| `0x8F` | `10001111` | fixmap (15 elem) |
| `0x90` | `10010000` | fixarray (0 elem)|
| `0x9F` | `10011111` | fixarray (15)    |
| `0xA0` | `10100000` | fixstr (0 bytes) |
| `0xBF` | `10111111` | fixstr (31 bytes)|
| `0xC0` | `11000000` | nil              |
| `0xC1` | `11000001` | (never used)     |
| `0xC2` | `11000010` | false            |
| `0xC3` | `11000011` | true             |
| `0xC4` | `11000100` | bin 8            |
| `0xC5` | `11000101` | bin 16           |
| `0xC6` | `11000110` | bin 32           |
| `0xC7` | `11000111` | ext 8            |
| `0xC8` | `11001000` | ext 16           |
| `0xC9` | `11001001` | ext 32           |
| `0xCA` | `11001010` | float 32         |
| `0xCB` | `11001011` | float 64         |
| `0xCC` | `11001100` | uint 8           |
| `0xCD` | `11001101` | uint 16          |
| `0xCE` | `11001110` | uint 32          |
| `0xCF` | `11001111` | uint 64          |
| `0xD0` | `11010000` | int 8            |
| `0xD1` | `11010001` | int 16           |
| `0xD2` | `11010010` | int 32           |
| `0xD3` | `11010011` | int 64           |
| `0xD4` | `11010100` | fixext 1         |
| `0xD5` | `11010101` | fixext 2         |
| `0xD6` | `11010110` | fixext 4         |
| `0xD7` | `11010111` | fixext 8         |
| `0xD8` | `11011000` | fixext 16        |
| `0xD9` | `11011001` | str 8            |
| `0xDA` | `11011010` | str 16           |
| `0xDB` | `11011011` | str 32           |
| `0xDC` | `11011100` | array 16         |
| `0xDD` | `11011101` | array 32         |
| `0xDE` | `11011110` | map 16           |
| `0xDF` | `11011111` | map 32           |
| `0xE0` | `11100000` | negative fixint  |
| `0xFF` | `11111111` | negative fixint  |

---

## Serialization Examples

### Integer Examples

| Value | Encoded Bytes           | Explanation                      |
|-------|-------------------------|----------------------------------|
| 0     | `0x00`                  | positive fixint                  |
| 127   | `0x7F`                  | positive fixint (max)            |
| 128   | `0xCC 0x80`             | uint 8                           |
| 255   | `0xCC 0xFF`             | uint 8 (max)                     |
| 256   | `0xCD 0x01 0x00`        | uint 16                          |
| -1    | `0xFF`                  | negative fixint                  |
| -32   | `0xE0`                  | negative fixint (min)            |
| -33   | `0xD0 0xDF`             | int 8                            |
| -128  | `0xD0 0x80`             | int 8 (min)                      |
| -129  | `0xD1 0xFF 0x7F`        | int 16                           |

### String Examples

| Value   | Encoded Bytes                    |
|---------|----------------------------------|
| ""      | `0xA0`                           |
| "a"     | `0xA1 0x61`                      |
| "hello" | `0xA5 0x68 0x65 0x6C 0x6C 0x6F`  |

### Array Examples

| Value      | Encoded Bytes                    |
|------------|----------------------------------|
| []         | `0x90`                           |
| [1]        | `0x91 0x01`                      |
| [1, 2, 3]  | `0x93 0x01 0x02 0x03`            |

### Map Examples

| Value           | Encoded Bytes                         |
|-----------------|---------------------------------------|
| {}              | `0x80`                                |
| {"a": 1}        | `0x81 0xA1 0x61 0x01`                 |

---

## Java Type Mapping

For JBson integration:

| Java Type       | MessagePack Type | Format Codes              |
|-----------------|------------------|---------------------------|
| `null`          | nil              | `0xC0`                    |
| `Boolean`       | bool             | `0xC2`, `0xC3`            |
| `Byte`          | int              | fixint, int 8             |
| `Short`         | int              | fixint, int 8/16          |
| `Integer`       | int              | fixint, int 8/16/32       |
| `Long`          | int              | fixint, int 8/16/32/64    |
| `Float`         | float 32         | `0xCA`                    |
| `Double`        | float 64         | `0xCB`                    |
| `String`        | str              | fixstr, str 8/16/32       |
| `byte[]`        | bin              | bin 8/16/32               |
| `ByteBuffer`    | bin              | bin 8/16/32               |
| `List<?>`       | array            | fixarray, array 16/32     |
| `Map<?,?>`      | map              | fixmap, map 16/32         |
| `Instant`       | ext (-1)         | timestamp extension       |

---

## Byte Order

All multi-byte integers in MessagePack are stored in **big-endian** (network byte order).

```java
// Reading uint 16 in Java
int value = ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);

// Or using ByteBuffer with BIG_ENDIAN order (default)
buffer.order(ByteOrder.BIG_ENDIAN);
int value = buffer.getShort() & 0xFFFF;
```

---

## Comparison with BSON

| Feature         | MessagePack              | BSON                     |
|-----------------|--------------------------|--------------------------|
| Byte order      | Big-endian               | Little-endian            |
| String encoding | UTF-8 with length prefix | UTF-8 with null terminator|
| Document size   | No size prefix           | 4-byte size prefix       |
| Root type       | Any                      | Document only            |
| Type in array   | Implicit (by position)   | Explicit (with key)      |
| Integer types   | Compact (fixint)         | Always 32 or 64 bit      |

---

## Parsing Algorithm

```
function parse(buffer):
    byte = buffer.read()

    if byte <= 0x7F:                    // positive fixint
        return byte
    else if byte >= 0xE0:               // negative fixint
        return byte - 256               // signed interpretation
    else if byte >= 0x80 and byte <= 0x8F:  // fixmap
        count = byte & 0x0F
        return parseMap(buffer, count)
    else if byte >= 0x90 and byte <= 0x9F:  // fixarray
        count = byte & 0x0F
        return parseArray(buffer, count)
    else if byte >= 0xA0 and byte <= 0xBF:  // fixstr
        length = byte & 0x1F
        return parseString(buffer, length)
    else:
        switch byte:
            case 0xC0: return null
            case 0xC2: return false
            case 0xC3: return true
            case 0xCA: return parseFloat32(buffer)
            case 0xCB: return parseFloat64(buffer)
            case 0xCC: return parseUint8(buffer)
            case 0xCD: return parseUint16(buffer)
            case 0xCE: return parseUint32(buffer)
            case 0xCF: return parseUint64(buffer)
            case 0xD0: return parseInt8(buffer)
            case 0xD1: return parseInt16(buffer)
            case 0xD2: return parseInt32(buffer)
            case 0xD3: return parseInt64(buffer)
            // ... etc
```

---

## References

- [MessagePack Specification](https://github.com/msgpack/msgpack/blob/master/spec.md)
- [MessagePack Website](https://msgpack.org/)
