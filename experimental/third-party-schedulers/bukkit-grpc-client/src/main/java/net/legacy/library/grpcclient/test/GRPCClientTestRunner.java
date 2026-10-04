package net.legacy.library.grpcclient.test;

import net.legacy.library.foundation.annotation.TestConfiguration;
import net.legacy.library.foundation.test.AbstractModuleTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * gRPC client module test runner for the clustered task scheduler.
 *
 * <p>This runner requires two task scheduler nodes configured as peers of each other,
 * see {@link ClusterSchedulingTest} for the expected setup.
 *
 * @author qwq-dev
 * @since 2026-10-04 01:20
 */
@TestConfiguration(
        continueOnFailure = true,
        verboseLogging = true,
        debugMode = true,
        maxConcurrency = 1,
        testPackages = {"net.legacy.library.grpcclient.test"},
        globalTimeout = 120000,
        enableCaching = false,
        failFast = false
)
public class GRPCClientTestRunner extends AbstractModuleTestRunner {

    private static final String MODULE_NAME = "bukkit-grpc-client";

    private final TestTimer timer = new TestTimer();
    private int totalTests = 0;
    private int passedTests = 0;
    private int failedTests = 0;

    /**
     * Creates the gRPC client module test runner.
     */
    public GRPCClientTestRunner() {
        super(MODULE_NAME);
    }

    /**
     * Creates the gRPC client module test runner.
     *
     * @return the created test runner
     */
    public static GRPCClientTestRunner create() {
        return new GRPCClientTestRunner();
    }

    @Override
    protected void beforeTests() throws Exception {
        TestLogger.logTestStart(MODULE_NAME, "cluster-scheduling-tests");
        timer.startTimer("total-execution");
        ClusterSchedulingTest.initialize();
    }

    @Override
    protected void executeTests() throws Exception {
        executeTestClass(ClusterSchedulingTest.class);

        validateResult(totalTests >= 10, "Should have at least 10 test methods");
        validateResult(failedTests == 0, failedTests + " of " + totalTests + " cluster scheduling tests failed");
    }

    @Override
    protected void afterTests() throws Exception {
        ClusterSchedulingTest.shutdown();
        timer.stopTimer("total-execution");

        long duration = timer.getTimerResult("total-execution").getDuration();
        TestLogger.logTestComplete(MODULE_NAME, "cluster-scheduling-tests", duration);
        TestLogger.logStatistics(MODULE_NAME, totalTests, passedTests, failedTests, duration);
    }

    @Override
    protected TestResultSummary generateFailureResult(long duration, Exception exception) {
        TestLogger.logFailure(MODULE_NAME, "Cluster scheduling tests aborted: %s", exception, exception.getMessage());
        return super.generateFailureResult(duration, exception);
    }

    private void executeTestClass(Class<?> testClass) {
        List<Method> testMethods = Arrays.stream(testClass.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("test"))
                .filter(method -> method.getReturnType() == boolean.class)
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .toList();

        testMethods.forEach(this::executeTestMethod);
    }

    private void executeTestMethod(Method method) {
        totalTests++;
        timer.startTimer(method.getName());
        context.incrementProcessed();

        try {
            boolean passed = (Boolean) method.invoke(null);
            if (passed) {
                passedTests++;
                context.incrementSuccess();
            } else {
                failedTests++;
                context.incrementFailure();
            }
            TestLogger.logValidation(MODULE_NAME, method.getName(), passed,
                    passed ? "Test completed successfully" : "Test failed - returned false");
        } catch (Exception exception) {
            failedTests++;
            context.incrementFailure();
            TestLogger.logValidation(MODULE_NAME, method.getName(), false,
                    "Test failed with exception: %s", exception.getMessage());
        } finally {
            timer.stopTimer(method.getName());
        }
    }

}
