package me.qwqdev.library.cache.service.memory;

import lombok.Data;
import me.qwqdev.library.cache.model.CacheItem;
import me.qwqdev.library.cache.model.ExpirationSettings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A memory-based cache implementation using a ConcurrentHashMap or custom map.
 * Supports caching with expiration and manual cache item removal.
 *
 * @author qwq-dev
 * @since 2024-12-20 13:54
 */
@Data
public class MemoryQueryCache<K, V> implements MemoryQueryCacheInterface<K, V> {
    private final Map<K, CacheItem<V>> cacheMap;

    /**
     * Default constructor, initializes an empty cache.
     */
    public MemoryQueryCache() {
        this.cacheMap = new ConcurrentHashMap<>();
    }

    /**
     * Constructor that accepts an initial map of cache data.
     *
     * @param cacheMap initial cache data
     */
    public MemoryQueryCache(Map<K, CacheItem<V>> cacheMap) {
        this.cacheMap = cacheMap;
    }

    /**
     * {@inheritDoc}
     *
     * @param key             {@inheritDoc}
     * @param removeIfExpired {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CacheItem<V> getCacheItem(K key, boolean removeIfExpired) {
        CacheItem<V> cacheItem = cacheMap.get(key);

        if (cacheItem == null) {
            return null;
        }

        if (cacheItem.isExpired() && removeIfExpired) {
            cacheMap.remove(key);
            return cacheItem;
        }

        return cacheItem;
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public V get(K key) {
        CacheItem<V> cacheItem = cacheMap.get(key);

        if (cacheItem == null) {
            return null;
        }

        boolean expired = cacheItem.isExpired();

        if (expired) {
            cacheMap.remove(key);
            return null;
        }

        return cacheItem.getValue();
    }

    /**
     * {@inheritDoc}
     *
     * @param key                {@inheritDoc}
     * @param value              {@inheritDoc}
     * @param expirationSettings {@inheritDoc}
     */
    @Override
    public void put(K key, V value, ExpirationSettings expirationSettings) {
        cacheMap.put(key, new CacheItem<>(value, expirationSettings));
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     */
    @Override
    public void remove(K key) {
        cacheMap.remove(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        cacheMap.clear();
    }

    /**
     * {@inheritDoc}
     *
     * @return
     */
    @Override
    public int size() {
        return cacheMap.size();
    }
}