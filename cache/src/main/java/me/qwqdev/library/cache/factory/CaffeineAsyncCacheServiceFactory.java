package me.qwqdev.library.cache.factory;

import com.github.benmanes.caffeine.cache.AsyncCache;
import me.qwqdev.library.cache.service.caffeine.CaffeineAsyncCacheService;
import me.qwqdev.library.cache.service.caffeine.CaffeineAsyncCacheServiceInterface;

/**
 * Factory class for creating instances of {@link CaffeineAsyncCacheService}.
 *
 * @author qwq-dev
 * @since 2024-12-20 20:39
 */
public class CaffeineAsyncCacheServiceFactory {
    /**
     * Creates a new instance of {@link CaffeineAsyncCacheService} with a default {@link AsyncCache}.
     *
     * @param <K> the type of keys maintained by the cache
     * @param <V> the type of mapped values
     * @return a new instance of {@link CaffeineAsyncCacheService}
     */
    public static <K, V> CaffeineAsyncCacheServiceInterface<K, V> create() {
        return new CaffeineAsyncCacheService<>();
    }

    /**
     * Creates a new instance of {@link CaffeineAsyncCacheService} with the specified {@link AsyncCache}.
     *
     * @param cache the {@link AsyncCache} to be used by the service
     * @param <K>   the type of keys maintained by the cache
     * @param <V>   the type of mapped values
     * @return a new instance of {@link CaffeineAsyncCacheService}
     */
    public static <K, V> CaffeineAsyncCacheServiceInterface<K, V> create(AsyncCache<K, V> cache) {
        return new CaffeineAsyncCacheService<>(cache);
    }
}