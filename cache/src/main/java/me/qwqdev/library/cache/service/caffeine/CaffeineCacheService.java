package me.qwqdev.library.cache.service.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;

import java.util.function.Supplier;

/**
 * Implementation of the {@link CaffeineCacheServiceInterface} using Caffeine's {@link Cache}.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 * @author qwq-dev
 * @see CaffeineCacheServiceInterface
 * @see Cache
 * @since 2024-12-20 20:18
 */
@Data
public class CaffeineCacheService<K, V> implements CaffeineCacheServiceInterface<K, V> {
    private final Cache<K, V> cache;

    /**
     * Instantiates with a default {@link Cache}.
     *
     * @see Caffeine#build()
     */
    public CaffeineCacheService() {
        this.cache = Caffeine.newBuilder().build();
    }

    /**
     * Instantiates with a specified {@link Cache}.
     *
     * @param cache the {@link Cache} to be used by this service
     */
    public CaffeineCacheService(Cache<K, V> cache) {
        this.cache = cache;
    }

    /**
     * {@inheritDoc}
     *
     * @param key             {@inheritDoc}
     * @param query           {@inheritDoc}
     * @param cacheAfterQuery {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public V get(K key, Supplier<V> query, boolean cacheAfterQuery) {
        V value = cache.getIfPresent(key);

        if (value != null) {
            return value;
        }

        value = query.get();

        if (value == null) {
            return null;
        }

        if (cacheAfterQuery) {
            cache.put(key, value);
        }

        return value;
    }
}