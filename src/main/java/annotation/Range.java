package annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inclusive numeric bounds on an integral field (byte/short/int/long), enforced during binding.
 * A value outside {@code [min, max]} makes binding reject the document. Both bounds are optional
 * (default to the full {@code long} range).
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Range {
    long min() default Long.MIN_VALUE;
    long max() default Long.MAX_VALUE;
}
