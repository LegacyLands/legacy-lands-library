package net.legacy.library.player.test;

import net.legacy.library.foundation.annotation.TestConfiguration;
import net.legacy.library.foundation.test.AbstractModuleTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Player module test runner for data service and cache management components.
 *
 * <p>This test runner validates the critical player data management components in the player module,
 * focusing on multi-level caching systems, Redis Stream processing, and MongoDB persistence.
 * The test suite ensures reliability and correctness of enterprise-grade data services used
 * throughout the Legacy Lands Library player management ecosystem.
 *
 * <p>All tests require active Redis (localhost:6379) and MongoDB (localhost:27017) connections
 * for integration testing with real database backends.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 02:15
 */
@TestConfiguration(
        continueOnFailure = false,
        verboseLogging = true,
        debugMode = true,
        maxConcurrency = 1,
        testPackages = {"net.legacy.library.player.test"},
        globalTimeout = 45000,
        enableCaching = true,
        failFast = true
)
public class PlayerTestRunner extends AbstractModuleTestRunner {
    private static final String MODULE_NAME = "player";

    private final TestTimer timer = new TestTimer();
    private int totalTests = 0;
    private int passedTests = 0;
    private int failedTests = 0;

    /**
     * Create Player module test runner
     */
    public PlayerTestRunner() {
        super(MODULE_NAME);
    }

    /**
     * Create Player module test runner
     */
    public static PlayerTestRunner create() {
        return new PlayerTestRunner();
    }

    @Override
    protected void beforeTests() throws Exception {
        TestLogger.logTestStart(MODULE_NAME, "player-module-integration-tests");

        timer.startTimer("total-execution");
        timer.startTimer("setup");

        // Reset counters
        totalTests = 0;
        passedTests = 0;
        failedTests = 0;

        // Set random seed for test repeatability
        System.setProperty("test.random.seed", "54321");

        // Log database connection requirements
        TestLogger.logInfo(MODULE_NAME, "Integration tests require Redis (localhost:6379) and MongoDB (localhost:27017)");
    }

    @Override
    protected void executeTests() throws Exception {
        timer.stopTimer("setup");
        timer.startTimer("test-execution");

        TestLogger.logInfo(MODULE_NAME, "Running player module integration tests...");

        executeTestClass(LegacyPlayerDataServiceTest.class, "LegacyPlayerDataService Integration");
        executeTestClass(LegacyEntityDataServiceTest.class, "LegacyEntityDataService Integration");
        executeTestClass(OptimisticLockingIntegrationTest.class, "OptimisticLockingIntegrationTest");
        executeTestClass(ErrorRecoveryIntegrationTest.class, "ErrorRecoveryIntegrationTest");
        executeTestClass(ErrorRecoveryEntityIntegrationTest.class, "ErrorRecoveryEntityIntegrationTest");
        executeTestClass(ResilientIntegrationTest.class, "ResilientIntegrationTest");
        executeTestClass(PerformanceStressTest.class, "PerformanceStressTest");
        executeTestClass(LockContentionQPSTest.class, "LockContentionQPSTest");

        timer.stopTimer("test-execution");
        timer.startTimer("validation");

        // Validate overall results
        validateTestResults();

        timer.stopTimer("validation");
    }

    @Override
    protected void afterTests() throws Exception {
        timer.stopTimer("total-execution");

        TestLogger.logTestComplete(MODULE_NAME, "player-module-integration-tests",
                timer.getTimerResult("total-execution").getDuration());

        // Log final statistics
        TestLogger.logStatistics(MODULE_NAME, totalTests, passedTests, failedTests,
                timer.getTimerResult("total-execution").getDuration());
    }

    /**
     * Execute all test methods in a test class
     */
    private void executeTestClass(Class<?> testClass, String testDescription) {
        TestLogger.logInfo(MODULE_NAME, "Executing %s tests...", testDescription);

        List<String> testMethods = getTestMethods(testClass);

        for (String methodName : testMethods) {
            executeTestMethod(testClass, methodName);
        }
    }

    /**
     * Get all static test methods from a test class
     */
    private List<String> getTestMethods(Class<?> testClass) {
        List<String> testMethods = new ArrayList<>();

        for (Method method : testClass.getDeclaredMethods()) {
            if (method.getName().startsWith("test") &&
                    method.getReturnType() == boolean.class &&
                    method.getParameterCount() == 0 &&
                    java.lang.reflect.Modifier.isStatic(method.getModifiers())) {

                testMethods.add(method.getName());
            }
        }

        return testMethods;
    }

