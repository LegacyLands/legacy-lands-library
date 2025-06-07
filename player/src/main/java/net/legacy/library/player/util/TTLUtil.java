package net.legacy.library.player.util;

import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.util.List;

/**
 * Utility class for reliable TTL (Time-To-Live) operations in Redis using atomic operations.
 *
 * @author qwq-dev
 * @since 2025-04-11 19:45
 */
public class TTLUtil {
    /**
     * Lua script for atomic increment with TTL setting
     */
    private static final String INCREMENT_WITH_TTL_SCRIPT =
            """
            local key = KEYS[1]
            local ttl = tonumber(ARGV[1])
            local current = redis.call('incr', key)
            if current == 1 and ttl > 0 then
                redis.call('expire', key, ttl)
            end
            return current
            """;

    /**
     * Lua script for atomic TTL setting on existing keys
     */
    private static final String SET_TTL_IF_EXISTS_SCRIPT =
            """
            local key = KEYS[1]
            local ttl = tonumber(ARGV[1])
            if redis.call('exists', key) == 1 then
                local currentTtl = redis.call('ttl', key)
                if currentTtl == -1 then
                    return redis.call('expire', key, ttl)
                end
                return 1
            end
            return 0
            """;

    /**
     * Lua script for atomic set value with TTL
     */
    private static final String SET_WITH_TTL_SCRIPT =
            """
            local key = KEYS[1]
            local value = ARGV[1]
            local ttl = tonumber(ARGV[2])
            redis.call('set', key, value)
            if ttl > 0 then
                redis.call('expire', key, ttl)
            end
            return redis.call('ttl', key)
            """;

    /**
     * Lua script for atomic TTL setting if missing
     */
    private static final String SET_TTL_IF_MISSING_SCRIPT =
            """
            local key = KEYS[1]
            local ttl = tonumber(ARGV[1])
            if redis.call('exists', key) == 1 then
                local currentTtl = redis.call('ttl', key)
                if currentTtl == -1 then
                    return redis.call('expire', key, ttl)
                end
                return 1
            end
            return 0
            """;

    /**
     * Lua script for atomic bucket processing with TTL
     */
    private static final String PROCESS_BUCKET_TTL_SCRIPT =
            """
            local key = KEYS[1]
            local ttl = tonumber(ARGV[1])
            if redis.call('exists', key) == 1 then
                local currentTtl = redis.call('ttl', key)
                if currentTtl == -1 then
                    local value = redis.call('get', key)
                    if value then
                        redis.call('set', key, value)
                        local expireResult = redis.call('expire', key, ttl)
                        if expireResult == 1 then
                            return redis.call('ttl', key) > 0 and 1 or 0
                        end
                    end
                end
                return 0
            end
            return 0
            """;

    /**
     * Atomically increments a counter and sets TTL if it's a new key.
     *
     * <p>This operation is atomic and prevents race conditions between increment and TTL setting.
     *
     * @param redissonClient the Redisson client
     * @param key           the Redis key
     * @param ttlSeconds    TTL in seconds
     * @return the new counter value after increment
     */
    public static Long incrementWithTTL(RedissonClient redissonClient, String key, long ttlSeconds) {
        if (redissonClient == null || key == null || ttlSeconds <= 0) {
            throw new IllegalArgumentException("Invalid parameters for incrementWithTTL");
        }

        return redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                INCREMENT_WITH_TTL_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(key),
                ttlSeconds
        );
    }

    /**
     * Sets TTL for a Redis bucket using atomic operations.
     *
     * <p>Uses Lua script to prevent race conditions between get, set, and expire operations.
     *
     * @param redissonClient the Redis client
     * @param key           the Redis key
     * @param ttlSeconds    TTL in seconds
     * @return true if TTL was successfully set, false otherwise
     */
    @SuppressWarnings("DuplicatedCode")
    public static boolean setReliableTTL(RedissonClient redissonClient, String key, long ttlSeconds) {
        if (redissonClient == null || key == null || ttlSeconds <= 0) {
            return false;
        }

        // Get current value first
        RBucket<Object> bucket = redissonClient.getBucket(key);
        Object value = bucket.get();
        if (value == null) {
            return false;
        }

        // Use Lua script to atomically set value with TTL
        Long result = redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                SET_WITH_TTL_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(key),
                value.toString(),
                ttlSeconds
        );

        return result != null && result > 0;
    }

    /**
     * Atomically sets TTL for a key if it exists and doesn't already have one.
     *
     * <p>Uses Lua script to prevent race conditions between existence check and TTL setting.
     *
     * @param redissonClient the Redisson client
     * @param key           the Redis key
     * @param ttlSeconds    TTL in seconds
     * @return true if TTL was set, false if key doesn't exist or already has TTL
     */
    @SuppressWarnings("DuplicatedCode")
    public static boolean setTTLIfExistsAtomic(RedissonClient redissonClient, String key, long ttlSeconds) {
        if (redissonClient == null || key == null || ttlSeconds <= 0) {
            return false;
        }

        Integer result = redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                SET_TTL_IF_EXISTS_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(key),
                ttlSeconds
        );

        return result != null && result == 1;
    }

    /**
     * Atomically sets TTL for a key if it doesn't already have one.
     *
     * <p>Uses Lua script to prevent race conditions between TTL check and setting.
     *
     * @param redissonClient the Redis client
     * @param key           the Redis key
     * @param ttlSeconds    TTL in seconds
     * @return true if TTL was set or already had a valid TTL, false if setting failed
     */
    @SuppressWarnings("DuplicatedCode")
    public static boolean setTTLIfMissing(RedissonClient redissonClient, String key, long ttlSeconds) {
        if (redissonClient == null || key == null || ttlSeconds <= 0) {
            return false;
        }

        Integer result = redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                SET_TTL_IF_MISSING_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(key),
                ttlSeconds
        );

        return result != null && result == 1;
    }

    /**
     * Atomically processes a Redis key for TTL and returns whether a TTL was set.
     *
     * <p>Uses Lua script to prevent race conditions in bulk TTL setting operations.
     *
     * @param redissonClient the Redis client
     * @param key           the Redis key
     * @param ttlSeconds    TTL in seconds
     * @return true if a new TTL was set, false if no change was made
     */
    @SuppressWarnings("DuplicatedCode")
    public static boolean processBucketTTL(RedissonClient redissonClient, String key, long ttlSeconds) {
        if (redissonClient == null || key == null || ttlSeconds <= 0) {
            return false;
        }

        Integer result = redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                PROCESS_BUCKET_TTL_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(key),
                ttlSeconds
        );

        return result != null && result == 1;
    }
}