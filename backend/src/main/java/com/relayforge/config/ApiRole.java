package com.relayforge.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * Marks a bean that should only exist when this process serves the REST API —
 * that is, when {@code relayforge.role} is {@code api} or {@code all}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression(
        "'${relayforge.role:all}'.equalsIgnoreCase('api')"
                + " or '${relayforge.role:all}'.equalsIgnoreCase('all')")
public @interface ApiRole {}
