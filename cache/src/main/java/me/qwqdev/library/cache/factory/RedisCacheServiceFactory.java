package me.qwqdev.library.cache.factory;

import lombok.experimental.UtilityClass;
import me.qwqdev.library.cache.service.redis.RedisCacheService;
import me.qwqdev.library.cache.service.redis.RedisCacheServiceInterface;
import org.redisson.config.Config;

/**
 * Factory class for creating instances of {@link RedisCacheService}.
 *
 * @author qwq-dev
 * @since 2024-12-21 12:27
 */
@UtilityClass
public class RedisCacheServiceFactory {
    /**
     * Creates a new instance of {@link RedisCacheService} with a default {@link RedisCacheService}.
     *
     * @param <K> the type of the key
     * @param <V> the type of the value
     * @return a new instance of {@link RedisCacheService}
     */
    public static <K, V> RedisCacheServiceInterface<K, V> create(Config config) {
        return new RedisCacheService<>(config);
    }
}
