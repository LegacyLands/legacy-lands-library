package net.legacy.library.cache.service.multi;

import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.AbstractLockable;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A flexible multi-level cache service that manages multiple {@link TieredCacheLevel} instances.
 *
 * @author qwq-dev
 * @since 2024-12-27 19:15
 */
public class FlexibleMultiLevelCacheService extends AbstractLockable<Set<TieredCacheLevel<?, ?>>> {

    /**
     * A set of tiered cache levels that this service manages.
     */
    private final Set<TieredCacheLevel<?, ?>> tieredCacheLevels;

    /**
     * Constructs a new flexible multi-level cache service.
     *
     * @param tieredCacheLevels a set of {@link TieredCacheLevel} instances
     */
    public FlexibleMultiLevelCacheService(Set<TieredCacheLevel<?, ?>> tieredCacheLevels) {
        super(tieredCacheLevels);
        this.tieredCacheLevels = tieredCacheLevels;
    }

    /**
     * Retrieves the cache level object (wrapped in an {@link Optional}) by the given level identifier.
     *
     * @param level the identifier of the level to look up
     * @param <L>   the type of the level identifier
     * @return an Optional containing the {@link TieredCacheLevel} if found; otherwise empty
     */
    public <L> Optional<TieredCacheLevel<?, ?>> getCacheLevel(L level) {
        return tieredCacheLevels.stream()
                .filter(tieredLevel -> tieredLevel.getLevel().equals(level))
                .findFirst();
    }

    /**
     * Retrieves the cache level object by the given level identifier, or throws an exception if not found.
     *
     * <p>This method attempts to retrieve a {@link TieredCacheLevel} corresponding to the given {@code level}.
     * If the cache level is not found (i.e., the {@link Optional} is empty), it throws an exception
     * provided by the {@code exceptionSupplier}.
     *
     * @param level             the identifier of the level to look up
     * @param exceptionSupplier a {@link Supplier} that provides the exception to be thrown if the cache level is not found
     * @param <L>               the type of the level identifier
     * @param <X>               the type of the exception that may be thrown
     * @return the {@link TieredCacheLevel} associated with the given level identifier
     * @throws X if the cache level is not found, the exception provided by {@code exceptionSupplier} is thrown
     */
    public <L, X extends Throwable> TieredCacheLevel<?, ?> getCacheLevelElseThrow(L level, Supplier<? extends X> exceptionSupplier) throws X {
        return getCacheLevel(level).orElseThrow(exceptionSupplier);
    }

    /**
     * Applies a function to the underlying cache of a specific level (without lock).
     *
     * <p>This method looks up the {@link TieredCacheLevel} by the provided level identifier, and if found,
     * applies the given function to the cache.
     *
     * @param level        the identifier of the cache level
     * @param cacheMapping a function describing how to interact with the underlying cache
     * @param <L>          the type of the level identifier
     * @param <C>          the concrete cache implementation type
     * @param <R>          the return type of the function
     * @return the result of the function application, or {@code null} if the level is not found
     */
    @SuppressWarnings("unchecked")
    public <L, C, R> R applyFunctionWithoutLock(L level, Function<C, R> cacheMapping) {
        return getCacheLevel(level)
                // TieredCacheLevel<?, ?> -> TieredCacheLevel<L, C>
                .map(tieredCacheLevel -> cacheMapping.apply((C) tieredCacheLevel.getCache()))
                .orElse(null);
    }

    /**
     * Applies a function to the underlying cache of a specific level (with lock).
     *
     * <p>This method obtains the lock from the targeted cache (using the provided function),
     * then executes the desired operation via {@link #execute(Function, Function, LockSettings)}
     * of the parent class {@link AbstractLockable}.
     *
     * @param level           the identifier of the cache level
     * @param getLockFunction a function to obtain a {@link Lock} from the specific cache
     * @param lockSettings    settings to control lock acquisition timeout, retries, etc.
     * @param cacheMapping    the main function to be executed under lock
     * @param <L>             the type of the level identifier
     * @param <C>             the concrete cache implementation type
     * @param <R>             the return type of the function
     * @return the result of the function application, or {@code null} if the level is not found
     * @throws RuntimeException if unable to acquire the lock or if the thread is interrupted
     */
    public <L, C, R> R applyFunctionWithLock(
            L level,
            Function<C, Lock> getLockFunction,
            LockSettings lockSettings,
            Function<C, R> cacheMapping
    ) {
        return getCacheLevel(level)
                .map(tier -> {
                    @SuppressWarnings("unchecked")
                    C cache = (C) tier.getCache();
                    return execute(
                            (ignore) -> getLockFunction.apply(cache),
                            (ignore) -> cacheMapping.apply(cache),
                            lockSettings
                    );
                })
                .orElse(null);
    }

}
