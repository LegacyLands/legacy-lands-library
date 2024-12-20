package me.qwqdev.library.cache.service.memory;

import me.qwqdev.library.cache.model.ExpirationSettings;

import java.util.function.Supplier;

/**
 * Interface for a memory cache service.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 * @author qwq-dev
 * @since 2024-12-20 20:39
 */
public interface MemoryCacheServiceInterface<K, V> {
    /**
     * Retrieves a value from the cache. If the value is not present in the cache,
     * it will be fetched using the provided query supplier and optionally cached
     * with the specified expiration settings.
     *
     * <p>It should be noted that the Memory cache based on the expiration time
     * of the element will only be checked when the get method is called.
     *
     * <p>If we need a better expiration strategy, please consider
     * using {@link me.qwqdev.library.cache.service.caffeine.CaffeineCacheService} or
     * {@link me.qwqdev.library.cache.service.caffeine.CaffeineAsyncCacheService} and
     * create them using the factory class.
     *
     * @param key                the key whose associated value is to be returned
     * @param query              a supplier to fetch the value if it is not present in the cache
     * @param cacheAfterQuery    if true, the fetched value will be cached
     * @param expirationSettings the settings for cache item expiration
     * @return the value associated with the specified key
     * @see ExpirationSettings
     * @see me.qwqdev.library.cache.service.caffeine.CaffeineCacheService
     * @see me.qwqdev.library.cache.service.caffeine.CaffeineAsyncCacheService
     * @see me.qwqdev.library.cache.factory.CaffeineCacheServiceFactory
     * @see me.qwqdev.library.cache.factory.CaffeineAsyncCacheServiceFactory
     */
    V get(K key, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings);
}