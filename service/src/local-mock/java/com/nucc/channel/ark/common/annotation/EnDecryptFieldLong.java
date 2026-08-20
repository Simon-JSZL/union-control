package com.nucc.channel.ark.common.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Local compatibility copy. Production keeps its existing annotation unchanged. */
@Component
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EnDecryptFieldLong {
    String value() default "";
    int chunkSize() default 0;
}
