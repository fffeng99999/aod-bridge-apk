package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 编译期桩。 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR,
        ElementType.FIELD, ElementType.PACKAGE})
public @interface RequiresApi {
    int value() default 1;

    int api() default 1;
}
