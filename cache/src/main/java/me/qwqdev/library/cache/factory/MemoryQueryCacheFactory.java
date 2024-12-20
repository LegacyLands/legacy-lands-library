package me.qwqdev.library.cache.factory;

import me.qwqdev.library.cache.model.CacheItem;
import me.qwqdev.library.cache.service.memory.MemoryQueryCache;
import me.qwqdev.library.cache.service.memory.MemoryQueryCacheInterface;

import java.util.Map;

/**
 * A factory class to create instances of MemoryQueryCache with or without initial data.
 * This class provides static methods to create empty caches or caches pre-populated
 * with initial data.
 *
 * @author qwq-dev
 * @see MemoryQueryCache
 * @since 2024-12-20 13:56
 */
public class MemoryQueryCacheFactory {
    /**
     * Creates an empty MemoryQueryCache.
     *
     * @param <K> the type of cache key
     * @param <V> the type of cache value
     * @return a new instance of MemoryQueryCache
     */
    public static <K, V> MemoryQueryCacheInterface<K, V> create() {
        return new MemoryQueryCache<>();
    }

    /**
     * Creates a MemoryQueryCache with an initial set of cache data.
     *
     * @param <K>      the type of cache key
     * @param <V>      the type of cache value
     * @param cacheMap the initial cache data
     * @return a new instance of MemoryQueryCache with the given data
     */
    public static <K, V> MemoryQueryCacheInterface<K, V> create(Map<K, CacheItem<V>> cacheMap) {
        return new MemoryQueryCache<>(cacheMap);
    }
}
