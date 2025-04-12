package net.legacy.library.player.util;

import org.redisson.api.RBucket;

import java.time.Duration;

/**
 * Utility class for reliable TTL (Time-To-Live) operations in Redis.
 *
 * @author qwq-dev
 * @since 2025-04-11 19:45
 */
public class TTLUtil {
    /**
     * Sets TTL for a Redis bucket using the most reliable methods available.
     *
     * <p>First tries the combined set approach, then falls back to expire method if needed.
     *
     * @param bucket the Redis bucket to set TTL on
     * @param ttl    duration representing the TTL value
     * @return true if TTL was successfully set, false otherwise
     */
    @SuppressWarnings("unchecked")
    public static boolean setReliableTTL(RBucket<?> bucket, Duration ttl) {
        if (bucket == null || ttl == null) {
            return false;
        }

        Object value = bucket.get();
        if (value != null) {
            ((RBucket<Object>) bucket).set(value, Duration.ofMillis(ttl.toMillis()));
            if (bucket.remainTimeToLive() > 0) {
                return true;
            }
        }

        return bucket.expire(ttl);
    }

    /**
     * Sets TTL for a Redis bucket if it doesn't already have one.
     *
     * <p>Checks the current TTL and only sets if needed.
     *
     * @param bucket the Redis bucket to set TTL on
     * @param ttl    duration representing the TTL value
     * @return true if TTL was set or already had a valid TTL, false if setting failed
     */
    public static boolean setTTLIfMissing(RBucket<?> bucket, Duration ttl) {
        if (bucket == null || ttl == null) {
            return false;
        }

        if (bucket.remainTimeToLive() < 0) {
            return setReliableTTL(bucket, ttl);
        }

        return true;
    }

    /**
     * Processes a Redis bucket for TTL and returns whether a TTL was set.
     *
     * <p>Used primarily for bulk TTL setting operations.
     *
     * @param bucket the Redis bucket to check and potentially set TTL on
     * @param ttl    duration representing the TTL value
     * @return true if a new TTL was set, false if no change was made
     */
    @SuppressWarnings("unchecked")
    public static boolean processBucketTTL(RBucket<?> bucket, Duration ttl) {
        if (bucket == null || ttl == null || !bucket.isExists()) {
            return false;
        }

        if (bucket.remainTimeToLive() < 0) {
            Object value = bucket.get();
            if (value == null) {
                return false;
            }

            ((RBucket<Object>) bucket).set(value, Duration.ofMillis(ttl.toMillis()));

            if (bucket.remainTimeToLive() > 0) {
                return true;
            }

            boolean expireSuccess = bucket.expire(ttl);
            return expireSuccess && bucket.remainTimeToLive() > 0;
        }

        return false;
    }
}