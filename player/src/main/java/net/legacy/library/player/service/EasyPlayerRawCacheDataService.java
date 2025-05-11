package net.legacy.library.player.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.Sets;
import lombok.Getter;
import net.legacy.library.cache.factory.CacheServiceFactory;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.player.model.EasyPlayerRawCacheData;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for managing {@link EasyPlayerRawCacheData} instances.
 *
 * <p>This service provides a way to manage non-persistent player data through an in-memory cache.
 * It handles the creation, retrieval, and management of player raw cache data objects that
 * can be used for temporary storage during a player's session.
 *
 * <p>Each {@code EasyPlayerRawCacheDataService} is identified by a unique name, allowing multiple
 * services to be created for different purposes or components. The service tracks all player
 * data instances in a thread-safe concurrent set.
 *
 * <p>The service itself is registered in a global static cache, allowing it to be retrieved
 * by name from anywhere in the application.
 *
 * @author qwq-dev
 * @since 2025-05-11 17:26
 */
@Getter
public class EasyPlayerRawCacheDataService {
    /**
     * Cache service for managing {@link EasyPlayerRawCacheDataService} instances.
     * Keyed by service name.
     */
    public static final CacheServiceInterface<Cache<String, EasyPlayerRawCacheDataService>, EasyPlayerRawCacheDataService>
            EASY_PLAYER_RAW_CACHE_DATA_SERVICES = CacheServiceFactory.createCaffeineCache();

    private final String name;
    private final Set<EasyPlayerRawCacheData> easyPlayerRawCacheDataSets;

    /**
     * Constructs a new {@link EasyPlayerRawCacheDataService}.
     *
     * @param name the unique name of the service
     * @throws IllegalStateException if a service with the same name already exists
     */
    public EasyPlayerRawCacheDataService(String name) {
        this.name = name;
        this.easyPlayerRawCacheDataSets = Sets.newConcurrentHashSet();

        // Record all EasyPlayerRawCacheDataService
        Cache<String, EasyPlayerRawCacheDataService> cache = EASY_PLAYER_RAW_CACHE_DATA_SERVICES.getResource();

        if (cache.getIfPresent(name) != null) {
            throw new IllegalStateException("EasyPlayerRawCacheDataService with name " + name + " already exists");
        }

        cache.put(name, this);
    }

    /**
     * Creates a new {@link EasyPlayerRawCacheDataService} with the specified name.
     *
     * @param name the unique name of the service
     * @return a new instance of {@link EasyPlayerRawCacheDataService}
     * @throws IllegalStateException if a service with the same name already exists
     */
    public static EasyPlayerRawCacheDataService of(String name) {
        return new EasyPlayerRawCacheDataService(name);
    }

    /**
     * Retrieves the {@link EasyPlayerRawCacheData} for the specified UUID.
     *
     * @param uuid              the unique identifier of the player
     * @param createIfNotExists whether to create a new instance if one doesn't exist
     * @return an {@link Optional} containing the {@link EasyPlayerRawCacheData} if found or created, 
     *         or empty if not found and creation was not requested
     */
    public Optional<EasyPlayerRawCacheData> get(UUID uuid, boolean createIfNotExists) {
        EasyPlayerRawCacheData data = easyPlayerRawCacheDataSets.stream()
                .filter(easyPlayerRawCacheData -> easyPlayerRawCacheData.getUuid().equals(uuid))
                .findFirst().orElse(null);

        if (data == null) {
            if (createIfNotExists) {
                EasyPlayerRawCacheData easyPlayerRawCacheData = EasyPlayerRawCacheData.of(uuid);
                easyPlayerRawCacheDataSets.add(easyPlayerRawCacheData);
                return Optional.of(easyPlayerRawCacheData);
            } else {
                return Optional.empty();
            }
        }

        return Optional.of(data);
    }
}