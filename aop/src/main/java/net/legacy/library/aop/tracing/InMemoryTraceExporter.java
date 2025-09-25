package net.legacy.library.aop.tracing;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of TraceExporter for development and testing.
 *
 * <p>This exporter stores trace data in memory and provides basic logging
 * capabilities. It's suitable for development environments and testing scenarios
 * where external tracing backends are not available.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
public class InMemoryTraceExporter implements TraceExporter {

    private final ConcurrentMap<String, TraceContext> exportedSpans = new ConcurrentHashMap<>();
    private final AtomicLong totalSpansExported = new AtomicLong(0);
    private final boolean enableLogging;

    /**
     * Constructs a new in-memory trace exporter with logging enabled.
     */
    public InMemoryTraceExporter() {
        this(true);
    }

    /**
     * Constructs a new in-memory trace exporter.
     *
     * @param enableLogging whether to enable trace logging
     */
    public InMemoryTraceExporter(boolean enableLogging) {
        this.enableLogging = enableLogging;
    }

    @Override
    public void exportSpan(TraceContext context) {
        if (context == null) {
            return;
        }

        // Store the span
        exportedSpans.put(context.getSpanId(), context);
        totalSpansExported.incrementAndGet();

        // Log the span if enabled
        if (enableLogging) {
            logSpan(context);
        }
    }

    @Override
    public void exportSpans(Iterable<TraceContext> contexts) {
        if (contexts == null) {
            return;
        }

        for (TraceContext context : contexts) {
            exportSpan(context);
        }
    }

    @Override
    public void exportMetrics(java.util.Map<String, Double> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }

        if (enableLogging) {
            Log.info("Exporting metrics: %s", metrics);
        }

        // Store metrics in the most recent span for demonstration
        // In a real implementation, this would send metrics to a metrics system
        metrics.forEach((key, value) -> Log.info("Metric: %s = %f", key, value));
    }

    @Override
    public void flush() {
        // No-op for in-memory exporter
        Log.info("Flushed %d exported spans", exportedSpans.size());
    }

    @Override
    public void shutdown() {
        Log.info("Shutting down in-memory trace exporter. Total spans exported: %d",
                totalSpansExported.get());
        exportedSpans.clear();
    }

    /**
     * Gets all exported spans.
     *
     * @return the exported spans
     */
    public java.util.Collection<TraceContext> getExportedSpans() {
        return exportedSpans.values();
    }

    /**
     * Gets the total number of spans exported.
     *
     * @return the total spans exported
     */
    public long getTotalSpansExported() {
        return totalSpansExported.get();
    }

    /**
     * Clears all exported spans.
     */
    public void clear() {
        exportedSpans.clear();
    }

    /**
     * Gets a specific exported span by span ID.
     *
     * @param spanId the span ID
     * @return the trace context, or null if not found
     */
    public TraceContext getExportedSpan(String spanId) {
        return exportedSpans.get(spanId);
    }

    /**
     * Logs the trace span information.
     *
     * @param context the trace context
     */
    private void logSpan(TraceContext context) {
        String status = context.getStatus() == TraceContext.TraceStatus.ERROR
                ? "ERROR"
                : context.getStatus().toString();

        Log.info("Trace Span: %s.%s [%s] (%dms) - %s",
                context.getServiceName(),
                context.getOperationName(),
                status,
                context.getDuration(),
                context.getTraceId());

        // Log attributes if any
        if (!context.getAttributes().isEmpty()) {
            Log.info("Attributes: %s", context.getAttributes());
        }

        // Log metrics if any
        if (!context.getMetrics().isEmpty()) {
            Log.info("Metrics: %s", context.getMetrics());
        }

        // Log error if present
        if (context.getError() != null) {
            Log.warn("Error: %s - %s",
                    context.getError().getClass().getSimpleName(),
                    context.getError().getMessage());
        }
    }

}
