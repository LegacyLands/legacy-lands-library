package me.qwqdev.library.cache.factory;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.experimental.UtilityClass;
import me.qwqdev.library.cache.service.caffeine.CaffeineCacheService;
import me.qwqdev.library.cache.service.caffeine.CaffeineCacheServiceInterface;

/**
 * Factory class for creating instances of {@link CaffeineCacheService}.
 *
 * @author qwq-dev
 * @since 2024-12-20 20:39
 */
@UtilityClass
public class CaffeineCacheServiceFactory {
    /**
     * Creates a new instance of {@link CaffeineCacheService} with a default {@link Cache}.
     *
     * @param <K> the type of keys maintained by the cache
     * @param <V> the type of mapped values
     * @return a new instance of {@link CaffeineCacheService}
     */
    public static <K, V> CaffeineCacheServiceInterface<K, V> create() {
        return new CaffeineCacheService<>();
    }

    /**
     * Creates a new instance of {@link CaffeineCacheService} with the specified {@link Cache}.
     *
     * @param cache the {@link Cache} to be used by the service
     * @param <K>   the type of keys maintained by the cache
     * @param <V>   the type of mapped values
     * @return a new instance of {@link CaffeineCacheService}
     */
    public static <K, V> CaffeineCacheServiceInterface<K, V> create(Cache<K, V> cache) {
        return new CaffeineCacheService<>(cache);
    }
}