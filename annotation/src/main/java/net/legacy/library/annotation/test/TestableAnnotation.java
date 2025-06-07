package net.legacy.library.annotation.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test annotation for validating annotation processing functionality.
 *
 * <p>This annotation is used during debug mode to test the annotation processing framework.
 * Classes annotated with this annotation will be processed by the corresponding test processor
 * to verify that the annotation scanning and processing mechanisms work correctly.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 21:30
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestableAnnotation {
    /**
     * The test name for identification purposes.
     *
     * @return the test name
     */
    String testName() default "default-test";

    /**
     * The expected result of the test processing.
     *
     * @return the expected result
     */
    String expectedResult() default "processed";

    /**
     * Whether this test should validate processing order.
     *
     * @return true if processing order should be validated
     */
    boolean validateOrder() default false;
}