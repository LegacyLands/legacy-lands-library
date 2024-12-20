package me.qwqdev.library.cache.service.memory;

import lombok.Data;
import me.qwqdev.library.cache.model.CacheItem;
import me.qwqdev.library.cache.model.ExpirationSettings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Implementation of the {@link MemoryCacheServiceInterface} using an in-memory {@link Map}.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 * @author qwq-dev
 * @see MemoryCacheServiceInterface
 * @see CacheItem
 * @see ExpirationSettings
 * @since 2024-12-20 20:39
 */
@Data
public class MemoryCacheService<K, V> implements MemoryCacheServiceInterface<K, V> {
    private final Map<K, CacheItem<V>> cache;

    /**
     * Instantiates with a default {@link ConcurrentHashMap}.
     *
     * @see ConcurrentHashMap
     */
    public MemoryCacheService() {
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Instantiates with a specified {@link Map}.
     *
     * @param cache the {@link Map} to be used by this service
     */
    public MemoryCacheService(Map<K, CacheItem<V>> cache) {
        this.cache = cache;
    }

    /**
     * {@inheritDoc}
     *
     * @param key                {@inheritDoc}
     * @param query              {@inheritDoc}
     * @param cacheAfterQuery    {@inheritDoc}
     * @param expirationSettings {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public V get(K key, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings) {
        CacheItem<V> cacheItem = cache.get(key);

        if (cacheItem == null) {
            V value = query.get();

            if (cacheAfterQuery) {
                cache.put(key, new CacheItem<>(value, expirationSettings));
            }

            return value;
        }

        if (cacheItem.isExpired()) {
            cache.remove(key);
            return null;
        }

        return cacheItem.getValue();
    }
}