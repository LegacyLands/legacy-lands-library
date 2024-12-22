package me.qwqdev.library.cache.service.caffeine;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.qwqdev.library.cache.service.AbstractCacheService;
import me.qwqdev.library.cache.service.CacheServiceInterface;

/**
 * Asynchronous Caffeine cache service implementation.
 *
 * <p>Provides caching functionality using Caffeine's asynchronous cache implementation.
 *
 * @param <K> the cache key type
 * @param <V> the cache value type
 * @author qwq-dev
 * @see AsyncCache
 * @see AbstractCacheService
 * @see CacheServiceInterface
 * @since 2024-12-21 20:03
 */
public class CaffeineAsyncCacheService<K, V> extends AbstractCacheService<AsyncCache<K, V>, V> implements CacheServiceInterface<AsyncCache<K, V>, V> {
    /**
     * Creates a new async cache service with default Caffeine settings.
     */
    public CaffeineAsyncCacheService() {
        super(Caffeine.newBuilder().buildAsync());
    }

    /**
     * Creates a new async cache service with a pre-configured cache.
     *
     * @param cache the pre-configured async cache instance
     */
    public CaffeineAsyncCacheService(AsyncCache<K, V> cache) {
        super(cache);
    }
}