package net.legacy.library.player.task.redis.resilience;

import lombok.Builder;
import lombok.Getter;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

/**
 * Context information for failure handling in stream accepters.
 *
 * <p>This immutable context object contains all necessary information about a failed operation,
 * including the exception that caused the failure, stream details, task data, and retry metadata.
 * It is used by {@link FailureHandler} implementations to make informed decisions about retry
 * strategies and compensation actions.
 *
 * <p>The context is built using the builder pattern and provides comprehensive information
 * to enable sophisticated failure handling logic based on:
 * <ul>
 *   <li>Exception type and details</li>
 *   <li>Current retry attempt and limits</li>
 *   <li>Task metadata (action name, data)</li>
 *   <li>Stream and message identifiers</li>
 *   <li>Failure timing information</li>
 * </ul>
 *
 * @author qwq-dev
 * @see FailureHandler
 * @see RetryPolicy
 * @see CompensationAction
 * @since 2025-06-06 16:30
 */
@Getter
@Builder
public class FailureContext {

    /**
     * The exception that caused the failure.
     *
     * <p>This includes the root cause and any wrapped exceptions that can be used
     * to determine the appropriate retry strategy or compensation action.
     */
    private final Exception exception;

    /**
     * The Redis stream where the task originated.
     *
     * <p>Provides access to stream operations for compensation actions,
     * such as removing failed messages or adding dead letter queue entries.
     */
    private final RStream<Object, Object> stream;

    /**
     * The unique identifier of the failed message.
     *
     * <p>Can be used to track retry attempts, remove messages from the stream,
     * or correlate failures across multiple systems.
     */
    private final StreamMessageId messageId;

    /**
     * The task data that failed to process.
     *
     * <p>Contains the raw data payload that was being processed when the failure occurred.
     * This can be analyzed to determine if the failure was due to malformed data.
     */
    private final String taskData;

    /**
     * The action name of the failed task.
     *
     * <p>Identifies the type of operation that failed, allowing for action-specific
     * retry policies and compensation strategies.
     */
    private final String actionName;

    /**
     * Current attempt number (1-based).
     *
     * <p>Indicates how many times this operation has been attempted, including
     * the initial attempt. Used to determine if retry limits have been exceeded.
     */
    private final int attemptNumber;

    /**
     * Maximum number of retry attempts allowed.
     *
     * <p>The configured limit for retry attempts. When {@link #attemptNumber}
     * exceeds this value, retries should cease and compensation should be triggered.
     */
    private final int maxAttempts;

    /**
     * Timestamp when the failure occurred (milliseconds since epoch).
     *
     * <p>Can be used for failure analysis, rate limiting, or implementing
     * time-based retry strategies.
     */
    private final long failureTimestamp;

}