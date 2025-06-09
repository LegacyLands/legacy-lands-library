package net.legacy.library.player.model;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.factory.CacheServiceFactory;
import net.legacy.library.cache.service.CacheServiceInterface;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Easy player raw cache data.
 *
 * @author qwq-dev
 * @since 2025-05-11 17:23
 */
@Getter
@RequiredArgsConstructor
public class EasyPlayerRawCacheData {
    /**
     * The unique identifier for the player.
     */
    private final UUID uuid;

    /**
     * A single-player, in-memory cache that resides on the server and is not persisted to db.
     */
    private final CacheServiceInterface<Cache<String, String>, String> rawCache =
            CacheServiceFactory.createCaffeineCache();

    /**
     * Custom raw cache.
     */
    private final Map<String, CacheServiceInterface<Cache<?, ?>, ?>> customRawCache =
            new ConcurrentHashMap<>();

    /**
     * Creates a new {@link EasyPlayerRawCacheData} instance for the given player.
     *
     * @param player the Bukkit {@link Player} instance
     * @return a new {@link EasyPlayerRawCacheData} instance associated with the player's UUID
     */
    public static EasyPlayerRawCacheData of(Player player) {
        return new EasyPlayerRawCacheData(player.getUniqueId());
    }

    /**
     * Creates a new {@link EasyPlayerRawCacheData} instance for the given UUID string.
     *
     * @param uuid the string representation of the player's UUID
     * @return a new {@link EasyPlayerRawCacheData} instance associated with the given UUID
     * @throws IllegalArgumentException if the UUID string is invalid
     */
    public static EasyPlayerRawCacheData of(String uuid) {
        return new EasyPlayerRawCacheData(UUID.fromString(uuid));
    }

    /**
     * Creates a new {@link EasyPlayerRawCacheData} instance for the given UUID.
     *
     * @param uuid the {@link UUID} of the player
     * @return a new {@link EasyPlayerRawCacheData} instance associated with the given UUID
     */
    public static EasyPlayerRawCacheData of(UUID uuid) {
        return new EasyPlayerRawCacheData(uuid);
    }

    /**
     * Add a custom cache service to the player's custom cache collection.
     *
     * @param key          the unique identifier key for the cache
     * @param cacheService the cache service interface instance to add
     */
    public void addCustomRawCache(String key, CacheServiceInterface<Cache<?, ?>, ?> cacheService) {
        customRawCache.put(key, cacheService);
    }

    /**
     * Get the custom cache service for the specified key.
     *
     * @param key the unique identifier key for the cache
     * @param <T> the cache object type
     * @param <V> the cache value type
     * @return the cache service interface instance for the given key, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T extends Cache<?, ?>, V> CacheServiceInterface<T, V> getCustomRawCache(String key) {
        return (CacheServiceInterface<T, V>) customRawCache.get(key);
    }

    /**
     * Check if a custom cache service exists for the specified key.
     *
     * @param key the unique identifier key for the cache
     * @return true if a cache service exists for the key, false otherwise
     */
    public boolean hasCustomRawCache(String key) {
        return customRawCache.containsKey(key);
    }

    /**
     * Remove the custom cache service for the specified key.
     *
     * @param key the unique identifier key for the cache
     * @param <T> the cache object type
     * @param <V> the cache value type
     * @return the removed cache service interface instance, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T extends Cache<?, ?>, V> CacheServiceInterface<T, V> removeCustomRawCache(String key) {
        return (CacheServiceInterface<T, V>) customRawCache.remove(key);
    }
}
