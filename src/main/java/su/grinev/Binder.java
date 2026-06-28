package su.grinev;

import annotation.Type;
import annotation.Tag;
import annotation.Transient;
import annotation.Size;
import annotation.Range;
import annotation.Pattern;
import su.grinev.exception.ValidationException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Binder {

    public enum ClassNameMode { FULL_NAME, SIMPLE_NAME }

    private final ClassNameMode classNameMode;

    public Binder(ClassNameMode classNameMode) {
        this.classNameMode = classNameMode;
    }

    enum FieldKind { PRIMITIVE, ENUM, COLLECTION, MAP, TYPE, NESTED }

    static final class FieldBinding {
        final int tag;
        final VarHandle handle;
        final FieldKind kind;
        final Class<?> fieldType;
        final java.lang.reflect.Type genericType;
        final int discriminator; // -1 if not BSON_TYPE
        final Constraints constraints; // field validation bounds; null if none declared

        FieldBinding(int tag, VarHandle handle, FieldKind kind, Class<?> fieldType, java.lang.reflect.Type genericType, int discriminator, Constraints constraints) {
            this.tag = tag;
            this.handle = handle;
            this.kind = kind;
            this.fieldType = fieldType;
            this.genericType = genericType;
            this.discriminator = discriminator;
            this.constraints = constraints;
        }
    }

    /** Validation bounds for a field, enforced during binding. */
    static final class Constraints {
        final int minSize;                       // @Size: min length/size/byte-length
        final int maxSize;                       // @Size: max length/size/byte-length
        final long rangeMin;                     // @Range: min numeric value
        final long rangeMax;                     // @Range: max numeric value
        final java.util.regex.Pattern pattern;   // @Pattern: regex for String fields (null if none)

        Constraints(int minSize, int maxSize, long rangeMin, long rangeMax, java.util.regex.Pattern pattern) {
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.rangeMin = rangeMin;
            this.rangeMax = rangeMax;
            this.pattern = pattern;
        }
    }

    static final class ClassSchema {
        final FieldBinding[] bindings;   // for iteration in unbind()
        final FieldBinding[] tagLookup;  // tag-indexed array for O(1) lookup in bind()

        ClassSchema(FieldBinding[] bindings, FieldBinding[] tagLookup) {
            this.bindings = bindings;
            this.tagLookup = tagLookup;
        }
    }

    private static final Map<Class<?>, ClassSchema> schemaCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, MethodHandle> ctorCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, MethodHandle> recordCtorCache = new ConcurrentHashMap<>();
    // Per-record-class constraints, indexed by canonical-component order; built once per class.
    private static final Map<Class<?>, Constraints[]> recordConstraintsCache = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> classNameRegistry = new ConcurrentHashMap<>();
    private static final Set<String> knownPackages = ConcurrentHashMap.newKeySet();
    private static final Class<?> AMBIGUOUS = Binder.class;

    public static void registerClass(Class<?>... classes) {
        for (Class<?> clazz : classes) {
            classNameRegistry.put(clazz.getName(), clazz);
            classNameRegistry.merge(clazz.getSimpleName(), clazz,
                    (existing, incoming) -> existing == incoming ? existing : AMBIGUOUS);
            Package pkg = clazz.getPackage();
            if (pkg != null) {
                knownPackages.add(pkg.getName());
            }
        }
    }

    static Class<?> resolveClass(String name) throws ClassNotFoundException {
        Class<?> cached = classNameRegistry.get(name);
        if (cached != null && cached != AMBIGUOUS) return cached;
        if (name.indexOf('.') >= 0) {
            // FQCN: only allow if already registered — never call Class.forName() with untrusted input
            throw new ClassNotFoundException("Class not registered: " + name);
        }
        for (String pkg : knownPackages) {
            try {
                Class<?> found = Class.forName(pkg + "." + name);
                registerClass(found);
                return found;
            } catch (ClassNotFoundException ignored) {}
        }
        throw new ClassNotFoundException(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T bind(Class<T> tClass, BinaryDocument document) {
        if (tClass.isRecord()) {
            return (T) bindRecord(tClass, (Map<Integer, Object>) (Map<?, ?>) document.getDocumentMap());
        }
        Object rootObject = instantiate(tClass);
        ArrayDeque<BinderContext> stack = new ArrayDeque<>();
        stack.addLast(new BinderContext(rootObject, document.getDocumentMap(), tClass));
        runBindLoop(stack);
        return (T) rootObject;
    }

    @SuppressWarnings("unchecked")
    private void runBindLoop(ArrayDeque<BinderContext> stack) {
        while (!stack.isEmpty()) {
            BinderContext ctx = stack.removeLast();

            if (ctx.o instanceof Map targetMap && ctx.document instanceof Map<?, ?> docMap) {
                targetMap.putAll(docMap);
                continue;
            }

            if (ctx.o instanceof Collection<?> collection && ctx.document instanceof List<?> listData) {
                java.lang.reflect.Type itemType = resolveListItemType(ctx.type);
                for (Object rawItem : listData) {
                    if (rawItem == null) {
                        ((Collection<Object>) collection).add(null);
                    } else if (isPrimitiveOrWrapperOrString(rawItem.getClass())) {
                        ((Collection<Object>) collection).add(rawItem);
                    } else if (rawItem instanceof Map<?, ?> mapItem) {
                        Class<?> itemClass = resolveClassFromType(itemType);
                        if (itemClass.isRecord()) {
                            ((Collection<Object>) collection).add(bindRecord(itemClass, (Map<Integer, Object>) mapItem));
                        } else {
                            Object itemObj = instantiate(itemClass);
                            ((Collection<Object>) collection).add(itemObj);
                            stack.addLast(new BinderContext(itemObj, mapItem, itemClass));
                        }
                    }
                }
                continue;
            }

            ClassSchema schema = getSchema(ctx.o.getClass());
            Map<Integer, Object> documentMap = (Map<Integer, Object>) ctx.document;
            FieldBinding[] tagLookup = schema.tagLookup;

            for (Map.Entry<Integer, Object> entry : documentMap.entrySet()) {
                int key = entry.getKey();
                if (key < 0 || key >= tagLookup.length) continue;
                FieldBinding binding = tagLookup[key];
                if (binding == null) continue;

                Object value = entry.getValue();
                enforceConstraints(binding.constraints, value, binding.tag);

                try {
                    switch (binding.kind) {
                        case PRIMITIVE -> binding.handle.set(ctx.o, coerceNumeric(binding.fieldType, value));
                        case ENUM -> binding.handle.set(ctx.o, resolveEnum(binding.fieldType, value.toString()));
                        case COLLECTION -> {
                            Collection<Object> target = instantiateCollection(binding.fieldType);
                            binding.handle.set(ctx.o, target);
                            stack.addLast(new BinderContext(target, value, binding.genericType));
                        }
                        case MAP -> {
                            Map<Object, Object> targetMap = new HashMap<>();
                            binding.handle.set(ctx.o, targetMap);
                            stack.addLast(new BinderContext(targetMap, value, binding.genericType));
                        }
                        case TYPE -> {
                            String className = (String) documentMap.get(binding.discriminator);
                            Class<?> targetCls = resolveClass(className);
                            if (targetCls.isRecord()) {
                                binding.handle.set(ctx.o, bindRecord(targetCls, (Map<Integer, Object>) value));
                            } else {
                                Object newObject = instantiate(targetCls);
                                binding.handle.set(ctx.o, newObject);
                                stack.addLast(new BinderContext(newObject, value, targetCls));
                            }
                        }
                        case NESTED -> {
                            Class<?> targetCls = binding.fieldType;
                            if (targetCls.isRecord()) {
                                binding.handle.set(ctx.o, bindRecord(targetCls, (Map<Integer, Object>) value));
                            } else {
                                Object newObject = instantiate(targetCls);
                                binding.handle.set(ctx.o, newObject);
                                stack.addLast(new BinderContext(newObject, value, targetCls));
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to bind tag: " + key, e);
                }
            }
        }
    }

    // Read @Size/@Range/@Pattern off an annotated element (field or record component) into a
    // Constraints bundle, or null if none declared. Compiled once per class (schema/record cache).
    private static Constraints buildConstraints(java.lang.reflect.AnnotatedElement el) {
        Size sizeAnn = el.getAnnotation(Size.class);
        Range rangeAnn = el.getAnnotation(Range.class);
        Pattern patternAnn = el.getAnnotation(Pattern.class);
        if (sizeAnn == null && rangeAnn == null && patternAnn == null) {
            return null;
        }
        return new Constraints(
                sizeAnn != null ? sizeAnn.min() : 0,
                sizeAnn != null ? sizeAnn.max() : Integer.MAX_VALUE,
                rangeAnn != null ? rangeAnn.min() : Long.MIN_VALUE,
                rangeAnn != null ? rangeAnn.max() : Long.MAX_VALUE,
                patternAnn != null ? java.util.regex.Pattern.compile(patternAnn.value()) : null);
    }

    // Fail-fast field validation during binding: @Size (String length / Collection size / byte[]
    // length), @Range (numeric value), @Pattern (String regex). Violations throw ValidationException,
    // which makes binding reject the whole document. {@code tag} is only used for the error message.
    private static void enforceConstraints(Constraints c, Object value, int tag) {
        if (c == null || value == null) {
            return;
        }
        if (value instanceof CharSequence cs) {
            int len = cs.length();
            if (len < c.minSize || len > c.maxSize) {
                throw new ValidationException("Field (tag " + tag + ") length " + len
                        + " is out of bounds [" + c.minSize + ", " + c.maxSize + "]");
            }
            if (c.pattern != null && !c.pattern.matcher(cs).matches()) {
                throw new ValidationException("Field (tag " + tag + ") does not match pattern "
                        + c.pattern.pattern());
            }
        } else if (value instanceof Collection<?> col) {
            int sz = col.size();
            if (sz < c.minSize || sz > c.maxSize) {
                throw new ValidationException("Field (tag " + tag + ") size " + sz
                        + " is out of bounds [" + c.minSize + ", " + c.maxSize + "]");
            }
        } else if (value instanceof byte[] b) {
            int len = b.length;
            if (len < c.minSize || len > c.maxSize) {
                throw new ValidationException("Field (tag " + tag + ") length " + len
                        + " is out of bounds [" + c.minSize + ", " + c.maxSize + "]");
            }
        } else if (value instanceof Number n && !(value instanceof Float || value instanceof Double)) {
            long v = n.longValue();
            if (v < c.rangeMin || v > c.rangeMax) {
                throw new ValidationException("Field (tag " + tag + ") value " + v
                        + " is out of range [" + c.rangeMin + ", " + c.rangeMax + "]");
            }
        }
    }

    // ---- Record support (immutable types: built via the canonical constructor, not setters) ----

    @SuppressWarnings("unchecked")
    private Object bindRecord(Class<?> recordClass, Map<Integer, Object> doc) {
        java.lang.reflect.RecordComponent[] comps = recordClass.getRecordComponents();
        Constraints[] constraints = recordConstraintsCache.computeIfAbsent(recordClass, Binder::buildRecordConstraints);
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            java.lang.reflect.RecordComponent rc = comps[i];
            int tag = tagForComponent(recordClass, rc);
            Object docVal = doc == null ? null : doc.get(tag);
            enforceConstraints(constraints[i], docVal, tag);
            args[i] = bindComponentValue(rc.getType(), rc.getGenericType(), docVal);
        }
        try {
            MethodHandle ctor = recordCtorCache.computeIfAbsent(recordClass, Binder::canonicalConstructor);
            return ctor.invokeWithArguments(args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to construct record " + recordClass.getName(), e);
        }
    }

    // Constraints per record component (canonical order); annotations may be on the component or
    // its backing field. Built once per record class and cached.
    private static Constraints[] buildRecordConstraints(Class<?> recordClass) {
        java.lang.reflect.RecordComponent[] comps = recordClass.getRecordComponents();
        Constraints[] out = new Constraints[comps.length];
        for (int i = 0; i < comps.length; i++) {
            java.lang.reflect.RecordComponent rc = comps[i];
            Constraints c = buildConstraints(rc);
            if (c == null) {
                try {
                    c = buildConstraints(recordClass.getDeclaredField(rc.getName()));
                } catch (NoSuchFieldException ignored) {
                }
            }
            out[i] = c;
        }
        return out;
    }

    private static MethodHandle canonicalConstructor(Class<?> recordClass) {
        java.lang.reflect.RecordComponent[] comps = recordClass.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            paramTypes[i] = comps[i].getType();
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(recordClass, MethodHandles.lookup());
            return lookup.findConstructor(recordClass, MethodType.methodType(void.class, paramTypes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int tagForComponent(Class<?> recordClass, java.lang.reflect.RecordComponent rc) {
        Tag tag = rc.getAnnotation(Tag.class);
        if (tag == null) {
            try {
                tag = recordClass.getDeclaredField(rc.getName()).getAnnotation(Tag.class);
            } catch (NoSuchFieldException ignored) {
            }
        }
        if (tag == null) {
            throw new IllegalArgumentException("Record component '" + rc.getName() + "' in '"
                    + recordClass.getName() + "' must be annotated with @Tag");
        }
        if (tag.value() < 0) {
            throw new IllegalArgumentException("Tag value for record component '" + rc.getName() + "' in '"
                    + recordClass.getName() + "' must be non-negative, got " + tag.value());
        }
        return tag.value();
    }

    // Resolve an enum constant by wire name. Forward-compat: an unknown name maps to the enum's
    // UNKNOWN constant if it declares one (so a newer peer's new value doesn't break an older
    // binder); otherwise the lookup stays strict and throws.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> resolveEnum(Class<?> enumType, String name) {
        try {
            return Enum.valueOf((Class<Enum>) enumType, name);
        } catch (IllegalArgumentException e) {
            for (Object c : enumType.getEnumConstants()) {
                if (((Enum<?>) c).name().equals("UNKNOWN")) {
                    return (Enum<?>) c;
                }
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Object bindComponentValue(Class<?> type, java.lang.reflect.Type genericType, Object docVal) {
        if (docVal == null) {
            return type.isPrimitive() ? defaultPrimitive(type) : null;
        }
        if (isPrimitiveOrWrapperOrString(type)) {
            return coerceNumeric(type, docVal);
        }
        if (type.isEnum()) {
            return resolveEnum(type, docVal.toString());
        }
        if (type.isRecord()) {
            return bindRecord(type, (Map<Integer, Object>) docVal);
        }
        if (Collection.class.isAssignableFrom(type)) {
            Collection<Object> col = instantiateCollection(type);
            java.lang.reflect.Type itemType = resolveListItemType(genericType);
            Class<?> itemClass = resolveClassFromType(itemType);
            for (Object raw : (List<?>) docVal) {
                if (raw == null) {
                    col.add(null);
                } else if (isPrimitiveOrWrapperOrString(itemClass)) {
                    col.add(coerceNumeric(itemClass, raw));
                } else if (raw instanceof Map<?, ?> m) {
                    col.add(itemClass.isRecord()
                            ? bindRecord(itemClass, (Map<Integer, Object>) m)
                            : bindClassValue(itemClass, (Map<Integer, Object>) m));
                } else {
                    col.add(raw);
                }
            }
            return col;
        }
        if (Map.class.isAssignableFrom(type)) {
            Map<Object, Object> m = new HashMap<>();
            m.putAll((Map<Object, Object>) docVal);
            return m;
        }
        // nested non-record class
        return bindClassValue(type, (Map<Integer, Object>) docVal);
    }

    private Object bindClassValue(Class<?> clazz, Map<Integer, Object> doc) {
        Object o = instantiate(clazz);
        ArrayDeque<BinderContext> stack = new ArrayDeque<>();
        stack.addLast(new BinderContext(o, doc, clazz));
        runBindLoop(stack);
        return o;
    }

    private static Object defaultPrimitive(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    public BinaryDocument unbind(Object o) {
        Map<Object, Object> rootDocument = new HashMap<>();
        ArrayDeque<BinderContext> stack = new ArrayDeque<>();
        stack.addLast(new BinderContext(o, rootDocument, o.getClass()));

        while (!stack.isEmpty()) {
            BinderContext ctx = stack.removeLast();
            Map<Integer, Object> currentDocument = (Map<Integer, Object>) ctx.document;
            ClassSchema schema = getSchema(ctx.o.getClass());

            try {
                for (FieldBinding binding : schema.bindings) {
                    Object fieldValue = binding.handle.get(ctx.o);
                    if (fieldValue == null) continue;

                    int tag = binding.tag;
                    switch (binding.kind) {
                        case PRIMITIVE -> currentDocument.put(tag, fieldValue);
                        case ENUM -> currentDocument.put(tag, fieldValue.toString());
                        case TYPE -> {
                            Map<Integer, Object> nested = new LinkedHashMap<>();
                            String className = classNameMode == ClassNameMode.SIMPLE_NAME
                                    ? fieldValue.getClass().getSimpleName()
                                    : fieldValue.getClass().getName();
                            currentDocument.put(binding.discriminator, className);
                            currentDocument.put(tag, nested);
                            stack.addLast(new BinderContext(fieldValue, nested, fieldValue.getClass()));
                        }
                        case COLLECTION -> {
                            List<Object> serialized = new ArrayList<>();
                            currentDocument.put(tag, serialized);
                            for (Object item : (Collection<?>) fieldValue) {
                                if (isPrimitiveOrWrapperOrString(item.getClass()) || item.getClass().isEnum()) {
                                    serialized.add(item.toString());
                                } else {
                                    Map<Integer, Object> nested = new LinkedHashMap<>();
                                    serialized.add(nested);
                                    stack.addLast(new BinderContext(item, nested, item.getClass()));
                                }
                            }
                        }
                        case MAP -> {
                            Map<Integer, Object> nestedMap = new LinkedHashMap<>();
                            currentDocument.put(tag, nestedMap);
                            Map<?, ?> sourceMap = (Map<?, ?>) fieldValue;
                            sourceMap.forEach((k, v) -> nestedMap.put(((Number) k).intValue(), v));
                        }
                        case NESTED -> {
                            Map<Integer, Object> nested = new LinkedHashMap<>();
                            currentDocument.put(tag, nested);
                            stack.addLast(new BinderContext(fieldValue, nested, fieldValue.getClass()));
                        }
                    }
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        return new BinaryDocument(rootDocument, 0);
    }

    private static Object instantiate(Class<?> clazz) {
        try {
            MethodHandle ctor = ctorCache.computeIfAbsent(clazz, c -> {
                try {
                    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                    return lookup.findConstructor(c, MethodType.methodType(void.class));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return ctor.invoke();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private Collection<Object> instantiateCollection(Class<?> type) {
        if (type.isAssignableFrom(List.class) || type.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>();
        }
        if (type.isAssignableFrom(Set.class) || type.isAssignableFrom(HashSet.class)) {
            return new HashSet<>();
        }
        if (type.isAssignableFrom(Queue.class) || type.isAssignableFrom(LinkedList.class)) {
            return new LinkedList<>();
        }
        throw new UnsupportedOperationException("Unsupported collection type: " + type);
    }

    private static ClassSchema getSchema(Class<?> clazz) {
        return schemaCache.computeIfAbsent(clazz, Binder::buildSchema);
    }

    private static ClassSchema buildSchema(Class<?> clazz) {
        registerClass(clazz);
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        List<FieldBinding> bindingList = new ArrayList<>();
        int maxTag = -1;

        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            Tag tag = field.getAnnotation(Tag.class);
            if (tag == null) {
                throw new IllegalArgumentException(
                        "Field '" + field.getName() + "' in class '" + clazz.getName()
                                + "' must be annotated with @Tag or @Transient");
            }
            if (tag.value() < 0) {
                throw new IllegalArgumentException(
                        "Tag value for field '" + field.getName() + "' in class '" + clazz.getName()
                                + "' must be non-negative, got " + tag.value());
            }

            VarHandle handle;
            try {
                handle = lookup.findVarHandle(clazz, field.getName(), field.getType());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            FieldKind kind;
            int bsonDiscriminator = -1;
            Class<?> fieldType = field.getType();

            if (isPrimitiveOrWrapperOrString(fieldType)) {
                kind = FieldKind.PRIMITIVE;
            } else if (fieldType.isEnum()) {
                kind = FieldKind.ENUM;
            } else if (Collection.class.isAssignableFrom(fieldType)) {
                kind = FieldKind.COLLECTION;
            } else if (Map.class.isAssignableFrom(fieldType)) {
                kind = FieldKind.MAP;
            } else if (field.isAnnotationPresent(Type.class)) {
                kind = FieldKind.TYPE;
                bsonDiscriminator = field.getAnnotation(Type.class).discriminator();
            } else {
                kind = FieldKind.NESTED;
            }

            Constraints constraints = buildConstraints(field);

            FieldBinding binding = new FieldBinding(tag.value(), handle, kind, fieldType, field.getGenericType(), bsonDiscriminator, constraints);
            bindingList.add(binding);

            if (tag.value() > maxTag) {
                maxTag = tag.value();
            }
        }

        // Check for duplicate tags
        FieldBinding[] tagLookup = new FieldBinding[maxTag + 1];
        for (FieldBinding binding : bindingList) {
            if (tagLookup[binding.tag] != null) {
                throw new IllegalArgumentException(
                        "Duplicate tag value " + binding.tag + " in class '" + clazz.getName() + "'");
            }
            tagLookup[binding.tag] = binding;
        }

        return new ClassSchema(bindingList.toArray(new FieldBinding[0]), tagLookup);
    }

    public static boolean isPrimitiveOrWrapperOrString(Class<?> type) {
        return type.isPrimitive()
                || type == Instant.class
                || type == LocalDateTime.class
                || type == BigDecimal.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class
                || type == String.class
                || type == byte[].class
                || type == short[].class
                || type == int[].class
                || type == long[].class
                || type == float[].class
                || type == double[].class
                || type == boolean[].class
                || type == char[].class
                || type == Boolean[].class
                || type == Byte[].class
                || type == Short[].class
                || type == Integer[].class
                || type == Long[].class
                || type == Float[].class
                || type == Double[].class
                || type == Character[].class
                || type == String[].class
                || type == Enum.class
                || type == ByteBuffer.class;
    }

    private static Object coerceNumeric(Class<?> targetType, Object value) {
        if (value instanceof Number num) {
            if (targetType == Long.class || targetType == long.class) return num.longValue();
            if (targetType == Integer.class || targetType == int.class) return num.intValue();
            if (targetType == Double.class || targetType == double.class) return num.doubleValue();
            if (targetType == Float.class || targetType == float.class) return num.floatValue();
            if (targetType == Short.class || targetType == short.class) return num.shortValue();
            if (targetType == Byte.class || targetType == byte.class) return num.byteValue();
        }
        if (value instanceof Instant inst && targetType == LocalDateTime.class) {
            return LocalDateTime.ofInstant(inst, ZoneOffset.UTC);
        }
        return value;
    }

    private java.lang.reflect.Type resolveListItemType(java.lang.reflect.Type listType) {
        if (listType instanceof ParameterizedType pt) {
            return pt.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private Class<?> resolveClassFromType(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
        throw new IllegalArgumentException("Unknown type: " + type);
    }

    private record BinderContext(Object o, Object document, java.lang.reflect.Type type) {}
}
