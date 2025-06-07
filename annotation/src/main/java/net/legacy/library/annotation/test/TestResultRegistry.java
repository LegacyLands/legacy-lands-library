package net.legacy.library.annotation.test;

import lombok.Getter;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for tracking test results during annotation processing validation.
 *
 * <p>This utility class maintains thread-safe collections of processed and failed classes
 * during debug mode testing. It provides methods to register test results and retrieve
 * them for validation purposes.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 21:30
 */
@UtilityClass
public class TestResultRegistry {
    private static final Map<String, ProcessedClassInfo> processedClasses = new ConcurrentHashMap<>();
    private static final Map<String, FailedClassInfo> failedClasses = new ConcurrentHashMap<>();

    /**
     * Registers a successfully processed class.
     *
     * @param clazz      the processed class
     * @param testName   the test name from the annotation
     * @param result     the processing result
     */
    public static void registerProcessedClass(Class<?> clazz, String testName, String result) {
        String key = generateKey(clazz, testName);
        processedClasses.put(key, new ProcessedClassInfo(clazz, testName, result, System.currentTimeMillis()));
    }

    /**
     * Registers a failed class processing.
     *
     * @param clazz        the failed class
     * @param testName     the test name from the annotation
     * @param errorMessage the error message
     */
    public static void registerFailedClass(Class<?> clazz, String testName, String errorMessage) {
        String key = generateKey(clazz, testName);
        failedClasses.put(key, new FailedClassInfo(clazz, testName, errorMessage, System.currentTimeMillis()));
    }

    /**
     * Gets all processed classes.
     *
     * @return a map of processed class information
     */
    public static Map<String, ProcessedClassInfo> getProcessedClasses() {
        return Map.copyOf(processedClasses);
    }

    /**
     * Gets all failed classes.
     *
     * @return a map of failed class information
     */
    public static Map<String, FailedClassInfo> getFailedClasses() {
        return Map.copyOf(failedClasses);
    }

    /**
     * Checks if a specific class with test name was processed successfully.
     *
     * @param clazz    the class to check
     * @param testName the test name
     * @return true if the class was processed successfully
     */
    public static boolean wasProcessedSuccessfully(Class<?> clazz, String testName) {
        String key = generateKey(clazz, testName);
        return processedClasses.containsKey(key) && !failedClasses.containsKey(key);
    }

    /**
     * Checks if a specific class with test name failed processing.
     *
     * @param clazz    the class to check
     * @param testName the test name
     * @return true if the class failed processing
     */
    public static boolean wasProcessingFailed(Class<?> clazz, String testName) {
        String key = generateKey(clazz, testName);
        return failedClasses.containsKey(key);
    }

    /**
     * Gets the total number of processed classes.
     *
     * @return the count of processed classes
     */
    public static int getProcessedCount() {
        return processedClasses.size();
    }

    /**
     * Gets the total number of failed classes.
     *
     * @return the count of failed classes
     */
    public static int getFailedCount() {
        return failedClasses.size();
    }

    /**
     * Clears all test results.
     */
    public static void clearResults() {
        processedClasses.clear();
        failedClasses.clear();
    }

    /**
     * Generates a unique key for a class and test name combination.
     *
     * @param clazz    the class
     * @param testName the test name
     * @return the generated key
     */
    private static String generateKey(Class<?> clazz, String testName) {
        return clazz.getName() + "#" + testName;
    }

    /**
     * Information about a successfully processed class.
     */
    @Getter
    public static class ProcessedClassInfo {
        private final Class<?> clazz;
        private final String testName;
        private final String result;
        private final long timestamp;

        public ProcessedClassInfo(Class<?> clazz, String testName, String result, long timestamp) {
            this.clazz = clazz;
            this.testName = testName;
            this.result = result;
            this.timestamp = timestamp;
        }
    }

    /**
     * Information about a failed class processing.
     */
    @Getter
    public static class FailedClassInfo {
        private final Class<?> clazz;
        private final String testName;
        private final String errorMessage;
        private final long timestamp;

        public FailedClassInfo(Class<?> clazz, String testName, String errorMessage, long timestamp) {
            this.clazz = clazz;
            this.testName = testName;
            this.errorMessage = errorMessage;
            this.timestamp = timestamp;
        }
    }
}