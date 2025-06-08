package net.legacy.library.cache.service;

import lombok.Data;
import net.legacy.library.cache.model.LockSettings;
import org.redisson.api.RLock;

import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * Abstract base class for lockable resource implementations.
 *
 * <p>This class provides a foundation for implementing thread-safe operations
 * through locking mechanisms, with configurable timeout and retry policies.
 * It can be used for any resource that requires protected access.
 *
 * @param <R> the resource type
 * @author qwq-dev
 * @see LockableInterface
 * @see LockSettings
 * @see Lock
 * @since 2024-12-21 20:03
 */
@Data
public abstract class AbstractLockable<R> implements LockableInterface<R> {
    private final R resource;

    /**
     * Creates a new lockable instance with the specified resource.
     *
     * @param resource the underlying resource to be used
     */
    protected AbstractLockable(R resource) {
        this.resource = resource;
    }

    /**
     * Static factory method to create a new lockable resource instance.
     *
     * @param resource the underlying resource to be wrapped
     * @param <R>      the resource type
     * @return a LockableInterface instance wrapping the specified resource
     */
    public static <R> LockableInterface<R> of(R resource) {
        return new AbstractLockable<>(resource) {
        };
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
    public <T> T execute(Function<R, Lock> getLockFunction,
                         Function<R, T> function,
                         LockSettings lockSettings) {
        Lock lock = getLockFunction.apply(resource);
        String simpleName = lock.getClass().getSimpleName();

        try {
            if (lock.tryLock(lockSettings.getWaitTime(), lockSettings.getTimeUnit())) {
                try {
                    return function.apply(resource);
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
