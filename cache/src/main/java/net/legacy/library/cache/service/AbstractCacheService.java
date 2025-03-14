package net.legacy.library.cache.service;

import net.legacy.library.cache.model.LockSettings;

import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Abstract base class for cache services that provides common caching functionality.
 *
 * <p>The service supports both locked and non-locked cache operations,
 * with configurable behavior for cache misses and storage policies.
 *
 * @param <C> the cache implementation type, representing the underlying cache technology
 * @param <V> the cache value type that will be stored and retrieved
 * @author qwq-dev
 * @see AbstractLockable
 * @see LockSettings
 * @see Lock
 * @since 2024-12-21 18:53
 */
public abstract class AbstractCacheService<C, V> extends AbstractLockable<C> {
    /**
     * Creates a new cache service instance with the specified cache implementation.
     *
     * @param cache the underlying cache implementation to be used
     */
    protected AbstractCacheService(C cache) {
        super(cache);
    }

    /**
     * Retrieves a value from cache or computes it if not present.
     * This method provides a non-locked approach to cache access and updates.
     *
     * @param getCacheFunction the function to retrieve value from cache, typically implementing
     *                         the specific cache technology's retrieval mechanism
     * @param query            the supplier to compute value if not found in cache, executed only on cache misses
     * @param cacheBiConsumer  the consumer to handle cache operations after successful query,
     *                         can be used for additional cache maintenance tasks
     * @param cacheAfterQuery  flag indicating whether to store the computed value in cache after retrieval
     * @return the cached value if present, otherwise the newly computed and optionally cached value
     */
    public V get(Function<C, V> getCacheFunction, Supplier<V> query,
                 BiConsumer<C, V> cacheBiConsumer, boolean cacheAfterQuery) {
        return retrieveOrStoreInCache(getCacheFunction.apply(getResource()),
                query, cacheBiConsumer, cacheAfterQuery);
    }

    /**
     * Retrieves a value from cache or computes it if not present, with lock protection.
     * This method ensures thread-safe access to the cache through a locking mechanism.
     *
     * @param getLockFunction  the function to obtain the lock for thread-safe operations
     * @param getCacheFunction the function to retrieve value from cache, executed under lock protection
     * @param query            the supplier to compute value if not found in cache, executed only on cache misses
     * @param cacheBiConsumer  the consumer to handle cache operations after successful query
     * @param cacheAfterQuery  flag indicating whether to store the computed value in cache after retrieval
     * @param lockSettings     the settings controlling lock behavior including timeout and retry policies
     * @return the cached value if present, otherwise the newly computed and optionally cached value
     * @see LockSettings
     */
    public V get(Function<C, Lock> getLockFunction, Function<C, V> getCacheFunction,
                 Supplier<V> query, BiConsumer<C, V> cacheBiConsumer,
                 boolean cacheAfterQuery, LockSettings lockSettings) {
        return execute(getLockFunction,
                cache -> retrieveOrStoreInCache(getCacheFunction.apply(cache),
                        query, cacheBiConsumer, cacheAfterQuery),
                lockSettings);
    }

    /**
     * Core method implementing the cache retrieval and storage logic.
     *
     * @param value           the value from cache, may be null indicating a cache miss
     * @param query           the supplier to compute value if not found in cache
     * @param cacheBiConsumer the consumer to handle cache storage operations
     * @param cacheAfterQuery flag indicating whether to store the computed value in cache
     * @return the cached value if present, otherwise the newly computed and optionally cached value
     */
    protected V retrieveOrStoreInCache(V value, Supplier<V> query,
                                       BiConsumer<C, V> cacheBiConsumer,
                                       boolean cacheAfterQuery) {
        if (value != null) {
            return value;
        }

        value = query.get();

        if (value != null && cacheAfterQuery) {
            cacheBiConsumer.accept(getResource(), value);
        }

        return value;
    }
}
