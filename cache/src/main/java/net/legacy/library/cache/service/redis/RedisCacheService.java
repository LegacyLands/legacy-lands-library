package net.legacy.library.cache.service.redis;

import io.fairyproject.log.Log;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.AbstractLockable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Redis implementation of cache service using Redisson client.
 *
 * <p>Provides Redis-specific caching functionality with support for
 * expiration and lock-protected operations.
 *
 * @author qwq-dev
 * @see RedissonClient
 * @see RedisCacheServiceInterface
 * @see AbstractLockable
 * @since 2024-12-21 20:03
 */
public class RedisCacheService extends AbstractLockable<RedissonClient> implements RedisCacheServiceInterface {

    /**
     * Constructs a new Redis cache service with the specified Redisson client.
     *
     * <p>The client gets its own Redisson thread pools, capped by default ({@link RedissonSharedResources#limit}),
     * unless the system property {@value RedissonSharedResources#ENABLED_PROPERTY} is {@code true}, which puts it on
     * the shared pools, see {@link RedissonSharedResources}.
     *
     * @param config {@link Config}
     * @see Config
     */
    public RedisCacheService(Config config) {
        this(config, RedissonSharedResources.enabledByDefault());
    }

    /**
     * Constructs a new Redis cache service with the specified Redisson client.
     *
     * @param config       {@link Config}
     * @param shareThreads whether the client runs on the event loop group and worker pool shared by every Legacy
     *                     Redis service in the JVM that opts in; otherwise it gets its own pools, capped by default,
     *                     see {@link RedissonSharedResources}
     * @see Config
     */
    public RedisCacheService(Config config, boolean shareThreads) {
        super(Redisson.create(shareThreads
                ? RedissonSharedResources.share(config)
                : RedissonSharedResources.limit(config)));
    }

    /**
     * {@inheritDoc}
     *
     * @param getCacheFunction {@inheritDoc}
     * @param query            {@inheritDoc}
     * @param cacheBiConsumer  {@inheritDoc}
     * @param cacheAfterQuery  {@inheritDoc}
     * @param <R>              {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public <R> R getWithType(Function<RedissonClient, ?> getCacheFunction, Supplier<Object> query,
                             BiConsumer<RedissonClient, Object> cacheBiConsumer, boolean cacheAfterQuery) {
        return (R) retrieveOrStoreInCache(getCacheFunction.apply(getResource()), query, cacheBiConsumer, cacheAfterQuery);
    }

    /**
     * {@inheritDoc}
     *
     * @param getLockFunction  {@inheritDoc}
     * @param getCacheFunction {@inheritDoc}
     * @param query            {@inheritDoc}
     * @param cacheBiConsumer  {@inheritDoc}
     * @param cacheAfterQuery  {@inheritDoc}
     * @param lockSettings     {@inheritDoc}
     * @param <R>              {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public <R> R getWithType(Function<RedissonClient, Lock> getLockFunction, Function<RedissonClient, ?> getCacheFunction,
                             Supplier<Object> query, BiConsumer<RedissonClient, Object> cacheBiConsumer, boolean cacheAfterQuery,
                             LockSettings lockSettings) {
        return (R) execute(getLockFunction,
                cache -> retrieveOrStoreInCache(getCacheFunction.apply(cache),
                        query, cacheBiConsumer, cacheAfterQuery), lockSettings
        );
    }


    /**
     * {@inheritDoc}
     *
     * @param getCacheFunction {@inheritDoc}
     * @param query            {@inheritDoc}
     * @param cacheBiConsumer  {@inheritDoc}
     * @param cacheAfterQuery  {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public Object get(Function<RedissonClient, Object> getCacheFunction, Supplier<Object> query,
                      BiConsumer<RedissonClient, Object> cacheBiConsumer, boolean cacheAfterQuery) {
        return retrieveOrStoreInCache(getCacheFunction.apply(getResource()), query, cacheBiConsumer, cacheAfterQuery);
    }

    /**
     * {@inheritDoc}
     *
     * @param getLockFunction  {@inheritDoc}
     * @param getCacheFunction {@inheritDoc}
     * @param query            {@inheritDoc}
     * @param cacheBiConsumer  {@inheritDoc}
     * @param cacheAfterQuery  {@inheritDoc}
     * @param lockSettings     {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public Object get(Function<RedissonClient, Lock> getLockFunction,
                      Function<RedissonClient, Object> getCacheFunction, Supplier<Object> query,
                      BiConsumer<RedissonClient, Object> cacheBiConsumer, boolean cacheAfterQuery,
                      LockSettings lockSettings) {
        return execute(getLockFunction,
                cache -> retrieveOrStoreInCache(getCacheFunction.apply(cache),
                        query, cacheBiConsumer, cacheAfterQuery), lockSettings
        );
    }

    /**
     * Core method implementing the cache retrieval and storage logic with expiration support.
     *
     * @param value           the value from cache, may be {@code null} indicating a cache miss
     * @param query           the supplier to compute value if not found in cache
     * @param cacheBiConsumer the consumer to handle cache storage operations
     * @param cacheAfterQuery flag indicating whether to store the computed value in cache
     * @return the cached value if present, otherwise the newly computed value
     */
    protected Object retrieveOrStoreInCache(Object value, Supplier<?> query,
                                            BiConsumer<RedissonClient, Object> cacheBiConsumer,
                                            boolean cacheAfterQuery) {
        if (value != null) {
            return value;
        }

        value = query.get();

        if (value != null && cacheAfterQuery && cacheBiConsumer != null) {
            cacheBiConsumer.accept(getResource(), value);
        }

        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        RedissonClient cache = getResource();

        if (cache != null) {
            try {
                cache.shutdown();
            } catch (Exception exception) {
                Log.error("Error shutting down Redisson client", exception);
            }
        }
    }

}
