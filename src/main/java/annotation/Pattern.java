package annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Regex a {@code String} field must fully match, enforced during binding. The pattern is compiled
 * once when the class schema is built. A non-matching value makes binding reject the document.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pattern {
    String value();
}
