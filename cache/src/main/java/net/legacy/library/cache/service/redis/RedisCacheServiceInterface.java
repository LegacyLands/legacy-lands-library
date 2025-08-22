package net.legacy.library.cache.service.redis;

import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.CacheServiceInterface;
import org.redisson.api.RedissonClient;

import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Interface for the Redis cache service, providing methods to interact with the cache
 * using Redisson client, including support for lockable cache operations.
 *
 * <p>This interface extends {@link CacheServiceInterface} and provides Redis-specific
 * cache operations. It includes methods for retrieving or storing values in the cache,
 * with support for both locked and non-locked operations.
 *
 * @author qwq-dev
 * @see CacheServiceInterface
 * @see RedissonClient
 * @since 2024-12-21 20:03
 */
public interface RedisCacheServiceInterface extends CacheServiceInterface<RedissonClient, Object> {

    /**
     * Retrieves a value from the Redis cache or computes it if not found, without using a lock.
     *
     * <p>This method will first attempt to retrieve the value from the cache using the
     * {@code getCacheFunction}. If the value is not present, it will be computed by
     * the {@code query} supplier, and the result can be stored in the cache using
     * the {@code cacheBiConsumer}.
     *
     * @param getCacheFunction function to retrieve the cached value from Redis
     * @param query            the supplier to compute the value if not found in cache
     * @param cacheBiConsumer  the consumer to store the value in the cache after computation
     * @param cacheAfterQuery  flag indicating whether the computed value should be cached
     * @return the cached or computed value
     */
    <R> R getWithType(Function<RedissonClient, ?> getCacheFunction, Supplier<Object> query,
                      BiConsumer<RedissonClient, Object> cacheBiConsumer, boolean cacheAfterQuery);

    /**
     * Retrieves a value from the Redis cache or computes it if not found, with lock protection.
     *
     * <p>This method is similar to {@link #getWithType(Function, Supplier, BiConsumer, boolean)},
     * but it involves a locking mechanism to ensure that only one thread can compute and store
     * the value at a time. The lock is obtained using the {@code getLockFunction}, and the lock
     * settings are specified through {@code lockSettings}.
     *
     * @param getLockFunction  function to obtain the lock for cache operations
     * @param getCacheFunction function to retrieve the cached value from Redis
     * @param query            the supplier to compute the value if not found in cache
     * @param cacheBiConsumer  the consumer to store the value in the cache after computation
     * @param cacheAfterQuery  flag indicating whether the computed value should be cached
     * @param lockSettings     the settings that define the lock behavior
     * @return the cached or computed value
     */
    <R> R getWithType(Function<RedissonClient, Lock> getLockFunction, Function<RedissonClient, ?> getCacheFunction,
                      Supplier<Object> query, BiConsumer<RedissonClient, Object> cacheBiConsumer, boolean cacheAfterQuery,
                      LockSettings lockSettings);

    /**
     * Shuts down the Redis client connection gracefully.
     *
     * <p>This method is responsible for closing the connection to the Redis server
     * and releasing any resources associated with the Redisson client.
     */
    void shutdown();

}
