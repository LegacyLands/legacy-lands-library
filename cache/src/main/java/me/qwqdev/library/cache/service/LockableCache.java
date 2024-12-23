package me.qwqdev.library.cache.service;

import me.qwqdev.library.cache.model.LockSettings;

import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * Interface defining basic lockable cache operations.
 *
 * <p>Provides core functionality for cache access and lock-protected operations.
 *
 * @param <C> the cache implementation type
 * @author qwq-dev
 * @see Lock
 * @see LockSettings
 * @since 2024-12-21 19:13
 */
public interface LockableCache<C> {
    /**
     * Gets the underlying cache implementation.
     *
     * @return the cache implementation
     */
    C getCache();

    /**
     * Executes a function with lock protection.
     *
     * @param getLockFunction the function to obtain the lock
     * @param function        the function to execute under lock protection
     * @param lockSettings    the settings for lock behavior
     * @param <T>             the return type of the function
     * @return the result of the executed function
     */
    <T> T execute(Function<C, Lock> getLockFunction, Function<C, T> function, LockSettings lockSettings);
}
