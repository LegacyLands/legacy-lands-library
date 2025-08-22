package net.legacy.library.foundation.test;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Execution context for module testing, providing shared state and metrics.
 *
 * <p>This class maintains execution state, performance metrics, and shared
 * data during test execution. It provides thread-safe counters and timing
 * utilities for comprehensive test monitoring.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 22:30
 */
@Getter
@Setter
@Accessors(chain = true)
public class TestExecutionContext {

    /**
     * The module name being tested.
     */
    private final String moduleName;

    /**
     * Test execution start time in milliseconds.
     */
    private final long startTime;

    /**
     * Thread-safe counters for various test metrics.
     */
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger exceptionCount = new AtomicInteger(0);

    /**
     * Thread-safe timing metrics.
     */
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final AtomicLong averageProcessingTime = new AtomicLong(0);

    /**
     * Shared context data that can be used by test implementations.
     */
    private final Map<String, Object> contextData = new ConcurrentHashMap<>();

    /**
     * Test configuration properties.
     */
    private final Map<String, String> configuration = new ConcurrentHashMap<>();

    /**
     * Whether the test execution is in debug mode.
     */
    private boolean debugMode = false;

    /**
     * Whether to collect detailed performance metrics.
     */
    private boolean collectMetrics = true;

    /**
     * Creates a new test execution context.
     *
     * @param moduleName the name of the module being tested
     */
    public TestExecutionContext(String moduleName) {
        this.moduleName = moduleName;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Creates a new test execution context with configuration.
     *
     * @param moduleName    the name of the module being tested
     * @param configuration initial configuration properties
     */
    public TestExecutionContext(String moduleName, Map<String, String> configuration) {
        this.moduleName = moduleName;
        this.startTime = System.currentTimeMillis();
        if (configuration != null) {
            this.configuration.putAll(configuration);
        }
    }

    /**
     * Increments the processed count and returns the new value.
     *
     * @return the new processed count
     */
    public int incrementProcessed() {
        return processedCount.incrementAndGet();
    }

    /**
     * Increments the success count and returns the new value.
     *
     * @return the new success count
     */
    public int incrementSuccess() {
        return successCount.incrementAndGet();
    }

    /**
     * Increments the failure count and returns the new value.
     *
     * @return the new failure count
     */
    public int incrementFailure() {
        return failureCount.incrementAndGet();
    }

    /**
     * Increments the exception count and returns the new value.
     *
     * @return the new exception count
     */
    public int incrementException() {
        return exceptionCount.incrementAndGet();
    }

    /**
     * Records processing time for a single operation.
     *
     * @param processingTimeMs the processing time in milliseconds
     */
    public void recordProcessingTime(long processingTimeMs) {
        if (collectMetrics) {
            totalProcessingTime.addAndGet(processingTimeMs);
            updateAverageProcessingTime();
        }
    }

    /**
     * Gets the total elapsed time since context creation.
     *
     * @return elapsed time in milliseconds
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Puts data into the shared context.
     *
     * @param key   the data key
     * @param value the data value
     */
    public void putContextData(String key, Object value) {
        contextData.put(key, value);
    }

    /**
     * Gets data from the shared context.
     *
     * @param key the data key
     * @param <T> the expected type
     * @return the data value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getContextData(String key) {
        return (T) contextData.get(key);
    }

    /**
     * Gets data from the shared context with a default value.
     *
     * @param key          the data key
     * @param defaultValue the default value
     * @param <T>          the expected type
     * @return the data value, or default value if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getContextData(String key, T defaultValue) {
        return (T) contextData.getOrDefault(key, defaultValue);
    }

    /**
     * Sets a configuration property.
     *
     * @param key   the property key
     * @param value the property value
     */
    public void setConfiguration(String key, String value) {
        configuration.put(key, value);
    }

    /**
     * Gets a configuration property.
     *
     * @param key the property key
     * @return the property value, or null if not found
     */
    public String getConfiguration(String key) {
        return configuration.get(key);
    }

    /**
     * Gets a configuration property with a default value.
     *
     * @param key          the property key
     * @param defaultValue the default value
     * @return the property value, or default value if not found
     */
    public String getConfiguration(String key, String defaultValue) {
        return configuration.getOrDefault(key, defaultValue);
    }

    /**
     * Resets all counters and metrics.
     */
    public void reset() {
        processedCount.set(0);
        successCount.set(0);
        failureCount.set(0);
        exceptionCount.set(0);
        totalProcessingTime.set(0);
        averageProcessingTime.set(0);
        contextData.clear();
    }

    /**
     * Creates a summary map of current metrics.
     *
     * @return a map containing all current metrics
     */
    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("moduleName", moduleName);
        metrics.put("elapsedTime", getElapsedTime());
        metrics.put("processedCount", processedCount.get());
        metrics.put("successCount", successCount.get());
        metrics.put("failureCount", failureCount.get());
        metrics.put("exceptionCount", exceptionCount.get());
        metrics.put("totalProcessingTime", totalProcessingTime.get());
        metrics.put("averageProcessingTime", averageProcessingTime.get());
        metrics.put("debugMode", debugMode);
        metrics.put("collectMetrics", collectMetrics);
        return metrics;
    }

    /**
     * Updates the average processing time based on current metrics.
     */
    private void updateAverageProcessingTime() {
        int processed = processedCount.get();
        if (processed > 0) {
            long average = totalProcessingTime.get() / processed;
            averageProcessingTime.set(average);
        }
    }

}