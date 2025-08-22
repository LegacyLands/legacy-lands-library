package net.legacy.library.foundation.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures test execution behavior for a module test runner.
 *
 * <p>This annotation can be applied to test runner classes to configure
 * various aspects of test execution such as parallel execution, timeout
 * handling, and result reporting behavior.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 22:30
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TestConfiguration {

    /**
     * Whether to enable parallel execution of tests.
     *
     * <p>When enabled, tests may be executed concurrently to improve
     * performance. This requires tests to be thread-safe and not
     * depend on shared mutable state.
     *
     * @return true to enable parallel execution
     */
    boolean parallelExecution() default false;

    /**
     * Maximum number of concurrent test executions.
     *
     * <p>This setting is only used when parallel execution is enabled.
     * A value of 0 indicates no limit (uses system defaults).
     *
     * @return the maximum concurrency level
     */
    int maxConcurrency() default 0;

    /**
     * Global timeout for all tests in milliseconds.
     *
     * <p>This timeout applies to the entire test execution, not individual
     * tests. Individual tests may have their own timeouts specified in
     * the {@link ModuleTest} annotation.
     *
     * @return the global timeout in milliseconds
     */
    long globalTimeout() default 0;

    /**
     * Whether to continue test execution when individual tests fail.
     *
     * <p>When true, test execution will continue even if some tests fail,
     * allowing for comprehensive result collection. When false, execution
     * may stop at the first failure.
     *
     * @return true to continue execution on failure
     */
    boolean continueOnFailure() default true;

    /**
     * Whether to collect detailed performance metrics.
     *
     * <p>When enabled, the test framework will collect detailed timing
     * and performance metrics for analysis and reporting.
     *
     * @return true to enable performance metrics collection
     */
    boolean collectMetrics() default true;

    /**
     * Whether to enable verbose logging during test execution.
     *
     * <p>Verbose logging provides detailed information about test
     * execution progress, useful for debugging and monitoring.
     *
     * @return true to enable verbose logging
     */
    boolean verboseLogging() default false;

    /**
     * Whether to enable debug mode for test execution.
     *
     * <p>Debug mode provides additional diagnostic information and
     * may affect test execution behavior for troubleshooting purposes.
     *
     * @return true to enable debug mode
     */
    boolean debugMode() default false;

    /**
     * Custom test packages to scan for test classes.
     *
     * <p>If specified, only these packages will be scanned for test
     * classes. If empty, the default package scanning behavior applies.
     *
     * @return the packages to scan
     */
    String[] testPackages() default {};

    /**
     * Test execution environment identifier.
     *
     * <p>This can be used to configure environment-specific behavior
     * such as "development", "testing", "production", etc.
     *
     * @return the execution environment
     */
    String environment() default "testing";

    /**
     * Custom configuration properties for the test runner.
     *
     * <p>These properties can be used to pass custom configuration
     * to test runners. Each string should be in "key=value" format.
     *
     * @return the custom configuration properties
     */
    String[] properties() default {};

    /**
     * Whether to fail fast on the first test failure.
     *
     * <p>When enabled, test execution will stop immediately upon
     * the first test failure. This is the opposite of continueOnFailure.
     *
     * @return true to fail fast
     */
    boolean failFast() default false;

    /**
     * Test result reporting format.
     *
     * <p>Specifies the preferred format for test result reporting.
     * Common values include "detailed", "summary", "minimal".
     *
     * @return the reporting format
     */
    String reportFormat() default "detailed";

    /**
     * Whether to enable test result caching.
     *
     * <p>When enabled, test results may be cached to avoid re-running
     * tests that haven't changed. Useful for large test suites.
     *
     * @return true to enable result caching
     */
    boolean enableCaching() default false;

}