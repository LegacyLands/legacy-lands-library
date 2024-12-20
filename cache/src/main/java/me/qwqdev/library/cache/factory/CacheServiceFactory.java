package me.qwqdev.library.cache.factory;

import me.qwqdev.library.cache.service.CacheService;
import me.qwqdev.library.cache.service.QueryCacheInterface;

/**
 * @author qwq-dev
 * @since 2024-12-20 15:37
 */
public class CacheServiceFactory {
    public static <K, V> CacheService<K, V> create(QueryCacheInterface<K, V> queryCacheInterface) {
        return new CacheService<>(queryCacheInterface);
    }
}
