package net.legacy.library.annotation.test;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.annotation.service.AnnotationProcessor;
import net.legacy.library.annotation.service.CustomAnnotationProcessor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test annotation processor for validating annotation processing functionality.
 *
 * <p>This processor handles classes annotated with {@link TestableAnnotation} during debug mode.
 * It tracks processing lifecycle events and validates that the annotation processing framework
 * works correctly, including proper method invocation order and error handling.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 21:30
 */
@AnnotationProcessor(TestableAnnotation.class)
@InjectableComponent
public class TestableAnnotationProcessor implements CustomAnnotationProcessor {
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger beforeCount = new AtomicInteger(0);
    private final AtomicInteger afterCount = new AtomicInteger(0);
    private final AtomicInteger exceptionCount = new AtomicInteger(0);
    private final AtomicInteger finallyAfterCount = new AtomicInteger(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public void before(Class<?> clazz) throws Exception {
        beforeCount.incrementAndGet();
        TestableAnnotation annotation = clazz.getAnnotation(TestableAnnotation.class);
        
        if (annotation != null && annotation.validateOrder()) {
            Log.info("[TestableAnnotationProcessor] Before processing class: " + clazz.getSimpleName() + 
                    " with test name: " + annotation.testName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void process(Class<?> clazz) throws Exception {
        processedCount.incrementAndGet();
        TestableAnnotation annotation = clazz.getAnnotation(TestableAnnotation.class);
        
        if (annotation == null) {
            throw new IllegalStateException("TestableAnnotation not found on class: " + clazz.getName());
        }

        Log.info("[TestableAnnotationProcessor] Processing class: " + clazz.getSimpleName() + 
                " with test name: " + annotation.testName() + " (expected: " + annotation.expectedResult() + ")");

        // Simulate some processing work
        String result = "processed";
        
        if (!annotation.expectedResult().equals(result)) {
            throw new AssertionError("Expected result '" + annotation.expectedResult() + 
                    "' but got '" + result + "' for class: " + clazz.getName());
        }

        // Register the processed class for later validation
        TestResultRegistry.registerProcessedClass(clazz, annotation.testName(), result);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exception(Class<?> clazz, Exception exception) {
        exceptionCount.incrementAndGet();
        TestableAnnotation annotation = clazz.getAnnotation(TestableAnnotation.class);
        String testName = annotation != null ? annotation.testName() : "unknown";
        
        Log.error("[TestableAnnotationProcessor] Exception occurred while processing class: " + 
                clazz.getSimpleName() + " with test name: " + testName, exception);
        
        // Register the failed class for later validation
        TestResultRegistry.registerFailedClass(clazz, testName, exception.getMessage());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void after(Class<?> clazz) throws Exception {
        afterCount.incrementAndGet();
        TestableAnnotation annotation = clazz.getAnnotation(TestableAnnotation.class);
        
        if (annotation != null && annotation.validateOrder()) {
            Log.info("[TestableAnnotationProcessor] After processing class: " + clazz.getSimpleName() + 
                    " with test name: " + annotation.testName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finallyAfter(Class<?> clazz) {
        finallyAfterCount.incrementAndGet();
        TestableAnnotation annotation = clazz.getAnnotation(TestableAnnotation.class);
        
        if (annotation != null && annotation.validateOrder()) {
            Log.info("[TestableAnnotationProcessor] Finally after processing class: " + clazz.getSimpleName() + 
                    " with test name: " + annotation.testName());
        }
    }

    /**
     * Gets the number of classes processed.
     *
     * @return the processed count
     */
    public int getProcessedCount() {
        return processedCount.get();
    }

    /**
     * Gets the number of before method invocations.
     *
     * @return the before count
     */
    public int getBeforeCount() {
        return beforeCount.get();
    }

    /**
     * Gets the number of after method invocations.
     *
     * @return the after count
     */
    public int getAfterCount() {
        return afterCount.get();
    }

    /**
     * Gets the number of exceptions that occurred.
     *
     * @return the exception count
     */
    public int getExceptionCount() {
        return exceptionCount.get();
    }

    /**
     * Gets the number of finally after method invocations.
     *
     * @return the finally after count
     */
    public int getFinallyAfterCount() {
        return finallyAfterCount.get();
    }

    /**
     * Resets all counters to zero.
     */
    public void resetCounters() {
        processedCount.set(0);
        beforeCount.set(0);
        afterCount.set(0);
        exceptionCount.set(0);
        finallyAfterCount.set(0);
    }
}