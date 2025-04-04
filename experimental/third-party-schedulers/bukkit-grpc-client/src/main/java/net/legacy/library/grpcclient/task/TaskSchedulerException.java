package net.legacy.library.grpcclient.task;

/**
 * Exception thrown when an error occurs during interaction with the Task Scheduler gRPC service.
 *
 * @author qwq-dev
 * @since 2025-4-4 16:20
 */
public class TaskSchedulerException extends Exception {
    /**
     * Constructs a new TaskSchedulerException with the specified detail message.
     *
     * @param message the detail message
     */
    public TaskSchedulerException(String message) {
        super(message);
    }

    /**
     * Constructs a new TaskSchedulerException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public TaskSchedulerException(String message, Throwable cause) {
        super(message, cause);
    }
} 