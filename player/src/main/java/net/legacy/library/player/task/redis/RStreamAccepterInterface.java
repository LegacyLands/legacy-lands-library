package net.legacy.library.player.task.redis;

import net.legacy.library.player.service.LegacyPlayerDataService;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

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
 * @since 2025-01-04 20:30
 */
public interface RStreamAccepterInterface {
    /**
     * Gets the action name associated with this task.
     *
     * <p>This serves as a unique identifier or categorization for the specific type of task
     * being processed. For example, it could represent operations like "player-data-sync-name".
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
     * connection on each server. After the {@link #accept(RStream, StreamMessageId, LegacyPlayerDataService, String)} method is executed,
     * the task will not be executed again by the same instance unless explicitly deleted.
     *
     * <p>However, if another server or connection processes the task, it can still
     * be handled there. A task remains in the RStream until it is correctly processed
     * and deleted, or until it expires.
     *
     * <p>If this method returns {@code false}, the task can be processed repeatedly,
     * regardless of whether the {@link #accept(RStream, StreamMessageId, LegacyPlayerDataService, String)} method runs on the
     * same connection or instance.
     *
     * @return {@code true} if task records are limited to a single handling per connection,
     * {@code false} otherwise
     */
    boolean isRecodeLimit();

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
     * @param rStream                 the {@link RStream} object representing the Redis stream containing the task
     * @param streamMessageId         the {@link StreamMessageId} object representing the unique ID of the task
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} object representing the service for handling player data
     * @param data                    the data contained in the {@link RStreamTask} object
     */
    void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService, String data);

    /**
     * Acknowledges the task after it has been successfully processed.
     *
     * <p>This method removes the task from the Redis stream, indicating that it has been handled.
     *
     * @param rStream         the {@link RStream} object representing the Redis stream containing the task
     * @param streamMessageId the {@link StreamMessageId} object representing the unique ID of the task
     */
    default void ack(RStream<Object, Object> rStream, StreamMessageId streamMessageId) {
        rStream.remove(streamMessageId);
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
}