    /**
     * Execute a single static test method
     */
    private void executeTestMethod(Class<?> testClass, String methodName) {
        totalTests++;
        timer.startTimer(methodName);

        try {
            Method method = testClass.getMethod(methodName);
            boolean result = (Boolean) method.invoke(null);

            if (result) {
                passedTests++;
                TestLogger.logValidation(MODULE_NAME, methodName, true,
                        "Integration test completed successfully");

                // Update context
                context.incrementProcessed();
                context.incrementSuccess();
            } else {
                failedTests++;
                TestLogger.logValidation(MODULE_NAME, methodName, false,
                        "Integration test failed - returned false");

                context.incrementProcessed();
                context.incrementFailure();
            }

        } catch (Exception exception) {
            failedTests++;
            TestLogger.logValidation(MODULE_NAME, methodName, false,
                    "Integration test failed with exception: " + exception.getMessage());

            context.incrementProcessed();
            context.incrementFailure();

        } finally {
            timer.stopTimer(methodName);
        }
    }

    /**
     * Validate overall test results
     */
    private void validateTestResults() {
        TestLogger.logInfo(MODULE_NAME, "Validating player module integration test results...");

        // Validate reasonable test count for integration tests
        validateResult(totalTests >= 40, "Should have at least 40 integration test methods");

        // Validate all tests pass (100% success rate required for integration tests)
        double successRate = totalTests > 0 ? (double) passedTests / totalTests : 0.0;
        validateResult(successRate == 1.0, "Success rate must be 100% - all integration tests must pass");

        // Validate critical test categories are covered
        boolean hasServiceCreationTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Service") || name.contains("Creation"));
        boolean hasCacheTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Cache") || name.contains("L1") || name.contains("L2"));
        boolean hasPersistenceTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Database") || name.contains("Persistence"));
        boolean hasTTLTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("TTL") || name.contains("Expiration"));
        boolean hasManagementTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Management") || name.contains("Shutdown"));
        boolean hasPerformanceTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Performance") || name.contains("QPS") || name.contains("Stress"));

        validateResult(hasServiceCreationTests, "Should include service creation tests");
        validateResult(hasCacheTests, "Should include cache operation tests");
        validateResult(hasPersistenceTests, "Should include database persistence tests");
        validateResult(hasTTLTests, "Should include TTL management tests");
        validateResult(hasManagementTests, "Should include service management tests");
        validateResult(hasPerformanceTests, "Should include performance stress tests");

        // Log detailed validation results
        TestLogger.logValidation(MODULE_NAME, "TotalTestCount", totalTests >= 40,
                "Total integration tests: " + totalTests);
        TestLogger.logValidation(MODULE_NAME, "SuccessRate", successRate == 1.0,
                "Success rate: " + (successRate * 100) + "% (" + passedTests + "/" + totalTests + ") - 100% required");
        TestLogger.logValidation(MODULE_NAME, "ServiceCreationCoverage", hasServiceCreationTests,
                "Service creation test coverage");
        TestLogger.logValidation(MODULE_NAME, "CacheOperationCoverage", hasCacheTests,
                "Cache operation test coverage");
        TestLogger.logValidation(MODULE_NAME, "PersistenceCoverage", hasPersistenceTests,
                "Database persistence test coverage");
        TestLogger.logValidation(MODULE_NAME, "TTLManagementCoverage", hasTTLTests,
                "TTL management test coverage");
        TestLogger.logValidation(MODULE_NAME, "ServiceManagementCoverage", hasManagementTests,
                "Service management test coverage");
        TestLogger.logValidation(MODULE_NAME, "PerformanceStressCoverage", hasPerformanceTests,
                "Performance stress test coverage");

        // Add metrics to context
        context.putContextData("totalTests", totalTests);
        context.putContextData("passedTests", passedTests);
        context.putContextData("failedTests", failedTests);
        context.putContextData("successRate", successRate);

        // Add timing information
        timer.getAllResults().forEach((name, result) -> context.putContextData(name + "_duration", result.getDuration()));
    }

    @Override
    protected TestResultSummary generateSuccessResult(long duration) {
        String message = "All player module integration tests passed successfully";
        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(true)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("totalTests", totalTests)
                .withMetadata("passedTests", passedTests)
                .withMetadata("failedTests", failedTests)
                .withMetadata("successRate", totalTests > 0 ? (double) passedTests / totalTests : 0.0)
                .withMetadata("testType", "integration")
                .withMetadata("requiresRedis", true)
                .withMetadata("requiresMongoDB", true);
    }

    @Override
    protected TestResultSummary generateFailureResult(long duration, Exception exception) {
        String message = "Player module integration tests failed: " + exception.getMessage();
        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(false)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("exception", exception.getClass().getSimpleName())
                .withMetadata("exceptionMessage", exception.getMessage())
                .withMetadata("totalTests", totalTests)
                .withMetadata("passedTests", passedTests)
                .withMetadata("failedTests", failedTests)
                .withMetadata("testType", "integration")
                .withMetadata("requiresRedis", true)
                .withMetadata("requiresMongoDB", true);
    }
}
