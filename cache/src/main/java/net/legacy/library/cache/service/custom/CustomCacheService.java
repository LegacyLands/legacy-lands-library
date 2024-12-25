package net.legacy.library.cache.service.custom;

import net.legacy.library.cache.service.AbstractCacheService;
import net.legacy.library.cache.service.CacheServiceInterface;

/**
 * CustomCacheService is a custom implementation of a cache service.
 *
 * @param <C> the cache implementation type
 * @param <V> the cache value type
 * @author qwq-dev
 * @see AbstractCacheService
 * @see CacheServiceInterface
 * @since 2024-12-25 12:17
 */
public class CustomCacheService<C, V> extends AbstractCacheService<C, V> implements CacheServiceInterface<C, V> {
    /**
     * Constructs a new CustomCacheService with the specified cache implementation.
     *
     * @param cache the underlying cache implementation
     */
    public CustomCacheService(C cache) {
        super(cache);
    }
}