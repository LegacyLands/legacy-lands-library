package net.legacy.library.annotation.service;

/**
 * Interface for custom annotation processors.
 *
 * <p>Implementations of this interface should handle the processing of classes annotated with a specific annotation.
 *
 * @author NaerQAQ
 * @version 1.0
 * @since 2024/1/7
 */
public interface CustomAnnotationProcessor {

    /**
     * Invoked before processing a class.
     *
     * @param clazz the class to be processed
     * @throws Exception the exception
     */
    default void before(Class<?> clazz) throws Exception {
        // No-op by default
    }

    /**
     * Handles the processing of a class annotated with a specific annotation.
     *
     * @param clazz the class to be processed
     * @throws Exception if an error occurs during processing
     */
    void process(Class<?> clazz) throws Exception;

    /**
     * Handles an exception that occurred during the processing of a class.
     *
     * @param clazz     the class that encountered an exception during processing
     * @param exception the exception that occurred
     */
    void exception(Class<?> clazz, Exception exception);

    /**
     * Invoked after processing a class.
     *
     * @param clazz the class that was processed
     * @throws Exception the exception
     */
    default void after(Class<?> clazz) throws Exception {
        // No-op by default
    }

    /**
     * Invoked in the "finally" block after processing a class, regardless of success or failure.
     *
     * <p>This method can be used for cleanup, logging, or other final operations that must run after processing
     * has completed, whether or not an exception was thrown.
     *
     * @param clazz the class that was processed
     */
    default void finallyAfter(Class<?> clazz) {
        // No-op by default
    }

}