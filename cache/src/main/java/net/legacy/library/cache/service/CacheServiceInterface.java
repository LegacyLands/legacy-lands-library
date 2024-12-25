package net.legacy.library.cache.service;

import net.legacy.library.cache.model.LockSettings;

import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Interface defining core cache service operations.
 *
 * <p>This interface provides methods for accessing and manipulating cache, supporting
 * both locked and non-locked cache operations.
 *
 * <p>It offers a generic structure that can be implemented by various cache services,
 * including both in-memory and distributed caches, and supports both the standard
 * cache access and computation as well as lock-protected access.
 *
 * @param <C> the type of the cache implementation
 * @param <V> the type of the cache value
 * @author qwq-dev
 * @see LockableCacheInterface
 * @see LockSettings
 * @since 2024-12-21 18:54
 */
public interface CacheServiceInterface<C, V> extends LockableCacheInterface<C> {

    /**
     * Retrieves a value from the cache or computes it if not found.
     *
     * <p>This method first attempts to retrieve the value from the cache using the
     * {@code getCacheFunction}. If the value is not found, it will be computed using
     * the {@code query} supplier, and then optionally stored in the cache using
     * the {@code cacheBiConsumer}.
     *
     * @param getCacheFunction function to retrieve the cached value from the cache
     * @param query            the supplier to compute the value if not found in the cache
     * @param cacheBiConsumer  the consumer to store the computed value in the cache
     * @param cacheAfterQuery  flag indicating whether the computed value should be cached
     * @return the cached value if present, or the newly computed value
     */
    V get(Function<C, V> getCacheFunction, Supplier<V> query,
          BiConsumer<C, V> cacheBiConsumer, boolean cacheAfterQuery);

    /**
     * Retrieves a value from the cache or computes it if not found, with lock protection.
     *
     * <p>This method is similar to {@link #get(Function, Supplier, BiConsumer, boolean)},
     * but it includes lock protection to ensure that only one thread can compute and store
     * the value at a time. The lock is obtained using the {@code getLockFunction}, and the
     * lock settings are provided through {@code lockSettings}.
     *
     * @param getLockFunction  function to obtain the lock for cache operations
     * @param getCacheFunction function to retrieve the cached value from the cache
     * @param query            the supplier to compute the value if not found in the cache
     * @param cacheBiConsumer  the consumer to store the computed value in the cache
     * @param cacheAfterQuery  flag indicating whether the computed value should be cached
     * @param lockSettings     settings that define the lock behavior
     * @return the cached value if present, or the newly computed value
     */
    V get(Function<C, Lock> getLockFunction, Function<C, V> getCacheFunction,
          Supplier<V> query, BiConsumer<C, V> cacheBiConsumer,
          boolean cacheAfterQuery, LockSettings lockSettings);
}
