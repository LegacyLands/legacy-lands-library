package net.legacy.library.grpcclient.task;

import io.grpc.Status;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

/**
 * Exception thrown when an error occurs during interaction with the Task Scheduler gRPC service.
 *
 * <p>When available, {@link #getStatusCode()} exposes the gRPC status code reported by the server
 * (for example {@link Status.Code#FAILED_PRECONDITION} for failed dependencies or
 * {@link Status.Code#DEADLINE_EXCEEDED} for timeouts), and {@link #getTaskStatus()} exposes the
 * final task status for failures observed through result polling.
 *
 * @author qwq-dev
 * @since 2025-4-4 16:20
 */
@Getter
public class TaskSchedulerException extends Exception {

    private final @Nullable Status.Code statusCode;
    private final @Nullable TaskStatus taskStatus;

    /**
     * Constructs a new TaskSchedulerException with the specified detail message.
     *
     * @param message the detail message
     */
    public TaskSchedulerException(String message) {
        this(message, null, null, null);
    }

    /**
     * Constructs a new TaskSchedulerException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public TaskSchedulerException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    /**
     * Constructs a new TaskSchedulerException with full failure details.
     *
     * @param message    the detail message
     * @param statusCode the gRPC status code reported by the server, {@code null} if not applicable
     * @param taskStatus the final task status, {@code null} if not applicable
     * @param cause      the cause, {@code null} if none
     */
    public TaskSchedulerException(String message, @Nullable Status.Code statusCode,
                                  @Nullable TaskStatus taskStatus, @Nullable Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.taskStatus = taskStatus;
    }

}
