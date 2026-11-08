package com.relayforge.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * Marks a bean that should only exist when this process runs delivery workers —
 * that is, when {@code relayforge.role} is {@code worker} or {@code all}.
 *
 * <p>A meta-annotation rather than a repeated SpEL expression, so the role rule
 * has one definition. Getting it wrong in one place would mean either two
 * processes both polling the outbox, or nobody polling it at all; neither is
 * obvious from reading a single class.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression(
        "'${relayforge.role:all}'.equalsIgnoreCase('worker')"
                + " or '${relayforge.role:all}'.equalsIgnoreCase('all')")
public @interface WorkerRole {}
