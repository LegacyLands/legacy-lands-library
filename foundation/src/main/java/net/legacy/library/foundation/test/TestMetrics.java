package net.legacy.library.foundation.test;

import lombok.Builder;
import lombok.Value;
import lombok.With;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Comprehensive test metrics collection for performance analysis.
 *
 * <p>This class captures detailed metrics during test execution, including
 * timing information, throughput measurements, resource usage, and custom
 * performance indicators. It uses Lombok extensively for clean, immutable
 * data structures.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 22:30
 */
@With
@Value
@Builder(toBuilder = true)
@Accessors(fluent = true)
public class TestMetrics {
    /**
     * Test execution start timestamp.
     */
    Instant startTime;

    /**
     * Test execution end timestamp.
     */
    Instant endTime;

    /**
     * Total execution duration in milliseconds.
     */
    long durationMs;

    /**
     * Number of operations processed.
     */
    @Builder.Default
    int operationsCount = 0;

    /**
     * Number of successful operations.
     */
    @Builder.Default
    int successCount = 0;

    /**
     * Number of failed operations.
     */
    @Builder.Default
    int failureCount = 0;

    /**
     * Operations per second (throughput).
     */
    @Builder.Default
    double operationsPerSecond = 0.0;

    /**
     * Average operation duration in milliseconds.
     */
    @Builder.Default
    double averageOperationDurationMs = 0.0;

    /**
     * Minimum operation duration in milliseconds.
     */
    @Builder.Default
    long minOperationDurationMs = Long.MAX_VALUE;

    /**
     * Maximum operation duration in milliseconds.
     */
    @Builder.Default
    long maxOperationDurationMs = 0L;

    /**
     * Memory usage before test execution in bytes.
     */
    @Builder.Default
    long memoryUsageBeforeBytes = 0L;

    /**
     * Memory usage after test execution in bytes.
     */
    @Builder.Default
    long memoryUsageAfterBytes = 0L;

    /**
     * Peak memory usage during test execution in bytes.
     */
    @Builder.Default
    long peakMemoryUsageBytes = 0L;

    /**
     * Custom metrics collected during test execution.
     */
    @Builder.Default
    Map<String, Object> customMetrics = new HashMap<>();

    /**
     * Performance percentiles (e.g., p50, p95, p99).
     */
    @Builder.Default
    Map<String, Double> percentiles = new HashMap<>();

    /**
     * Creates a basic metrics instance with start time.
     *
     * @return a new TestMetrics builder with start time set
     */
    public static TestMetricsBuilder create() {
        return TestMetrics.builder()
                .startTime(Instant.now());
    }

    /**
     * Calculates and returns derived metrics.
     *
     * @return a new TestMetrics instance with calculated fields
     */
    public TestMetrics calculate() {
        TestMetricsBuilder builder = toBuilder();

        // Calculate duration if end time is set
        if (endTime != null && startTime != null) {
            long duration = endTime.toEpochMilli() - startTime.toEpochMilli();
            builder.durationMs(duration);

            // Calculate throughput
            if (duration > 0) {
                double throughput = (operationsCount * 1000.0) / duration;
                builder.operationsPerSecond(throughput);
            }
        }

        // Calculate average operation duration
        if (operationsCount > 0 && durationMs > 0) {
            double avgDuration = (double) durationMs / operationsCount;
            builder.averageOperationDurationMs(avgDuration);
        }

        // Calculate memory delta
        if (memoryUsageAfterBytes > 0 && memoryUsageBeforeBytes > 0) {
            long memoryDelta = memoryUsageAfterBytes - memoryUsageBeforeBytes;
            builder.customMetrics(
                    withCustomMetric("memoryDeltaBytes", memoryDelta).customMetrics
            );
        }

        return builder.build();
    }

