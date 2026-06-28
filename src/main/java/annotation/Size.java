package annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bounds on a variable-length field, enforced during deserialization (binding).
 * For a {@code String} field the bounds apply to the character length; for a {@code Collection}
 * field to the element count; for a {@code byte[]} field to the byte length. A value outside
 * {@code [min, max]} makes binding reject the document — a cheap guard against abusive input.
 * Both bounds are optional: {@code min} defaults to 0 and {@code max} to {@link Integer#MAX_VALUE}.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Size {
    int min() default 0;
    int max() default Integer.MAX_VALUE;
}
