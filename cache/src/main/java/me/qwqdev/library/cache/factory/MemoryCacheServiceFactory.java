package me.qwqdev.library.cache.factory;

import lombok.experimental.UtilityClass;
import me.qwqdev.library.cache.model.CacheItem;
import me.qwqdev.library.cache.service.memory.MemoryCacheService;
import me.qwqdev.library.cache.service.memory.MemoryCacheServiceInterface;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory class for creating instances of {@link MemoryCacheService}.
 *
 * @author qwq-dev
 * @since 2024-12-20 20:39
 */
@UtilityClass
public class MemoryCacheServiceFactory {
    /**
     * Creates a new instance of {@link MemoryCacheService} with a default {@link ConcurrentHashMap}.
     *
     * @param <K> the type of keys maintained by the cache
     * @param <V> the type of mapped values
     * @return a new instance of {@link MemoryCacheService}
     */
    public static <K, V> MemoryCacheServiceInterface<K, V> create() {
        return new MemoryCacheService<>();
    }

    /**
     * Creates a new instance of {@link MemoryCacheService} with the specified {@link Map}.
     *
     * @param cache the {@link Map} to be used by the service
     * @param <K>   the type of keys maintained by the cache
     * @param <V>   the type of mapped values
     * @return a new instance of {@link MemoryCacheService}
     */
    public static <K, V> MemoryCacheServiceInterface<K, V> create(Map<K, CacheItem<V>> cache) {
        return new MemoryCacheService<>(cache);
    }
}