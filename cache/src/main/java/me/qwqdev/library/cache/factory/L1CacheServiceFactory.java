package me.qwqdev.library.cache.factory;

import me.qwqdev.library.cache.service.L1CacheService;
import me.qwqdev.library.cache.service.QueryCacheInterface;

/**
 * A factory class for creating instances of L1CacheService.
 *
 * @since 2024-12-20 15:37
 */
public class L1CacheServiceFactory {
    /**
     * Creates a L1CacheService with the specified QueryCacheInterface implementation.
     *
     * @param queryCacheInterface the QueryCacheInterface implementation to be used by the L1CacheService
     * @param <K>                 the type of cache key
     * @param <V>                 the type of cache value
     * @return a new instance of L1CacheService
     */
    public static <K, V> L1CacheService<K, V> create(QueryCacheInterface<K, V> queryCacheInterface) {
        return new L1CacheService<>(queryCacheInterface);
    }
}