    /**
     * Adds a custom metric.
     *
     * @param key   the metric key
     * @param value the metric value
     * @return a new TestMetrics instance with the added metric
     */
    public TestMetrics withCustomMetric(String key, Object value) {
        Map<String, Object> newMetrics = new HashMap<>(customMetrics);
        newMetrics.put(key, value);
        return withCustomMetrics(newMetrics);
    }

    /**
     * Adds a performance percentile.
     *
     * @param percentile the percentile label (e.g., "p95")
     * @param value      the percentile value
     * @return a new TestMetrics instance with the added percentile
     */
    public TestMetrics withPercentile(String percentile, double value) {
        Map<String, Double> newPercentiles = new HashMap<>(percentiles);
        newPercentiles.put(percentile, value);
        return withPercentiles(newPercentiles);
    }

    /**
     * Gets a custom metric value.
     *
     * @param key the metric key
     * @param <T> the expected type
     * @return the metric value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getCustomMetric(String key) {
        return (T) customMetrics.get(key);
    }

    /**
     * Gets a custom metric value with a default.
     *
     * @param key          the metric key
     * @param defaultValue the default value
     * @param <T>          the expected type
     * @return the metric value, or default if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getCustomMetric(String key, T defaultValue) {
        return (T) customMetrics.getOrDefault(key, defaultValue);
    }

    /**
     * Calculates the success rate as a percentage.
     *
     * @return the success rate (0.0 to 100.0)
     */
    public double getSuccessRate() {
        if (operationsCount == 0) {
            return 0.0;
        }
        return ((double) successCount / operationsCount) * 100.0;
    }

    /**
     * Calculates the failure rate as a percentage.
     *
     * @return the failure rate (0.0 to 100.0)
     */
    public double getFailureRate() {
        if (operationsCount == 0) {
            return 0.0;
        }
        return ((double) failureCount / operationsCount) * 100.0;
    }

    /**
     * Creates a summary string of key metrics.
     *
     * @return a formatted metrics summary
     */
    public String getSummary() {
        return new StringBuilder()
                .append("TestMetrics{duration=")
                .append(durationMs)
                .append("ms, operations=")
                .append(operationsCount)
                .append(", success=")
                .append(getSuccessRate())
                .append("%, throughput=")
                .append(operationsPerSecond)
                .append(" ops/sec, avgDuration=")
                .append(averageOperationDurationMs)
                .append("ms}")
                .toString();
    }

    /**
     * Creates a detailed string representation of all metrics.
     *
     * @return a detailed metrics report
     */
    public String getDetailedReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Test Metrics Report ===\n");
        report.append("Duration: ").append(durationMs).append("ms\n");
        report.append("Operations: ").append(operationsCount)
                .append(" (Success: ").append(successCount)
                .append(", Failed: ").append(failureCount).append(")\n");
        report.append("Success Rate: ").append(getSuccessRate()).append("%\n");
        report.append("Throughput: ").append(operationsPerSecond).append(" ops/sec\n");
        report.append("Avg Duration: ").append(averageOperationDurationMs).append("ms\n");
        report.append("Min/Max Duration: ")
                .append(minOperationDurationMs == Long.MAX_VALUE ? 0 : minOperationDurationMs)
                .append("ms / ").append(maxOperationDurationMs).append("ms\n");

        if (memoryUsageAfterBytes > 0) {
            report.append("Memory: Before=").append(memoryUsageBeforeBytes)
                    .append(" bytes, After=").append(memoryUsageAfterBytes)
                    .append(" bytes, Peak=").append(peakMemoryUsageBytes)
                    .append(" bytes\n");
        }

        if (!percentiles.isEmpty()) {
            report.append("Percentiles: ");
            percentiles.forEach((p, v) -> report.append(p)
                    .append("=").append(v).append("ms "));
            report.append("\n");
        }

        if (!customMetrics.isEmpty()) {
            report.append("Custom Metrics:\n");
            customMetrics.forEach((k, v) -> report.append("  ")
                    .append(k).append(": ").append(v).append("\n"));
        }

        return report.toString();
    }
}