package me.qwqdev.library.cache.service;

import me.qwqdev.library.cache.model.ExpirationSettings;

import java.util.function.Supplier;

/**
 * A generic interface for cache implementations that extends {@link QueryCacheInterface}.
 *
 * @param <K> the type of the cache key
 * @param <V> the type of the cache value
 * @author qwq-dev
 * @see QueryCacheInterface
 * @since 2024-12-20
 */
public interface CacheServiceInterface<K, V> extends QueryCacheInterface<K, V> {
    /**
     * Retrieves the value associated with the specified key from the cache.
     *
     * @param key                the key whose associated value is to be returned
     * @param query              if the cache misses, then execute
     * @param cacheAfterQuery    whether to cache the result after the query
     * @param expirationSettings expiration settings for the cache item
     * @return the value associated with the specified key, or the result of the query operation
     */
    V get(K key, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings);
}
