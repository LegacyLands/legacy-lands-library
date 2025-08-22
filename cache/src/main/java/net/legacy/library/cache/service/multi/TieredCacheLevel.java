package net.legacy.library.cache.service.multi;

import lombok.Data;

/**
 * Represents a single cache tier/level along with its underlying cache implementation.
 *
 * @param <L> the type representing the level identifier (e.g., an enum or string)
 * @param <C> the underlying cache implementation type (e.g., Map, Redis client, etc.)
 * @author qwq-dev
 * @since 2024-12-27 19:15
 */
@Data
public class TieredCacheLevel<L, C> {

    private final L level;
    private final C cache;

    /**
     * Creates a new tiered cache level instance.
     *
     * @param level the level identifier
     * @param cache the underlying cache implementation
     */
    public TieredCacheLevel(L level, C cache) {
        this.level = level;
        this.cache = cache;
    }

    /**
     * Static factory method for creating a {@link TieredCacheLevel}.
     *
     * @param level the level identifier
     * @param cache the underlying cache
     * @param <L>   the type of level
     * @param <C>   the type of cache
     * @return a new instance of {@link TieredCacheLevel}
     */
    public static <L, C> TieredCacheLevel<L, C> of(L level, C cache) {
        return new TieredCacheLevel<>(level, cache);
    }

    /**
     * Gets the level identifier with a specific type.
     *
     * @param <R> the desired return type
     * @return the level identifier of type {@code R}
     */
    @SuppressWarnings("unchecked")
    public <R> R getLevelWithType() {
        return (R) level;
    }

    /**
     * Gets the underlying cache implementation with a specific type.
     *
     * @param <R> the desired return type
     * @return the cache implementation of type {@code R}
     */
    @SuppressWarnings("unchecked")
    public <R> R getCacheWithType() {
        return (R) cache;
    }

}
