package net.legacy.library.commons.task;

import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the lifecycle of a shared {@link ExecutorService} for virtual threads.
 *
 * @author qwq-dev
 * @since 2025-08-22 21:51
 */
@UtilityClass
public class VirtualThreadExecutors {

    private static final long DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 20;
    private static final AtomicReference<ExecutorService> SHARED_EXECUTOR_REF = new AtomicReference<>();

    /**
     * Initializes the shared {@link ExecutorService} for virtual threads if it hasn't been already.
     */
    public static void initialize() {
        SHARED_EXECUTOR_REF.compareAndSet(null, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("LegacyLands-Virtual-Worker-", 0).factory()
        ));
    }

    /**
     * Returns the shared {@link ExecutorService} for virtual threads.
     *
     * @return the shared virtual thread-per-task {@link ExecutorService} instance
     * @throws IllegalStateException if the executor has not been initialized
     */
    public static ExecutorService getSharedExecutor() {
        ExecutorService executor = SHARED_EXECUTOR_REF.get();

        if (executor != null) {
            return executor;
        }

        throw new IllegalStateException("VirtualThreadExecutors has not been initialized. Call initialize() first.");
    }

    /**
     * Shuts down the shared virtual thread executor gracefully.
     */
    public static void destroy() {
        ExecutorService executor = SHARED_EXECUTOR_REF.getAndSet(null);

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.info("VirtualThreadExecutors did not terminate gracefully within " +
                            DEFAULT_SHUTDOWN_TIMEOUT_SECONDS + " seconds, attempting forceful shutdown.");

                    executor.shutdownNow();
                    if (!executor.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        Log.info("VirtualThreadExecutors did not terminate after forceful shutdown.");
                    }
                }
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Log.warn("VirtualThreadExecutors shutdown was interrupted. Forcing shutdown.");
            }
        }
    }

    /**
     * Creates and returns a new {@link ExecutorService} that uses a virtual thread per task.
     *
     * <p>This executor is typically used for single-use or very short-lived operations,
     * often within a {@code try-with-resources} block where blocking behavior is acceptable.
     * Its virtual threads are daemon threads.
     *
     * @return a new virtual thread-per-task {@link ExecutorService} instance
     */
    public static ExecutorService createEphemeralExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("LegacyLands-Commons-Ephemeral-Virtual-Worker-", 0).factory()
        );
    }

}