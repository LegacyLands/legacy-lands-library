package net.legacy.library.aop.tracing;

import java.util.Map;

/**
 * Service for managing distributed tracing operations.
 *
 * <p>This service provides methods for creating, managing, and exporting traces
 * across service boundaries. It integrates with OpenTelemetry standards and
 * supports various tracing backends.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface TraceService {

    /**
     * Starts a new trace.
     *
     * @param operationName the operation name
     * @param serviceName the service name
     * @return the trace context
     */
    TraceContext startTrace(String operationName, String serviceName);

    /**
     * Starts a new trace with a custom trace ID.
     *
     * @param traceId the trace ID
     * @param operationName the operation name
     * @param serviceName the service name
     * @return the trace context
     */
    TraceContext startTrace(String traceId, String operationName, String serviceName);

    /**
     * Gets the current active trace context.
     *
     * @return the current trace context, or null if no active trace
     */
    TraceContext getCurrentTrace();

    /**
     * Sets the current trace context.
     *
     * @param context the trace context to set as current
     */
    void setCurrentTrace(TraceContext context);

    /**
     * Completes the current trace.
     *
     * @param status the completion status
     */
    void completeCurrentTrace(TraceContext.TraceStatus status);

    /**
     * Completes the current trace with an error.
     *
     * @param error the error that occurred
     */
    void completeCurrentTraceWithError(Throwable error);

    /**
     * Creates a child span of the current trace.
     *
     * @param operationName the child operation name
     * @return the child trace context
     */
    TraceContext createChildSpan(String operationName);

    /**
     * Adds an attribute to the current trace.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    void addAttribute(String key, String value);

    /**
     * Adds a metric to the current trace.
     *
     * @param key the metric key
     * @param value the metric value
     */
    void addMetric(String key, double value);

    /**
     * Propagates the trace context to a remote service.
     *
     * @return the trace context headers for propagation
     */
    Map<String, String> getPropagationHeaders();

    /**
     * Extracts trace context from incoming headers.
     *
     * @param headers the incoming headers
     * @return the extracted trace context, or null if no context found
     */
    TraceContext extractFromHeaders(Map<String, String> headers);

    /**
     * Determines if a trace should be sampled based on the sampling rate.
     *
     * @param samplingRate the sampling rate (0.0 to 1.0)
     * @param alwaysTrace whether to always trace regardless of sampling
     * @return true if the trace should be sampled
     */
    boolean shouldSample(double samplingRate, boolean alwaysTrace);

    /**
     * Gets the trace statistics.
     *
     * @return the trace statistics
     */
    TraceStatistics getStatistics();

    /**
     * Shuts down the trace service and releases any resources.
     */
    void shutdown();

    /**
     * Interface for trace statistics.
     */
    interface TraceStatistics {

        long getTotalTraces();

        long getActiveTraces();

        long getCompletedTraces();

        long getErrorTraces();

        double getAverageDuration();

        long getMaxDuration();

        long getMinDuration();

    }

}