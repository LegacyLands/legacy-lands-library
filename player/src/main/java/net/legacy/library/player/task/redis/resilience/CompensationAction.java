package net.legacy.library.player.task.redis.resilience;

import io.fairyproject.log.Log;

/**
 * Represents a compensation action that can be executed when a stream accepter operation fails
 * and cannot be retried successfully.
 *
 * <p>Compensation actions provide a mechanism for cleanup, rollback, or alternative processing
 * when all retry attempts have been exhausted. They are the final step in the failure handling
 * process and should ensure the system remains in a consistent state.
 *
 * <p>Common compensation scenarios include:
 * <ul>
 *   <li><strong>Cleanup:</strong> Remove failed messages from streams</li>
 *   <li><strong>Logging:</strong> Record failure details for analysis</li>
 *   <li><strong>Notification:</strong> Alert administrators or monitoring systems</li>
 *   <li><strong>Dead letter queues:</strong> Move failed messages to alternative processing</li>
 *   <li><strong>Rollback:</strong> Undo partial state changes</li>
 * </ul>
 *
 * <p>Implementations should be idempotent and handle their own exceptions to prevent
 * cascading failures during compensation.
 *
 * @author qwq-dev
 * @see FailureContext
 * @see FailureHandler
 * @see FailureHandlingResult
 * @since 2025-06-06 16:30
 */
@FunctionalInterface
public interface CompensationAction {

    /**
     * A no-op compensation action that does nothing.
     *
     * <p>Use this when no compensation is required or when you want to
     * explicitly indicate that failures should be ignored.
     */
    CompensationAction NONE = context -> {
    };

    /**
     * A compensation action that logs the failure using Fairy's logging system.
     *
     * <p>Logs essential failure information including attempt count, action name,
     * message ID, and error message. Useful for debugging and monitoring purposes.
     */
    CompensationAction LOG_FAILURE = context ->
            Log.error("Failed to process stream message after %s attempts. Action: %s, MessageId: %s, Error: %s",
                    context.getAttemptNumber(), context.getActionName(),
                    context.getMessageId(), context.getException().getMessage());

    /**
     * A compensation action that removes the failed message from the stream.
     *
     * <p>Prevents the failed message from being processed again by removing it
     * entirely from the Redis stream. Use this when the message is determined
     * to be invalid or unprocessable.
     */
    CompensationAction REMOVE_MESSAGE = context -> context.getStream().remove(context.getMessageId());

    /**
     * Creates a composite compensation action that executes multiple actions in sequence.
     *
     * <p>The composite action will attempt to execute all provided actions, even if
     * some of them fail. Failed actions are logged but do not prevent subsequent
     * actions from executing. This ensures maximum cleanup even in the presence
     * of partial failures.
     *
     * @param actions the compensation actions to execute in sequence
     * @return a new {@link CompensationAction} that executes all provided actions
     */
    static CompensationAction composite(CompensationAction... actions) {
        return context -> {
            for (CompensationAction action : actions) {
                try {
                    action.execute(context);
                } catch (Exception exception) {
                    // Log compensation failure but continue with other actions
                    Log.error("Compensation action failed: %s", exception.getMessage(), exception);
                }
            }
        };
    }

    /**
     * Executes the compensation action with the given failure context.
     *
     * <p>This method should handle any cleanup, rollback, or alternative processing
     * required when the original operation cannot be completed. Implementations should:
     * <ul>
     *   <li>Be idempotent (safe to execute multiple times)</li>
     *   <li>Handle their own exceptions gracefully</li>
     *   <li>Not assume the system is in any particular state</li>
     *   <li>Log their actions for debugging purposes</li>
     * </ul>
     *
     * @param context the failure context containing information about the failed operation
     * @throws Exception if the compensation action itself fails
     */
    void execute(FailureContext context) throws Exception;

}