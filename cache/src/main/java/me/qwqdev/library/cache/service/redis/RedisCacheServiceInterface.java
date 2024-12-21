package me.qwqdev.library.cache.service.redis;

import me.qwqdev.library.cache.model.ExpirationSettings;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Interface for redis cache service.
 *
 * @param <K> the type of the key
 * @param <V> the type of the value
 * @author qwq-dev
 * @since 2024-12-21 12:16
 */
public interface RedisCacheServiceInterface<K, V> {
    /**
     * Get the {@link RedissonClient} that implements the cache
     *
     * @return the {@link RedissonClient}
     */
    RedissonClient getRedissonClient();

    /**
     * Retrieves a value from the Redis cache. If the value is not found, it fetches the value
     * using the provided query function and optionally stores it in the cache.
     *
     * @param key                the cache key
     * @param function           function to access the Redis cache (e.g., using {@link RedissonClient#getBucket(String)})
     * @param query              the query function to fetch the value when not present in the cache
     * @param cacheAfterQuery    whether to cache the value after querying
     * @param expirationSettings the expiration settings for the cache item
     * @return the value from the cache, or the queried value if not found
     */
    V get(K key, Function<RedissonClient, RBucket<V>> function, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings);

    /**
     * Retrieves a value from the Redis cache with a distributed lock to ensure exclusive access
     * in high-concurrency environments. If the value is not found, it fetches the value using
     * the provided query function and optionally stores it in the cache.
     *
     * @param key                the cache key
     * @param function           function to access the Redis cache (e.g., using {@link RedissonClient#getBucket(String)})
     * @param query              the query function to fetch the value when not present in the cache
     * @param cacheAfterQuery    whether to cache the value after querying
     * @param expirationSettings the expiration settings for the cache item
     * @return the value from the cache, or the queried value if not found
     * @throws RuntimeException if the lock cannot be acquired
     */
    V getWithLock(K key, Function<RedissonClient, RBucket<V>> function, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings);

    /**
     * Shuts down the Redisson client and releases resources.
     */
    void shutdown();
}
