package net.legacy.library.cache.service;

import net.legacy.library.cache.model.LockSettings;

import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * Interface defining basic lockable resource operations.
 *
 * <p>Provides core functionality for resource access and lock-protected operations.
 * This interface can be used for any type of resource that requires thread-safe access
 * through locking mechanisms.
 *
 * @param <R> the resource type
 * @author qwq-dev
 * @see Lock
 * @see LockSettings
 * @since 2024-12-21 19:13
 */
public interface LockableInterface<R> {
    /**
     * Gets the underlying resource.
     *
     * @return the resource
     */
    R getResource();

    /**
     * Executes a function with lock protection.
     *
     * @param getLockFunction the function to obtain the lock
     * @param function        the function to execute under lock protection
     * @param lockSettings    the settings for lock behavior
     * @param <T>             the return type of the function
     * @return the result of the executed function
     */
    <T> T execute(Function<R, Lock> getLockFunction, Function<R, T> function, LockSettings lockSettings);
}