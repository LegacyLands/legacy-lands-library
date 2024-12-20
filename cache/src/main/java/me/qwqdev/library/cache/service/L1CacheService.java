package me.qwqdev.library.cache.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import me.qwqdev.library.cache.model.ExpirationSettings;

import java.util.function.Supplier;

/**
 * A service class for interacting with caches, supporting cache retrieval and optional caching after querying.
 *
 * <p>Although getters are provided to support access to {@link #queryCacheInterface} fields, future second-level caches do not support
 * {@link me.qwqdev.library.cache.service.memory.MemoryQueryCacheInterface#getCacheItem(Object, boolean)} methods.
 * Unless you are really sure that the {@link #queryCacheInterface} field is
 * {@link me.qwqdev.library.cache.service.memory.MemoryQueryCacheInterface} and cast it, don't do this.
 *
 * @param <K> the type of cache key
 * @param <V> the type of cache value
 * @author qwq-dev
 * @since 2024-12-20 13:58
 */
@Data
@RequiredArgsConstructor
public class L1CacheService<K, V> implements CacheServiceInterface<K, V> {
    private final QueryCacheInterface<K, V> queryCacheInterface;

    /**
     * {@inheritDoc}
     *
     * @param key                {@inheritDoc}
     * @param query              {@inheritDoc}
     * @param cacheAfterQuery    {@inheritDoc}
     * @param expirationSettings {@inheritDoc}
     * @return
     */
    @Override
    public V get(K key, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings) {
        V cachedValue = queryCacheInterface.get(key);

        if (cachedValue != null) {
            return cachedValue;
        }

        V result = query.get();

        if (result != null && cacheAfterQuery) {
            queryCacheInterface.put(key, result, expirationSettings);
        }

        return result;
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
        queryCacheInterface.put(key, value, expirationSettings);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public V get(K key) {
        return queryCacheInterface.get(key);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     */
    @Override
    public void remove(K key) {
        queryCacheInterface.remove(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        queryCacheInterface.clear();
    }

    /**
     * {@inheritDoc}
     *
     * @return{@inheritDoc}
     */
    @Override
    public int size() {
        return queryCacheInterface.size();
    }
}
