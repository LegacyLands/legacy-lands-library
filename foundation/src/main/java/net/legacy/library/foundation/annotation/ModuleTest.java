package net.legacy.library.foundation.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a module-level test that should be executed during module testing.
 *
 * <p>This annotation is used to identify test classes that should be processed
 * by the module testing framework. It provides configuration options for test
 * execution behavior and expected outcomes.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 22:30
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModuleTest {

    /**
     * The unique name identifier for this test.
     *
     * <p>This name should be unique within the module and will be used
     * for test tracking, logging, and result reporting.
     *
     * @return the test name
     */
    String testName();

    /**
     * The expected result of test execution.
     *
     * <p>This value can be used by test processors to validate that
     * the test produces the expected outcome. Common values include
     * "processed", "success", "completed", etc.
     *
     * @return the expected result string
     */
    String expectedResult() default "processed";

    /**
     * Test execution timeout in milliseconds.
     *
     * <p>If the test takes longer than this timeout to complete,
     * it may be considered failed. A value of 0 indicates no timeout.
     *
     * @return the timeout in milliseconds
     */
    long timeout() default 0;

    /**
     * Priority level for test execution ordering.
     *
     * <p>Tests with higher priority values will be executed first.
     * Tests with the same priority may be executed in any order.
     *
     * @return the priority level
     */
    int priority() default 0;

    /**
     * Human-readable description of what this test validates.
     *
     * <p>This description will be included in test reports and logs
     * to help understand the purpose of the test.
     *
     * @return the test description
     */
    String description() default "";

    /**
     * Tags for categorizing and filtering tests.
     *
     * <p>Tags can be used to group related tests or to selectively
     * execute certain categories of tests. Examples: "integration",
     * "performance", "smoke", "regression".
     *
     * @return the test tags
     */
    String[] tags() default {};

    /**
     * Whether this test should be included in automated test runs.
     *
     * <p>Setting this to false allows the test to be defined but
     * excluded from normal test execution. Useful for tests that
     * are under development or temporarily problematic.
     *
     * @return true if the test should be executed automatically
     */
    boolean enabled() default true;

    /**
     * Whether to validate the order of lifecycle method invocations.
     *
     * <p>When enabled, the test framework will verify that lifecycle
     * methods (before, process, after, finallyAfter) are called in
     * the correct order and log detailed execution information.
     *
     * @return true to enable lifecycle validation
     */
    boolean validateLifecycle() default false;

    /**
     * Whether this test requires isolation from other tests.
     *
     * <p>Isolated tests may be executed in a separate context or
     * with additional setup/cleanup to ensure they don't interfere
     * with other tests or shared state.
     *
     * @return true if the test requires isolation
     */
    boolean isolated() default false;

    /**
     * Custom properties for test configuration.
     *
     * <p>This array allows specification of key-value pairs for
     * custom test configuration. Each string should be in the
     * format "key=value".
     *
     * @return the custom configuration properties
     */
    String[] properties() default {};

}