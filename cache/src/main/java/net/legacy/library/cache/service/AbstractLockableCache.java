package net.legacy.library.cache.service;

import lombok.Data;
import net.legacy.library.cache.model.LockSettings;
import org.redisson.api.RLock;

import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * Abstract base class for lockable cache implementations.
 *
 * <p>This class provides a foundation for implementing thread-safe cache operations
 * through locking mechanisms, with configurable timeout and retry policies.
 *
 * @param <C> the cache implementation type
 * @author qwq-dev
 * @see LockableCacheInterface
 * @see LockSettings
 * @see Lock
 * @since 2024-12-21 20:03
 */
@Data
public abstract class AbstractLockableCache<C> implements LockableCacheInterface<C> {
    private final C cache;

    /**
     * Creates a new lockable cache instance with the specified cache implementation.
     *
     * @param cache the underlying cache implementation to be used
     */
    protected AbstractLockableCache(C cache) {
        this.cache = cache;
    }

    /**
     * Executes a function with lock protection.
     * This method handles the acquisition and release of locks, ensuring thread-safe operations.
     *
     * @param getLockFunction the function to obtain the lock for thread-safe operations
     * @param function        the function to execute under lock protection
     * @param lockSettings    the settings controlling lock behavior including timeout and retry policies
     * @param <T>             the return type of the executed function
     * @return the result of the executed function
     * @throws RuntimeException if lock acquisition fails or the thread is interrupted
     * @see Lock
     * @see LockSettings
     */
    @Override
    public <T> T execute(Function<C, Lock> getLockFunction,
                         Function<C, T> function,
                         LockSettings lockSettings) {
        Lock lock = getLockFunction.apply(cache);
        String simpleName = lock.getClass().getSimpleName();

        try {
            if (lock.tryLock(lockSettings.getWaitTime(), lockSettings.getTimeUnit())) {
                try {
                    return function.apply(cache);
                } finally {
                    // Making RLock more secure
                    if (lock instanceof RLock rLock && rLock.isHeldByCurrentThread()) {
                        rLock.unlock();
                    } else {
                        lock.unlock();
                    }
                }
            } else {
                throw new RuntimeException("Could not acquire lock within the specified time: " + simpleName);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted while trying to acquire lock: " + simpleName, exception);
        }
    }
}
