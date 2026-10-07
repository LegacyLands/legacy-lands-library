package net.legacy.library.player.util;

import io.fairyproject.log.Log;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamTrimArgs;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a Redis stream bounded for accept tasks that read it from a cursor.
 *
 * <p>An accept task that reads only new messages never meets a message again once it has read it, so it cannot
 * remove the message when it expires the way a task re-reading the whole stream did. Each round therefore removes the
 * expired messages at the head of the stream, and trims everything older than the retention, expired or not.
 *
 * @author qwq-dev
 * @since 2026-10-07 02:20
 */
public final class StreamRetentionUtil {

    /**
     * How many messages one sweep of expired messages looks at from the head of the stream at most.
     */
    private static final int SWEEP_BATCH_SIZE = 100;

    /**
     * How many sweep batches one round runs at most, so a long backlog of expired messages is cleared over a few
     * rounds instead of holding up one.
     */
    private static final int SWEEP_BATCHES_PER_ROUND = 200;

    /**
     * Whether a failed trim was already reported: on a Redis without {@code XTRIM MINID} it fails every round.
     */
    private static final AtomicBoolean TRIM_FAILURE_REPORTED = new AtomicBoolean();

    private StreamRetentionUtil() {
    }

    /**
     * Removes what is older than the retention, and the expired messages at the head of the stream.
     *
     * <p>Expired messages are removed from the oldest on, a batch at a time, up to the first message that has not
     * expired; one with a longer expiry ahead of shorter ones holds those back until it expires or the retention
     * trims them. {@code XTRIM MINID} needs Redis 6.2: on an older server the trim fails with one warning, and only
     * the sweep runs.
     *
     * @param rStream          the stream to bound
     * @param retention        how long a message is kept at most, expired or not
     * @param expiryField      the message entry holding its expiry time in milliseconds since epoch
     * @param missingIsExpired whether a message without the entry counts as expired, rather than as never expiring
     */
    public static void bound(RStream<Object, Object> rStream, Duration retention, String expiryField, boolean missingIsExpired) {
        long now = System.currentTimeMillis();

        try {
            rStream.trim(StreamTrimArgs.minId(new StreamMessageId(now - retention.toMillis(), 0)).noLimit());
        } catch (Exception exception) {
            if (TRIM_FAILURE_REPORTED.compareAndSet(false, true)) {
                Log.warn("Could not trim the Redis stream; it is still read, only not bounded by the retention", exception);
            }
        }

        StreamMessageId from = StreamMessageId.MIN;
        for (int batch = 0; batch < SWEEP_BATCHES_PER_ROUND; batch++) {
            Map<StreamMessageId, Map<Object, Object>> messages = rStream.range(SWEEP_BATCH_SIZE, from, StreamMessageId.MAX);
            if (messages == null || messages.isEmpty()) {
                return;
            }

            StreamMessageId[] expired = messages.entrySet().stream()
                    .takeWhile(entry -> isExpired(entry.getValue(), expiryField, missingIsExpired, now))
                    .map(Map.Entry::getKey)
                    .toArray(StreamMessageId[]::new);
            if (expired.length > 0) {
                rStream.remove(expired);
            }

            // Up to the first message still valid; past it the stream is newer
            if (expired.length < messages.size()) {
                return;
            }

            StreamMessageId last = messages.keySet().stream().reduce((first, second) -> second).orElseThrow();
            from = new StreamMessageId(last.getId0(), last.getId1() + 1);
        }
    }

    private static boolean isExpired(Map<Object, Object> message, String expiryField, boolean missingIsExpired, long now) {
        Object expiry = message.get(expiryField);
        if (expiry == null) {
            return missingIsExpired;
        }

        try {
            long expiresAt = Long.parseLong(expiry.toString());
            return expiresAt == 0 ? missingIsExpired : now > expiresAt;
        } catch (NumberFormatException exception) {
            return missingIsExpired;
        }
    }

    /**
     * Gets the id of the newest message a stream has ever held, for a reader that starts from now on.
     *
     * <p>Taken from Redis rather than the local clock, which may differ from the server's. If the stream does not exist
     * yet there is nothing to skip; if Redis cannot be asked, the local clock stands in.
     *
     * @param rStream the stream
     * @return the newest message id, or {@link StreamMessageId#ALL} if the stream does not exist
     */
    public static StreamMessageId lastMessageId(RStream<Object, Object> rStream) {
        try {
            return rStream.isExists() ? rStream.getInfo().getLastGeneratedId() : StreamMessageId.ALL;
        } catch (Exception exception) {
            Log.warn("Could not read the end of the Redis stream; reading from the local time on", exception);
            return new StreamMessageId(System.currentTimeMillis(), 0);
        }
    }

}
