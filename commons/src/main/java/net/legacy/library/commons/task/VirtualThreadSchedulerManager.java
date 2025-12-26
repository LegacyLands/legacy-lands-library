package net.legacy.library.commons.task;

import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the lifecycle of a shared {@link ScheduledExecutorService} for virtual thread tasks.
 *
 * @author qwq-dev
 * @since 2025-08-22 21:52
 */
@UtilityClass
public class VirtualThreadSchedulerManager {

    private static final long DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 20;
    private static final AtomicReference<ScheduledExecutorService> SHARED_SCHEDULER_REF = new AtomicReference<>();

    /**
     * Initializes the shared {@link ScheduledExecutorService} if it hasn't been already.
     */
    public static void initialize() {
        SHARED_SCHEDULER_REF.compareAndSet(null, Executors.newScheduledThreadPool(1,
                Thread.ofPlatform()
                        .name("LegacyLands-TaskInterface-Virtual-Scheduler-", 0)
                        .daemon(true)
                        .factory()
        ));
    }

    /**
     * Returns the shared {@link ScheduledExecutorService} for virtual thread task scheduling.
     *
     * @return the shared scheduled executor service
     * @throws IllegalStateException if the scheduler has not been initialized
     */
    public static ScheduledExecutorService getScheduler() {
        ScheduledExecutorService scheduler = SHARED_SCHEDULER_REF.get();

        if (scheduler != null) {
            return scheduler;
        }

        throw new IllegalStateException("VirtualThreadSchedulerManager has not been initialized. Call initialize() first.");
    }

    /**
     * Shuts down the shared scheduled executor service gracefully.
     *
     * <p><b>Important:</b> This method uses blocking operations ({@code awaitTermination})
     * that would cause virtual thread pinning. It should be called from a platform thread
     * (e.g., main thread or shutdown hook), not from a virtual thread.
     */
    public static void destroy() {
        ScheduledExecutorService scheduler = SHARED_SCHEDULER_REF.getAndSet(null);

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.info("VirtualThreadSchedulerManager did not terminate gracefully within " +
                            DEFAULT_SHUTDOWN_TIMEOUT_SECONDS + " seconds, attempting forceful shutdown.");

                    scheduler.shutdownNow();
                    if (!scheduler.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        Log.info("VirtualThreadSchedulerManager did not terminate after forceful shutdown.");
                    }
                }
            } catch (InterruptedException exception) {
                scheduler.shutdownNow();
                Log.warn("VirtualThreadSchedulerManager shutdown was interrupted. Forcing shutdown.");
            }
        }
    }

}