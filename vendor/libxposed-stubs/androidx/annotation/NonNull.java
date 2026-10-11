package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 编译期桩：真实类由 androidx.annotation 提供（仅用于离线编译 libxposed API 源码）。 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
public @interface NonNull {
}
