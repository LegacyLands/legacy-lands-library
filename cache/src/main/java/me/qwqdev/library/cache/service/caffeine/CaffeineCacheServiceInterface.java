package me.qwqdev.library.cache.service.caffeine;

import java.util.function.Supplier;

/**
 * Interface for a cache service using Caffeine.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 * @author qwq-dev
 * @since 2024-12-20 20:18
 */
public interface CaffeineCacheServiceInterface<K, V> {
    /**
     * Retrieves a value from the cache. If the value is not present in the cache,
     * it will be fetched using the provided query supplier and optionally cached.
     *
     * @param key             the key whose associated value is to be returned
     * @param query           a supplier to fetch the value if it is not present in the cache
     * @param cacheAfterQuery if true, the fetched value will be cached
     * @return the value associated with the specified key
     */
    V get(K key, Supplier<V> query, boolean cacheAfterQuery);
}