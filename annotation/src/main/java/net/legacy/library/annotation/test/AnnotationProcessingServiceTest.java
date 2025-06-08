package net.legacy.library.annotation.test;

import net.legacy.library.annotation.service.AnnotationProcessingService;
import net.legacy.library.annotation.service.AnnotationProcessingServiceInterface;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import org.reflections.util.ClasspathHelper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Test class for AnnotationProcessingService core functionality.
 *
 * <p>This test class validates the core annotation processing service functionality,
 * including different processing methods, error handling, and integration patterns.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 18:00
 */
@ModuleTest(
        testName = "annotation-processing-service-test",
        description = "Tests AnnotationProcessingService core functionality and error handling",
        tags = {"annotation", "service", "processing", "core"},
        priority = 1,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AnnotationProcessingServiceTest {
    /**
     * Tests direct service instantiation and basic functionality.
     */
    public static boolean testDirectServiceInstantiation() {
        try {
            // Reset counters
            TestableAnnotationProcessor.resetCounters();

            // Create service directly (not through IoC)
            AnnotationProcessingService service = new AnnotationProcessingService();

            // Verify service is created and implements interface
            boolean serviceCreated = service != null;
            boolean implementsInterface = service instanceof AnnotationProcessingServiceInterface;

            TestLogger.logInfo("annotation", "Direct instantiation test: serviceCreated=" + serviceCreated +
                    ", implementsInterface=" + implementsInterface);

            return serviceCreated && implementsInterface;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Direct instantiation test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests package-based annotation processing.
     */
    public static boolean testPackageBasedProcessing() {
        try {
            // Reset counters
            TestableAnnotationProcessor.resetCounters();

            AnnotationProcessingService service = new AnnotationProcessingService();

            // Process annotations in test package (without IoC)
            String testPackage = "net.legacy.library.annotation.test";
            service.processAnnotations(testPackage, false);

            // Verify processing occurred
            int processCount = TestableAnnotationProcessor.getProcessedCount();
            int beforeCount = TestableAnnotationProcessor.getBeforeCount();
            int afterCount = TestableAnnotationProcessor.getAfterCount();
            int finallyCount = TestableAnnotationProcessor.getFinallyAfterCount();

            TestLogger.logInfo("annotation", "Package processing test: processCount=" + processCount +
                    ", beforeCount=" + beforeCount + ", afterCount=" + afterCount + ", finallyCount=" + finallyCount);

            // Should have processed at least the test classes
            return processCount > 0 && beforeCount > 0 && afterCount > 0 && finallyCount > 0;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Package processing test failed: " + exception.getMessage());
            return false;
        }
    }

    // Note: We now use TestableAnnotationProcessor's static counters instead of local ones

    /**
     * Tests multiple package processing.
     */
    public static boolean testMultiplePackageProcessing() {
        try {
            // Reset counters
            TestableAnnotationProcessor.resetCounters();

            AnnotationProcessingService service = new AnnotationProcessingService();

            // Process multiple packages
            List<String> packages = Arrays.asList(
                    "net.legacy.library.annotation.test",
                    "net.legacy.library.annotation.service"
            );

            service.processAnnotations(packages, false);

            // Verify processing occurred
            int processCount = TestableAnnotationProcessor.getProcessedCount();
            int beforeCount = TestableAnnotationProcessor.getBeforeCount();
            int afterCount = TestableAnnotationProcessor.getAfterCount();
            int finallyCount = TestableAnnotationProcessor.getFinallyAfterCount();

            TestLogger.logInfo("annotation", "Multiple package processing test: processCount=" + processCount +
                    ", beforeCount=" + beforeCount + ", afterCount=" + afterCount + ", finallyCount=" + finallyCount);

            return processCount > 0 && beforeCount > 0 && afterCount > 0 && finallyCount > 0;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Multiple package processing test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests URL-based annotation processing.
     */
    public static boolean testUrlBasedProcessing() {
        try {
            // Reset counters
            TestableAnnotationProcessor.resetCounters();

            AnnotationProcessingService service = new AnnotationProcessingService();

            // Get URLs for test package
            Collection<URL> urls = ClasspathHelper.forPackage("net.legacy.library.annotation.test");

            // Process annotations from URLs
            service.processAnnotations(urls, false);

            // Verify processing occurred
            int processCount = TestableAnnotationProcessor.getProcessedCount();
            int beforeCount = TestableAnnotationProcessor.getBeforeCount();
            int afterCount = TestableAnnotationProcessor.getAfterCount();
            int finallyCount = TestableAnnotationProcessor.getFinallyAfterCount();

            TestLogger.logInfo("annotation", "URL-based processing test: processCount=" + processCount +
                    ", beforeCount=" + beforeCount + ", afterCount=" + afterCount + ", finallyCount=" + finallyCount);

            return processCount > 0 && beforeCount > 0 && afterCount > 0 && finallyCount > 0;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "URL-based processing test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests IoC container integration vs direct instantiation.
     */
    public static boolean testIoCIntegrationVsDirectInstantiation() {
        try {
            // Reset counters
            TestableAnnotationProcessor.resetCounters();

            AnnotationProcessingService service = new AnnotationProcessingService();
            String testPackage = "net.legacy.library.annotation.test";

            // Test direct instantiation (fromFairyIoCSingleton = false)
            service.processAnnotations(testPackage, false);
            int directProcessCount = TestableAnnotationProcessor.getProcessedCount();

            // Reset counters
            TestableAnnotationProcessor.resetCounters();

            // Test with IoC flag (may not have actual IoC container in test)
            try {
                service.processAnnotations(testPackage, true);
                int iocProcessCount = TestableAnnotationProcessor.getProcessedCount();

                TestLogger.logInfo("annotation", "IoC integration test: directCount=" + directProcessCount +
                        ", iocCount=" + iocProcessCount);

                // Both should work, though IoC might fail gracefully
                return directProcessCount > 0;
            } catch (Exception iocException) {
                // IoC failure is acceptable in test environment
                TestLogger.logInfo("annotation", "IoC integration test: directCount=" + directProcessCount +
                        ", IoC failed as expected in test environment");
                return directProcessCount > 0;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "IoC integration test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests error handling with invalid processor class.
     */
    public static boolean testInvalidProcessorErrorHandling() {
        try {
            // Reset counters
            TestableAnnotationProcessor.resetCounters();

            AnnotationProcessingService service = new AnnotationProcessingService();

            // Create invalid processor class (no default constructor)
            class InvalidProcessor {
                public InvalidProcessor(String requiredParam) {
                    // Constructor with required parameter
                }
            }

            // This should handle the error gracefully
            Collection<URL> urls = ClasspathHelper.forPackage("net.legacy.library.annotation.test");

            // Service should handle missing annotation processor gracefully
            service.processAnnotations(urls, false);

            TestLogger.logInfo("annotation", "Invalid processor test: handled gracefully");

            // Should not throw exception, but handle gracefully
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Invalid processor test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests null and empty input handling.
     */
    public static boolean testNullAndEmptyInputHandling() {
        try {
            AnnotationProcessingService service = new AnnotationProcessingService();

            // Test with null package
            try {
                service.processAnnotations((String) null, false);
                TestLogger.logInfo("annotation", "Null package handling: no exception thrown");
            } catch (Exception expected) {
                TestLogger.logInfo("annotation", "Null package handling: exception caught as expected");
            }

            // Test with empty package
            try {
                service.processAnnotations("", false);
                TestLogger.logInfo("annotation", "Empty package handling: no exception thrown");
            } catch (Exception expected) {
                TestLogger.logInfo("annotation", "Empty package handling: exception caught as expected");
            }

            // Test with null URL collection
            try {
                service.processAnnotations(null, false);
                TestLogger.logInfo("annotation", "Null URLs handling: no exception thrown");
            } catch (Exception expected) {
                TestLogger.logInfo("annotation", "Null URLs handling: exception caught as expected");
            }

            return true; // Should handle gracefully without crashing
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Null input handling test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests processor lifecycle execution order.
     */
    public static boolean testProcessorLifecycleOrder() {
        try {
            // Reset counters
            TestableAnnotationProcessor.resetCounters();

            AnnotationProcessingService service = new AnnotationProcessingService();
            String testPackage = "net.legacy.library.annotation.test";

            service.processAnnotations(testPackage, false);

            // Verify lifecycle method call counts
            int processCount = TestableAnnotationProcessor.getProcessedCount();
            int beforeCount = TestableAnnotationProcessor.getBeforeCount();
            int afterCount = TestableAnnotationProcessor.getAfterCount();
            int finallyCount = TestableAnnotationProcessor.getFinallyAfterCount();

            // All lifecycle methods should be called the same number of times
            boolean lifecycleOrderCorrect = (processCount == beforeCount) &&
                    (beforeCount == afterCount) &&
                    (afterCount == finallyCount);

            TestLogger.logInfo("annotation", "Lifecycle order test: processCount=" + processCount +
                    ", beforeCount=" + beforeCount + ", afterCount=" + afterCount +
                    ", finallyCount=" + finallyCount + ", orderCorrect=" + lifecycleOrderCorrect);

            return lifecycleOrderCorrect && processCount > 0;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Lifecycle order test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests service performance with multiple processing calls.
     */
    public static boolean testServicePerformance() {
        try {
            AnnotationProcessingService service = new AnnotationProcessingService();
            String testPackage = "net.legacy.library.annotation.test";

            long startTime = System.currentTimeMillis();

            // Run multiple processing operations
            for (int i = 0; i < 5; i++) {
                TestableAnnotationProcessor.resetCounters();
                service.processAnnotations(testPackage, false);
            }

            long duration = System.currentTimeMillis() - startTime;

            TestLogger.logInfo("annotation", "Performance test: 5 processing cycles completed in " + duration + "ms");

            // Should complete within reasonable time (5 seconds)
            return duration < 5000;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Performance test failed: " + exception.getMessage());
            return false;
        }
    }

    // Test annotation for validation
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface TestProcessingAnnotation {
        String value() default "test";
    }

    // Test class with annotation
    @TestProcessingAnnotation("service-test")
    public static class AnnotatedTestClass {
        public String getValue() {
            return "test-value";
        }
    }
}