package net.legacy.library.aop.tracing;

import io.fairyproject.container.InjectableComponent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of the TraceService interface.
 *
 * <p>This implementation provides thread-local context management, sampling,
 * and integration with various trace exporters.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
public class DefaultTraceService implements TraceService {

    private final ThreadLocal<TraceContext> currentTrace = new ThreadLocal<>();
    private final TraceExporter traceExporter;
    private final AtomicLong totalTraces = new AtomicLong(0);
    private final AtomicLong activeTraces = new AtomicLong(0);
    private final AtomicLong completedTraces = new AtomicLong(0);
    private final AtomicLong errorTraces = new AtomicLong(0);
    private final AtomicLong totalDuration = new AtomicLong(0);
    private final AtomicLong maxDuration = new AtomicLong(0);
    private final AtomicLong minDuration = new AtomicLong(Long.MAX_VALUE);

    /**
     * Constructs a new default trace service.
     *
     * @param traceExporter the trace exporter
     */
    public DefaultTraceService(TraceExporter traceExporter) {
        this.traceExporter = traceExporter;
    }

    @Override
    public TraceContext startTrace(String operationName, String serviceName) {
        return startTrace(generateTraceId(), operationName, serviceName);
    }

    @Override
    public TraceContext startTrace(String traceId, String operationName, String serviceName) {
        String spanId = generateSpanId();
        TraceContext context = new TraceContext(traceId, spanId, null, operationName, serviceName);

        currentTrace.set(context);
        totalTraces.incrementAndGet();
        activeTraces.incrementAndGet();

        return context;
    }

    @Override
    public TraceContext getCurrentTrace() {
        return currentTrace.get();
    }

    @Override
    public void setCurrentTrace(TraceContext context) {
        currentTrace.set(context);
    }

    @Override
    public void completeCurrentTrace(TraceContext.TraceStatus status) {
        TraceContext context = currentTrace.get();
        if (context != null) {
            completeTrace(context, status);
            currentTrace.remove();
        }
    }

    @Override
    public void completeCurrentTraceWithError(Throwable error) {
        TraceContext context = currentTrace.get();
        if (context != null) {
            completeTraceWithError(context, error);
            currentTrace.remove();
        }
    }

    @Override
    public TraceContext createChildSpan(String operationName) {
        TraceContext parent = currentTrace.get();
        if (parent == null) {
            throw new IllegalStateException("No active trace context found");
        }

        TraceContext child = parent.createChildSpan(operationName);
        currentTrace.set(child);

        return child;
    }

    @Override
    public void addAttribute(String key, String value) {
        TraceContext context = currentTrace.get();
        if (context != null) {
            context.addAttribute(key, value);
        }
    }

    @Override
    public void addMetric(String key, double value) {
        TraceContext context = currentTrace.get();
        if (context != null) {
            context.addMetric(key, value);
        }
    }

    @Override
    public Map<String, String> getPropagationHeaders() {
        TraceContext context = currentTrace.get();
        Map<String, String> headers = new ConcurrentHashMap<>();

        if (context != null) {
            headers.put("X-Trace-Id", context.getTraceId());
            headers.put("X-Span-Id", context.getSpanId());
            headers.put("X-Parent-Span-Id", context.getParentSpanId() != null ? context.getParentSpanId() : "");
            headers.put("X-Operation-Name", context.getOperationName());
            headers.put("X-Service-Name", context.getServiceName());
        }

        return headers;
    }

    @Override
    public TraceContext extractFromHeaders(Map<String, String> headers) {
        String traceId = headers.get("X-Trace-Id");
        String spanId = headers.get("X-Span-Id");
        String parentSpanId = headers.get("X-Parent-Span-Id");
        String operationName = headers.get("X-Operation-Name");
        String serviceName = headers.get("X-Service-Name");

        if (traceId == null || spanId == null || operationName == null || serviceName == null) {
            return null;
        }

        TraceContext context = new TraceContext(traceId, spanId, parentSpanId, operationName, serviceName);
        currentTrace.set(context);
        activeTraces.incrementAndGet();

        return context;
    }

