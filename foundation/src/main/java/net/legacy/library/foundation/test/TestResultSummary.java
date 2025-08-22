package net.legacy.library.foundation.test;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.HashMap;
import java.util.Map;

/**
 * Standardized test result summary for module-level testing.
 *
 * <p>This class provides a unified representation of test execution results
 * across all modules in the Legacy Lands Library. It includes execution
 * metrics, success/failure status, and extensible metadata support.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 22:30
 */
@With
@Value
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TestResultSummary {

    /**
     * The name of the module that executed the tests.
     */
    String moduleName;

    /**
     * Whether all tests passed successfully.
     */
    boolean success;

    /**
     * Detailed message describing the test results.
     */
    String message;

    /**
     * Test execution duration in milliseconds.
     */
    long durationMs;

    /**
     * Additional metadata and metrics from test execution.
     */
    @Builder.Default
    Map<String, Object> metadata = new HashMap<>();

    /**
     * Creates a successful test result summary.
     *
     * @param moduleName the name of the module
     * @param message    the success message
     * @param durationMs the test duration in milliseconds
     * @return the test result summary
     */
    public static TestResultSummary success(String moduleName, String message, long durationMs) {
        return TestResultSummary.builder()
                .moduleName(moduleName)
                .success(true)
                .message(message)
                .durationMs(durationMs)
                .build();
    }

    /**
     * Creates a failed test result summary.
     *
     * @param moduleName the name of the module
     * @param message    the failure message
     * @param durationMs the test duration in milliseconds
     * @return the test result summary
     */
    public static TestResultSummary failure(String moduleName, String message, long durationMs) {
        return TestResultSummary.builder()
                .moduleName(moduleName)
                .success(false)
                .message(message)
                .durationMs(durationMs)
                .build();
    }

    /**
     * Creates a test result summary with custom metadata.
     *
     * @param moduleName the name of the module
     * @param success    whether tests passed
     * @param message    the result message
     * @param durationMs the test duration in milliseconds
     * @param metadata   additional metadata
     * @return the test result summary
     */
    public static TestResultSummary withMetadata(String moduleName, boolean success, String message,
                                                 long durationMs, Map<String, Object> metadata) {
        return TestResultSummary.builder()
                .moduleName(moduleName)
                .success(success)
                .message(message)
                .durationMs(durationMs)
                .metadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>())
                .build();
    }

    /**
     * Adds metadata to the current result summary.
     *
     * @param key   the metadata key
     * @param value the metadata value
     * @return a new test result summary with the added metadata
     */
    public TestResultSummary withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new HashMap<>(this.metadata);
        newMetadata.put(key, value);
        return withMetadata(newMetadata);
    }

    /**
     * Gets a metadata value by key.
     *
     * @param key the metadata key
     * @param <T> the expected type
     * @return the metadata value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    /**
     * Gets a metadata value by key with a default value.
     *
     * @param key          the metadata key
     * @param defaultValue the default value if key not found
     * @param <T>          the expected type
     * @return the metadata value, or default value if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, T defaultValue) {
        return (T) metadata.getOrDefault(key, defaultValue);
    }

}