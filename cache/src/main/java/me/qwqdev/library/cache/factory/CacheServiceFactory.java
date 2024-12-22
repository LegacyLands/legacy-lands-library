package me.qwqdev.library.cache.factory;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.experimental.UtilityClass;
import me.qwqdev.library.cache.service.CacheServiceInterface;
import me.qwqdev.library.cache.service.caffeine.CaffeineAsyncCacheService;
import me.qwqdev.library.cache.service.caffeine.CaffeineCacheService;
import me.qwqdev.library.cache.service.redis.RedisCacheService;
import me.qwqdev.library.cache.service.redis.RedisCacheServiceInterface;
import org.redisson.config.Config;

/**
 * Factory for creating cache service instances.
 *
 * <p>Provides centralized creation of different cache implementations
 * with various configuration options.
 *
 * @author qwq-dev
 * @since 2024-12-21 20:10
 */
@UtilityClass
public final class CacheServiceFactory {
    /**
     * Creates a Redis cache service with the specified configuration.
     *
     * @param config the Redis configuration
     * @return a new Redis cache service instance
     */
    public static RedisCacheServiceInterface createRedisCache(Config config) {
        return new RedisCacheService(config);
    }

    /**
     * Creates a Caffeine synchronous cache service with custom configuration.
     *
     * @param cache the Caffeine cache
     * @param <K>   the cache key type
     * @param <V>   the cache value type
     * @return a new Caffeine cache service instance
     */
    public static <K, V> CacheServiceInterface<Cache<K, V>, V> createCaffeineCache(Cache<K, V> cache) {
        return new CaffeineCacheService<>(cache);
    }

    /**
     * Creates a Caffeine synchronous cache service with default configuration.
     *
     * @param <K> the cache key type
     * @param <V> the cache value type
     * @return a new Caffeine cache service instance
     */
    public static <K, V> CacheServiceInterface<Cache<K, V>, V> createCaffeineCache() {
        return new CaffeineCacheService<>();
    }

    /**
     * Creates a Caffeine asynchronous cache service with custom configuration.
     *
     * @param asyncCache the Caffeine async cache
     * @param <K>        the cache key type
     * @param <V>        the cache value type
     * @return a new async Caffeine cache service instance
     */
    public static <K, V> CacheServiceInterface<AsyncCache<K, V>, V> createCaffeineAsyncCache(AsyncCache<K, V> asyncCache) {
        return new CaffeineAsyncCacheService<>(asyncCache);
    }

    /**
     * Creates a Caffeine asynchronous cache service with default configuration.
     *
     * @param <K> the cache key type
     * @param <V> the cache value type
     * @return a new async Caffeine cache service instance
     */
    public static <K, V> CacheServiceInterface<AsyncCache<K, V>, V> createCaffeineAsyncCache() {
        return new CaffeineAsyncCacheService<>();
    }
}
