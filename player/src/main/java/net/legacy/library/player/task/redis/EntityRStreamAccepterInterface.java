package net.legacy.library.player.task.redis;

import io.fairyproject.mc.scheduler.MCScheduler;
import io.fairyproject.mc.scheduler.MCSchedulers;
import net.legacy.library.player.service.LegacyEntityDataService;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interface defining the contract for handling tasks received via Redisson's RStream feature.
 *
 * <p>Implementations of this interface are responsible for processing specific types of tasks
 * and managing their lifecycle within the Redis stream.
 *
 * <p>Each accepter handles tasks identified by a unique action name and can optionally
 * enforce record-limiting to prevent duplicate processing across connections or servers.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
public interface EntityRStreamAccepterInterface {
    /**
     * Gets the action name associated with this task.
     *
     * <p>This serves as a unique identifier or categorization for the specific type of task
     * being processed. For example, it could represent operations like "entity-relationship-update".
     *
     * <p>If no specific action is required, this method can return {@code null}.
     *
     * @return a {@link String} representing the action name of the task, or {@code null} if not applicable
     */
    String getActionName();

    /**
     * Determines whether to limit task processing to prevent duplicates within a single server or connection.
     *
     * <p>If this method returns {@code true}, the task will be processed only once per
     * connection on each server. After the {@link #accept(RStream, StreamMessageId, LegacyEntityDataService, String)}
     * method is executed, the task will not be executed again by the same instance unless explicitly deleted.
     *
     * <p>However, if another server or connection processes the task, it can still
     * be handled there. A task remains in the RStream until it is correctly processed
     * and deleted, or until it expires.
     *
     * <p>If this method returns {@code false}, the task can be processed repeatedly,
     * regardless of whether the {@link #accept(RStream, StreamMessageId, LegacyEntityDataService, String)} method runs on the
     * same connection or instance.
     *
     * @return {@code true} if task records are limited to a single handling per connection,
     * {@code false} otherwise
     */
    boolean isRecordLimit();

    /**
     * Handles the data contained in the task.
     *
     * <p>This method is the main processing logic for handling tasks received via RStream.
     * It is expected to include the following:
     * <ul>
     *   <li>Determine if the task is valid and can be processed.</li>
     *   <li>If the task is successfully processed, it can be explicitly deleted using
     *       methods provided by {@link RStream} (e.g., {@link RStream#remove(StreamMessageId...)}).</li>
     *   <li>If the processing fails, the task will remain in the RStream and be available
     *       for handling by other connections or servers.</li>
     * </ul>
     *
     * <p><b>Task Lifecycle:</b>
     * <ul>
     *   <li>If successfully processed, the task should be deleted explicitly
     *       by the implementation.</li>
     *   <li>If processing fails, it will remain in RStream until:
     *       <ul>
     *         <li>It is processed and deleted successfully by another connection.</li>
     *         <li>It expires based on the configured RStream retention policy.</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <p><b>Note:</b> This method is not exclusive, meaning multiple connections or servers
     * may attempt to process the same task concurrently unless additional controls are in place.
     *
     * @param stream  the {@link RStream} object representing the Redis stream containing the task
     * @param id      the {@link StreamMessageId} object representing the unique ID of the task
     * @param service the {@link LegacyEntityDataService} object representing the service for handling entity data
     * @param data    the data contained in the task
     */
    void accept(RStream<Object, Object> stream,
                StreamMessageId id,
                LegacyEntityDataService service,
                String data);

    /**
     * Acknowledges the task after it has been successfully processed.
     *
     * <p>This method removes the task from the Redis stream, indicating that it has been handled.
     *
     * @param stream the {@link RStream} object representing the Redis stream containing the task
     * @param id     the {@link StreamMessageId} object representing the unique ID of the task
     */
    default void ack(RStream<Object, Object> stream, StreamMessageId id) {
        stream.remove(id);
    }

    /**
     * Determines whether to use virtual threads.
     *
     * <p>Defaults to {@code true}.If set to {@code false},
     * the task will be directly scheduled using the Bukkit thread.
     *
     * @return use virtual threads, defaults to {@code true}
     */
    default boolean useVirtualThread() {
        return true;
    }

    /**
     * Provides the {@link MCScheduler} instance used for scheduling tasks.
     *
     * <p>Only valid when the useVirtualThread method returns {@code false}.
     *
     * <p>By default, this returns {@code MCSchedulers.getAsyncScheduler()}, but implementations can
     * override it to return a different scheduler (e.g., {@link MCSchedulers#getGlobalScheduler()}, or a custom one).
     *
     * @return the {@link MCScheduler} scheduler instance
     */
    default MCScheduler getMCScheduler() {
        return MCSchedulers.getAsyncScheduler();
    }

    /**
     * Provides an {@link ExecutorService} that uses a virtual thread per task execution model.
     *
     * <p>Only valid when the useVirtualThread method returns {@code true}.
     *
     * <p>This method returns a new {@link ExecutorService} instance where each submitted task is
     * executed in its own virtual thread. Virtual threads are lightweight and allow for high concurrency,
     * making them suitable for tasks that are I/O-bound or involve waiting.
     *
     * <p>The returned executor is intended for scenarios where task isolation and concurrency are
     * important, without the overhead associated with traditional threads.
     *
     * @return a virtual thread-per-task {@link ExecutorService} instance
     */
    default ExecutorService getVirtualThreadPerTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}