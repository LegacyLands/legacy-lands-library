package net.legacy.library.aop.tracing;

import java.util.Map;

/**
 * Interface for exporting trace data to external tracing systems.
 *
 * <p>This interface provides methods for exporting trace spans, metrics, and logs
 * to various tracing backends such as Jaeger, Zipkin, or OpenTelemetry collectors.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface TraceExporter {

    /**
     * Exports a trace span to the tracing backend.
     *
     * @param context the trace context to export
     */
    void exportSpan(TraceContext context);

    /**
     * Exports multiple trace spans in batch for better performance.
     *
     * @param contexts the trace contexts to export
     */
    void exportSpans(Iterable<TraceContext> contexts);

    /**
     * Exports metrics associated with traces.
     *
     * @param metrics the metrics to export
     */
    void exportMetrics(Map<String, Double> metrics);

    /**
     * Flushes any pending trace data to ensure it's exported.
     */
    void flush();

    /**
     * Shuts down the exporter and releases any resources.
     */
    void shutdown();

}