package net.legacy.library.aop.tracing;

import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context object that holds tracing information for the current execution flow.
 *
 * <p>This context maintains the tracing state across method calls and service boundaries,
 * enabling comprehensive request chain monitoring and performance analysis.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public class TraceContext {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String operationName;
    private final String serviceName;
    private final Instant startTime;
    private final Map<String, String> attributes;
    private final Map<String, Double> metrics;
    private final Map<String, Object> baggage;
    private TraceStatus status;
    private Instant endTime;
    private Throwable error;
    private int nestingDepth;

    /**
     * Constructs a new trace context.
     *
     * @param traceId       the trace ID
     * @param spanId        the span ID
     * @param parentSpanId  the parent span ID, or null if this is the root span
     * @param operationName the operation name
     * @param serviceName   the service name
     */
    public TraceContext(String traceId, String spanId, String parentSpanId,
                        String operationName, String serviceName) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.operationName = operationName;
        this.serviceName = serviceName;
        this.startTime = Instant.now();
        this.attributes = new ConcurrentHashMap<>();
        this.metrics = new ConcurrentHashMap<>();
        this.status = TraceStatus.ACTIVE;
        this.nestingDepth = 0;
        this.baggage = new ConcurrentHashMap<>();
    }

    /**
     * Gets the duration of the trace in milliseconds.
     *
     * @return the duration, or 0 if the trace is still active
     */
    public long getDuration() {
        if (endTime == null) {
            return 0;
        }
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }

    /**
     * Adds an attribute to the trace.
     *
     * @param key   the attribute key
     * @param value the attribute value
     */
    public void addAttribute(String key, String value) {
        attributes.put(key, value);
    }

    /**
     * Adds a metric to the trace.
     *
     * @param key   the metric key
     * @param value the metric value
     */
    public void addMetric(String key, double value) {
        metrics.put(key, value);
    }

    /**
     * Adds a baggage item.
     *
     * @param key   the baggage key
     * @param value the baggage value
     */
    public void addBaggage(String key, Object value) {
        baggage.put(key, value);
    }

    /**
     * Gets a baggage item.
     *
     * @param key the baggage key
     * @return the baggage value, or null if not found
     */
    public Object getBaggage(String key) {
        return baggage.get(key);
    }

    /**
     * Completes the trace with the specified status.
     *
     * @param status the completion status
     */
    public void complete(TraceStatus status) {
        this.status = status;
        this.endTime = Instant.now();
    }

    /**
     * Completes the trace with an error.
     *
     * @param error the error that occurred
     */
    public void completeWithError(Throwable error) {
        this.error = error;
        this.status = TraceStatus.ERROR;
        this.endTime = Instant.now();
    }

    /**
     * Checks if the trace is still active.
     *
     * @return true if the trace is active
     */
    public boolean isActive() {
        return status == TraceStatus.ACTIVE;
    }

    /**
     * Creates a child span context.
     *
     * @param operationName the child operation name
     * @return the child trace context
     */
    public TraceContext createChildSpan(String operationName) {
        String childSpanId = generateSpanId();
        TraceContext childContext = new TraceContext(
                traceId, childSpanId, spanId, operationName, serviceName
        );
        childContext.nestingDepth = nestingDepth + 1;

        // Copy baggage items to child context
        childContext.getBaggage().putAll(baggage);

        return childContext;
    }

    /**
     * Generates a unique span ID.
     *
     * @return a unique span ID
     */
    private String generateSpanId() {
        return Long.toHexString(System.currentTimeMillis() ^ Thread.currentThread().threadId());
    }

    @Override
    public String toString() {
        return String.format("TraceContext{traceId='%s', spanId='%s', operation='%s', status=%s, duration=%dms}",
                traceId, spanId, operationName, status, getDuration());
    }

    /**
     * Enumeration of trace status values.
     */
    public enum TraceStatus {
        ACTIVE,
        COMPLETED,
        ERROR,
        TIMEOUT
    }

}
