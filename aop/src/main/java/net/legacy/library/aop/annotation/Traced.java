package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for enabling distributed tracing on methods.
 *
 * <p>This annotation enables comprehensive request chain monitoring across service boundaries,
 * providing detailed tracking of method execution, performance metrics, and error tracking.
 * It integrates with OpenTelemetry standards for enterprise-grade observability.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Traced {

    /**
     * The operation name for the trace span.
     *
     * <p>If not specified, the method name will be used.
     *
     * @return the operation name
     */
    String operationName() default "";

    /**
     * The service name for the trace span.
     *
     * <p>If not specified, the class name will be used.
     *
     * @return the service name
     */
    String serviceName() default "";

    /**
     * Whether to include method parameters in the trace attributes.
     *
     * @return true if parameters should be included
     */
    boolean includeParameters() default true;

    /**
     * The maximum size of parameter values to include in traces.
     *
     * <p>Large parameter values will be truncated to prevent excessive memory usage.
     *
     * @return maximum parameter size in characters
     */
    int maxParameterSize() default 100;

    /**
     * Whether to include the return value in the trace attributes.
     *
     * @return true if return value should be included
     */
    boolean includeReturnValue() default false;

    /**
     * Whether to create a new span even if a tracing context already exists.
     *
     * <p>When true, creates a new child span. When false, reuses the existing span.
     *
     * @return true to create a new span
     */
    boolean forceNewSpan() default false;

    /**
     * The sampling rate for this trace (0.0 to 1.0).
     *
     * <p>A value of 1.0 means 100% sampling, 0.1 means 10% sampling.
     * The default value of -1.0 indicates that the global sampling configuration should be used.
     *
     * @return the sampling rate, or -1.0 to use global configuration
     */
    double samplingRate() default -1.0;

    /**
     * Whether to trace this method even in low-traffic environments.
     *
     * <p>When true, the method will always be traced regardless of sampling rate.
     *
     * @return true to always trace this method
     */
    boolean alwaysTrace() default false;

    /**
     * The tags to apply to the trace span.
     *
     * <p>Each tag should be in the format "key=value".
     *
     * @return array of tags
     */
    String[] tags() default {};

    /**
     * Whether to include stack traces for exceptions in the trace.
     *
     * @return true to include stack traces
     */
    boolean includeStackTraces() default true;

    /**
     * The maximum depth for nested method tracing.
     *
     * <p>Prevents excessive stack depth in recursive or deeply nested method calls.
     *
     * @return maximum nesting depth
     */
    int maxNestingDepth() default 10;

}