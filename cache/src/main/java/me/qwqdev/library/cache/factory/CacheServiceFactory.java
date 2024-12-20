package me.qwqdev.library.cache.factory;

import me.qwqdev.library.cache.service.CacheService;
import me.qwqdev.library.cache.service.QueryCacheInterface;

/**
 * A factory class for creating instances of CacheService.
 *
 * @since 2024-12-20 15:37
 */
public class CacheServiceFactory {
    /**
     * Creates a CacheService with the specified QueryCacheInterface implementation.
     *
     * @param queryCacheInterface the QueryCacheInterface implementation to be used by the CacheService
     * @param <K>                 the type of cache key
     * @param <V>                 the type of cache value
     * @return a new instance of CacheService
     */
    public static <K, V> CacheService<K, V> create(QueryCacheInterface<K, V> queryCacheInterface) {
        return new CacheService<>(queryCacheInterface);
    }
}