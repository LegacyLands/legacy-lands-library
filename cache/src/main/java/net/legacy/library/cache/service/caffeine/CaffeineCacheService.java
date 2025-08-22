package net.legacy.library.cache.service.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.legacy.library.cache.service.AbstractCacheService;
import net.legacy.library.cache.service.CacheServiceInterface;

/**
 * Synchronous Caffeine cache service implementation.
 *
 * <p>Provides caching functionality using Caffeine's synchronous cache implementation.
 *
 * @param <K> the cache key type
 * @param <V> the cache value type
 * @author qwq-dev
 * @see Cache
 * @see AbstractCacheService
 * @see CacheServiceInterface
 * @since 2024-12-21 20:03
 */
public class CaffeineCacheService<K, V> extends AbstractCacheService<Cache<K, V>, V> implements CacheServiceInterface<Cache<K, V>, V> {

    /**
     * Creates a new cache service with default Caffeine settings.
     */
    public CaffeineCacheService() {
        super(Caffeine.newBuilder().build());
    }

    /**
     * Creates a new cache service with a pre-configured cache.
     *
     * @param cache the pre-configured cache instance
     */
    public CaffeineCacheService(Cache<K, V> cache) {
        super(cache);
    }

}