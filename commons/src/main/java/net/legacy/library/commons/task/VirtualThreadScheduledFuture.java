package net.legacy.library.commons.task;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * A wrapper for {@link ScheduledFuture} and {@link ScheduledExecutorService}
 * to simplify resource management, particularly for tasks scheduled with virtual threads.
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
     */
    public void close() {
        scheduledFuture.cancel(true);
        scheduledExecutorService.shutdown();
    }

    /**
     * Cancels the task and shuts down the executor service with specific options.
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
}
