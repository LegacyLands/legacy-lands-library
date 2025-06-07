package net.legacy.library.annotation.test;

import io.fairyproject.container.Containers;
import io.fairyproject.log.Log;
import net.legacy.library.annotation.service.AnnotationProcessingService;
import net.legacy.library.annotation.service.AnnotationProcessingServiceInterface;

/**
 * Test runner for validating annotation processing functionality during debug mode.
 *
 * <p>This class orchestrates the execution of annotation processing tests, including
 * running the annotation processor on test classes and validating the results.
 * It provides comprehensive testing coverage for the annotation processing framework.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 21:30
 */
public class AnnotationTestRunner {
    private static final String TEST_PACKAGE = "net.legacy.library.annotation.test";
    
    private final AnnotationProcessingServiceInterface annotationProcessingService;
    private final TestableAnnotationProcessor testProcessor;

    /**
     * Constructor for AnnotationTestRunner.
     *
     * @param annotationProcessingService the annotation processing service
     * @param testProcessor              the test processor instance
     */
    public AnnotationTestRunner(AnnotationProcessingServiceInterface annotationProcessingService, 
                               TestableAnnotationProcessor testProcessor) {
        this.annotationProcessingService = annotationProcessingService;
        this.testProcessor = testProcessor;
    }

    /**
     * Runs all annotation processing tests and validates results.
     *
     * @return the test result summary
     */
    public TestResultSummary runTests() {
        Log.info("[AnnotationTestRunner] Starting annotation processing tests...");
        
        // Clear previous test results
        TestResultRegistry.clearResults();
        testProcessor.resetCounters();
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Run annotation processing on the test package
            annotationProcessingService.processAnnotations(TEST_PACKAGE, true);
            
            // Wait a moment for processing to complete
            Thread.sleep(200);
            
            // Validate results
            return validateTestResults(startTime);
            
        } catch (Exception exception) {
            Log.error("[AnnotationTestRunner] Unexpected error during test execution", exception);
            return TestResultSummary.failure("Unexpected error: " + exception.getMessage(), 
                    System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Validates the test results and generates a summary.
     *
     * @param startTime the test start time
     * @return the test result summary
     */
    private TestResultSummary validateTestResults(long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        
        Log.info("[AnnotationTestRunner] Validating test results...");
        
        // Check expected successful processing
        boolean simpleTestSuccess = TestResultRegistry.wasProcessedSuccessfully(SimpleTestClass.class, "simple-test");
        boolean complexTestSuccess = TestResultRegistry.wasProcessedSuccessfully(ComplexTestClass.class, "complex-test");
        
        // Check expected error processing
        boolean errorTestFailed = TestResultRegistry.wasProcessingFailed(ErrorTestClass.class, "error-test");
        
        // Validate processor counters
        int expectedProcessCalls = 3; // SimpleTestClass, ComplexTestClass, ErrorTestClass
        int actualProcessCalls = testProcessor.getProcessedCount();
        
        // Build validation results
        StringBuilder validationResults = new StringBuilder();
        boolean allTestsPassed = true;
        
        // Validate successful processing
        if (!simpleTestSuccess) {
            validationResults.append("❌ SimpleTestClass was not processed successfully\n");
            allTestsPassed = false;
        } else {
            validationResults.append("✅ SimpleTestClass processed successfully\n");
        }
        
        if (!complexTestSuccess) {
            validationResults.append("❌ ComplexTestClass was not processed successfully\n");
            allTestsPassed = false;
        } else {
            validationResults.append("✅ ComplexTestClass processed successfully\n");
        }
        
        // Validate error handling
        if (!errorTestFailed) {
            validationResults.append("❌ ErrorTestClass should have failed but didn't\n");
            allTestsPassed = false;
        } else {
            validationResults.append("✅ ErrorTestClass failed as expected\n");
        }
        
        // Validate processor call counts
        if (actualProcessCalls != expectedProcessCalls) {
            validationResults.append(String.format("❌ Expected %d process calls but got %d\n", 
                    expectedProcessCalls, actualProcessCalls));
            allTestsPassed = false;
        } else {
            validationResults.append(String.format("✅ Process method called %d times as expected\n", actualProcessCalls));
        }
        
        // Validate lifecycle method calls
        if (testProcessor.getFinallyAfterCount() != expectedProcessCalls) {
            validationResults.append(String.format("❌ finallyAfter should be called %d times but was called %d times\n", 
                    expectedProcessCalls, testProcessor.getFinallyAfterCount()));
            allTestsPassed = false;
        } else {
            validationResults.append("✅ finallyAfter method called correctly for all classes\n");
        }
        
        // Log detailed results
        Log.info("[AnnotationTestRunner] Test Results Summary:");
        Log.info("Processed classes: %d", TestResultRegistry.getProcessedCount());
        Log.info("Failed classes: %d", TestResultRegistry.getFailedCount());
        Log.info("Process method calls: %d", testProcessor.getProcessedCount());
        Log.info("Before method calls: %d", testProcessor.getBeforeCount());
        Log.info("After method calls: %d", testProcessor.getAfterCount());
        Log.info("Exception method calls: %d", testProcessor.getExceptionCount());
        Log.info("Finally after method calls: %d", testProcessor.getFinallyAfterCount());
        
        if (allTestsPassed) {
            String successMessage = "All annotation processing tests passed! " + validationResults.toString();
            Log.info("[AnnotationTestRunner] ✅ %s", successMessage);
            return TestResultSummary.success(successMessage, duration);
        } else {
            String failureMessage = "Some annotation processing tests failed: " + validationResults.toString();
            Log.warn("[AnnotationTestRunner] ❌ %s", failureMessage);
            return TestResultSummary.failure(failureMessage, duration);
        }
    }

    /**
     * Creates a test runner instance using dependency injection.
     *
     * @return the configured test runner
     */
    public static AnnotationTestRunner create() {
        try {
            AnnotationProcessingServiceInterface processingService = Containers.get(AnnotationProcessingService.class);
            TestableAnnotationProcessor testProcessor = Containers.get(TestableAnnotationProcessor.class);
            return new AnnotationTestRunner(processingService, testProcessor);
        } catch (Exception exception) {
            Log.error("Failed to create AnnotationTestRunner", exception);
            throw new RuntimeException("Failed to create test runner", exception);
        }
    }

    /**
     * Summary of test execution results.
     */
    public static class TestResultSummary {
        private final boolean success;
        private final String message;
        private final long durationMs;

        private TestResultSummary(boolean success, String message, long durationMs) {
            this.success = success;
            this.message = message;
            this.durationMs = durationMs;
        }

        /**
         * Creates a successful test result summary.
         *
         * @param message    the success message
         * @param durationMs the test duration in milliseconds
         * @return the test result summary
         */
        public static TestResultSummary success(String message, long durationMs) {
            return new TestResultSummary(true, message, durationMs);
        }

        /**
         * Creates a failed test result summary.
         *
         * @param message    the failure message
         * @param durationMs the test duration in milliseconds
         * @return the test result summary
         */
        public static TestResultSummary failure(String message, long durationMs) {
            return new TestResultSummary(false, message, durationMs);
        }

        /**
         * Checks if the tests were successful.
         *
         * @return true if all tests passed
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * Gets the result message.
         *
         * @return the result message
         */
        public String getMessage() {
            return message;
        }

        /**
         * Gets the test duration in milliseconds.
         *
         * @return the duration in milliseconds
         */
        public long getDurationMs() {
            return durationMs;
        }
    }
}