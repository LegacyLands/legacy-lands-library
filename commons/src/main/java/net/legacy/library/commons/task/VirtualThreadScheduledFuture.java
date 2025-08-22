package net.legacy.library.commons.task;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * A wrapper for {@link ScheduledFuture} and {@link ScheduledExecutorService}
 * to simplify resource management, particularly for tasks scheduled with virtual threads.
 *
 * <p>This class encapsulates both the scheduled future and its associated executor service,
 * providing convenient methods to cancel tasks and manage executor lifecycle. When working
 * with shared schedulers (such as {@link TaskInterface#VIRTUAL_SCHEDULER}), care should be
 * taken when calling shutdown methods to avoid affecting other scheduled tasks.
 *
 * @author qwq-dev
 * @since 2025-05-10 21:27
 */
@Getter
@AllArgsConstructor
public class VirtualThreadScheduledFuture {

    private final ScheduledFuture<?> scheduledFuture;
    private final ScheduledExecutorService scheduledExecutorService;

    /**
     * Cancels the task and shuts down the executor service.
     * Equivalent to calling {@code close(true, false)}.
     *
     * <p><strong>Warning:</strong> If this instance wraps a shared scheduler
     * (such as {@link TaskInterface#VIRTUAL_SCHEDULER}), calling this method will
     * shut down the shared scheduler and affect other scheduled tasks.
     */
    public void close() {
        scheduledFuture.cancel(true);
        scheduledExecutorService.shutdown();
    }

    /**
     * Cancels the task and shuts down the executor service with specific options.
     *
     * <p><strong>Warning:</strong> If this instance wraps a shared scheduler
     * (such as {@link TaskInterface#VIRTUAL_SCHEDULER}), calling this method will
     * shut down the shared scheduler and affect other scheduled tasks.
     *
     * @param mayInterruptIfRunning {@code true} to interrupt the task thread if running
     * @param shutdownNow           {@code true} to attempt to stop all actively executing tasks immediately,
     *                              {@code false} to allow existing tasks to complete
     */
    public void close(boolean mayInterruptIfRunning, boolean shutdownNow) {
        scheduledFuture.cancel(mayInterruptIfRunning);

        if (shutdownNow) {
            scheduledExecutorService.shutdownNow();
        } else {
            scheduledExecutorService.shutdown();
        }
    }

    /**
     * Cancels only the scheduled task without shutting down the executor service.
     *
     * <p>This method is safe to use with shared schedulers as it does not affect
     * the underlying scheduler or other scheduled tasks.
     *
     * @param mayInterruptIfRunning {@code true} to interrupt the task thread if running
     * @return {@code false} if the task could not be cancelled, typically because it has already
     *         completed normally; {@code true} otherwise
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        return scheduledFuture.cancel(mayInterruptIfRunning);
    }

    /**
     * Cancels only the scheduled task without shutting down the executor service.
     * Equivalent to calling {@code cancel(true)}.
     *
     * <p>This method is safe to use with shared schedulers as it does not affect
     * the underlying scheduler or other scheduled tasks.
     *
     * @return {@code false} if the task could not be cancelled, typically because it has already
     *         completed normally; {@code true} otherwise
     */
    public boolean cancel() {
        return cancel(true);
    }

}