    @Override
    public boolean shouldSample(double samplingRate, boolean alwaysTrace) {
        if (alwaysTrace) {
            return true;
        }

        if (samplingRate <= 0.0) {
            return false;
        }

        if (samplingRate >= 1.0) {
            return true;
        }

        return ThreadLocalRandom.current().nextDouble() < samplingRate;
    }

    @Override
    public TraceStatistics getStatistics() {
        return new DefaultTraceStatistics(
                totalTraces.get(),
                activeTraces.get(),
                completedTraces.get(),
                errorTraces.get(),
                totalDuration.get(),
                maxDuration.get(),
                minDuration.get()
        );
    }

    @Override
    public void shutdown() {
        completeCurrentTrace(TraceContext.TraceStatus.COMPLETED);
        traceExporter.flush();
        traceExporter.shutdown();
    }

    /**
     * Completes a trace with the specified status.
     *
     * @param context the trace context
     * @param status the completion status
     */
    private void completeTrace(TraceContext context, TraceContext.TraceStatus status) {
        context.complete(status);

        // Update statistics
        activeTraces.decrementAndGet();
        completedTraces.incrementAndGet();

        long duration = context.getDuration();
        totalDuration.addAndGet(duration);

        // Update max/min duration
        updateDurationStats(duration);

        // Export the trace
        traceExporter.exportSpan(context);
    }

    /**
     * Completes a trace with an error.
     *
     * @param context the trace context
     * @param error the error that occurred
     */
    private void completeTraceWithError(TraceContext context, Throwable error) {
        context.completeWithError(error);

        // Update statistics
        activeTraces.decrementAndGet();
        errorTraces.incrementAndGet();
        completedTraces.incrementAndGet();

        long duration = context.getDuration();
        totalDuration.addAndGet(duration);

        // Update max/min duration
        updateDurationStats(duration);

        // Export the trace
        traceExporter.exportSpan(context);
    }

    /**
     * Updates the duration statistics.
     *
     * @param duration the duration to update
     */
    private void updateDurationStats(long duration) {
        long currentMax = maxDuration.get();
        while (duration > currentMax) {
            if (maxDuration.compareAndSet(currentMax, duration)) {
                break;
            }
            currentMax = maxDuration.get();
        }

        long currentMin = minDuration.get();
        while (duration < currentMin) {
            if (minDuration.compareAndSet(currentMin, duration)) {
                break;
            }
            currentMin = minDuration.get();
        }
    }

    /**
     * Generates a unique trace ID.
     *
     * @return a unique trace ID
     */
    private String generateTraceId() {
        return Long.toHexString(System.currentTimeMillis()) +
                Long.toHexString(Thread.currentThread().threadId()) +
                Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    /**
     * Generates a unique span ID.
     *
     * @return a unique span ID
     */
    private String generateSpanId() {
        return Long.toHexString(System.currentTimeMillis() ^ Thread.currentThread().threadId());
    }

    /**
     * Default implementation of TraceStatistics.
     */
    private static class DefaultTraceStatistics implements TraceStatistics {

        private final long totalTraces;
        private final long activeTraces;
        private final long completedTraces;
        private final long errorTraces;
        private final long totalDuration;
        private final long maxDuration;
        private final long minDuration;

        public DefaultTraceStatistics(long totalTraces, long activeTraces, long completedTraces,
                                      long errorTraces, long totalDuration, long maxDuration, long minDuration) {
            this.totalTraces = totalTraces;
            this.activeTraces = activeTraces;
            this.completedTraces = completedTraces;
            this.errorTraces = errorTraces;
            this.totalDuration = totalDuration;
            this.maxDuration = maxDuration;
            this.minDuration = minDuration;
        }

        @Override
        public long getTotalTraces() {
            return totalTraces;
        }

        @Override
        public long getActiveTraces() {
            return activeTraces;
        }

        @Override
        public long getCompletedTraces() {
            return completedTraces;
        }

        @Override
        public long getErrorTraces() {
            return errorTraces;
        }

        @Override
        public double getAverageDuration() {
            if (completedTraces == 0) {
                return 0.0;
            }
            return (double) totalDuration / completedTraces;
        }

        @Override
        public long getMaxDuration() {
            return maxDuration;
        }

        @Override
        public long getMinDuration() {
            return minDuration == Long.MAX_VALUE ? 0 : minDuration;
        }

    }

}
