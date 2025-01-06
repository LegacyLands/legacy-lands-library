package net.legacy.library.player.task.redis;

import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.Map;

/**
 * This interface defines the contract for handling tasks with Redisson's RStream feature.
 * It allows for processing tasks distributed across instances of the legacy player data service
 * and provides options for fine-grained control over task lifecycle and record-limiting behavior.
 *
 * @author qwq-dev
 * @since 2025-01-04 20:30
 */
public interface RStreamAccepterInterface {

    /**
     * Get the action name associated with this task.
     *
     * <p>This serves as a unique identifier or categorization for the specific type of task
     * being processed. For example, it could represent operations like "player-data-sync-name".
     *
     * @return A {@link String} representing the action name of the task.
     */
    String getActionName();

    /**
     * Get the target legacy player data service name that this task applies to.
     *
     * <p>This corresponds to the specific player data service instance this task
     * targets. It helps in routing or contextualizing tasks in multiservice or distributed environments.
     *
     * @return A {@link String} representing the target service name, If {@code null} is returned, it means there is no limit
     */
    String getTargetLegacyPlayerDataServiceName();

    /**
     * Determine whether to limit task processing to prevent duplicates within a single server or connection.
     *
     * <p>If this method returns {@code true}, the task will be processed only once per
     * connection on each server. After the {@link #accept(RStream, Map.Entry)} method is executed,
     * the task will not be executed again by the same instance unless explicitly deleted.
     *
     * <p>However, if another server or connection processes the task, it can still
     * be handled there. A task remains in the rStream until it is correctly processed
     * and deleted, or until it expires.
     *
     * <p>If this method returns {@code false}, the task can be processed repeatedly,
     * regardless of whether the {@link #accept(RStream, Map.Entry)} method runs on the
     * same connection or instance.
     *
     * @return {@code true} if task records are limited to a single handling per connection,
     * {@code false} otherwise.
     */
    boolean isRecodeLimit();

    /**
     * Handle the data contained in the task.
     *
     * <p>This method is the main processing logic for handling tasks received via rStream.
     * It is expected to include the following:
     * <ul>
     *   <li>Determine if the task is valid and can be processed.</li>
     *   <li>If the task is successfully processed, it can be explicitly deleted using
     *       methods provided by {@link RStream} (e.g., {@code rStream.acknowledge()} or
     *       {@code rStream.remove()}).</li>
     *   <li>If the processing fails, the task will remain in the rStream and be available
     *       for handling by other connections or servers.</li>
     * </ul>
     *
     * <p><b>Task Lifecycle:</b>
     * <ul>
     *   <li>If successfully processed, the task should be deleted explicitly
     *       by the implementation.</li>
     *   <li>If processing fails, it will remain in rStream until:
     *       <ul>
     *         <li>It is processed and deleted successfully by another connection.</li>
     *         <li>It expires based on the configured rStream retention policy.</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <p><b>Note:</b> This method is not exclusive, meaning multiple connections or servers
     * may attempt to process the same task concurrently unless additional controls are in place.
     *
     * @param rStream                 The {@link RStream} object representing the Redis stream containing the task.
     * @param streamMessageIdMapEntry A {@link Map.Entry} containing the task's {@link StreamMessageId} as the key
     *                                and a map of key-value pairs representing the task's data as the value.
     */
    void accept(RStream<Object, Object> rStream, Map.Entry<StreamMessageId, Map<Object, Object>> streamMessageIdMapEntry);
}