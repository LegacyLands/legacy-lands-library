package me.qwqdev.library.cache.service;

import me.qwqdev.library.cache.model.ExpirationSettings;

/**
 * A generic interface for cache implementations.
 * Provides basic methods for caching, retrieving, removing, and clearing items.
 *
 * @param <K> the type of cache key
 * @param <V> the type of cache value
 * @author qwq-dev
 * @since 2024-12-20 13:54
 */
public interface QueryCacheInterface<K, V> {
    /**
     * Puts a value into the cache with the specified expiration settings.
     *
     * @param key                the cache key
     * @param value              the cache value
     * @param expirationSettings the expiration settings for the cache item
     * @see ExpirationSettings
     */
    void put(K key, V value, ExpirationSettings expirationSettings);

    /**
     * Retrieves a value from the cache by its key.
     *
     * @param key the cache key
     * @return the cached value, or null if not found
     */
    V get(K key);

    /**
     * Removes a value from the cache by its key.
     *
     * @param key the cache key
     */
    void remove(K key);

    /**
     * Clears all items from the cache.
     */
    void clear();

    /**
     * Retrieves the number of items in the cache.
     *
     * @return the number of items in the cache
     */
    int size();
}