package me.qwqdev.library.cache.service.memory;

import me.qwqdev.library.cache.model.CacheItem;
import me.qwqdev.library.cache.service.QueryCacheInterface;

/**
 * MemoryQueryCacheInterface extends QueryCacheInterface, adding methods specific
 * to memory cache operations, such as get {@link CacheItem} directly and define whether to remove expired elements.
 *
 * @param <K> the type of cache key
 * @param <V> the type of cache value
 * @author qwq-dev
 * @see CacheItem
 * @see QueryCacheInterface
 * @since 2024-12-20 14:39
 */
public interface MemoryQueryCacheInterface<K, V> extends QueryCacheInterface<K, V> {
    /**
     * Retrieves a cache item by its key, with an option to remove it if expired.
     *
     * @param key             the cache key
     * @param removeIfExpired whether to remove the item if it is expired
     * @return the cache item, or null if not found
     */
    CacheItem<V> getCacheItem(K key, boolean removeIfExpired);
}
