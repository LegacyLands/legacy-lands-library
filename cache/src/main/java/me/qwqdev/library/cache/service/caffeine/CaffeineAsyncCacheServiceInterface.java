package me.qwqdev.library.cache.service.caffeine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Interface for an asynchronous cache service using Caffeine.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 * @author qwq-dev
 * @since 2024-12-20 20:18
 */
public interface CaffeineAsyncCacheServiceInterface<K, V> {
    /**
     * Retrieves a value from the cache asynchronously. If the value is not present in the cache,
     * it will be fetched using the provided query supplier and optionally cached.
     *
     * @param key             the key whose associated value is to be returned
     * @param query           a supplier to fetch the value if it is not present in the cache
     * @param cacheAfterQuery if true, the fetched value will be cached
     * @return a CompletableFuture containing the value associated with the specified key
     */
    CompletableFuture<V> get(K key, Supplier<V> query, boolean cacheAfterQuery);

    /**
     * Retrieves a value from the cache asynchronously using the specified executor. If the value
     * is not present in the cache, it will be fetched using the provided query supplier and
     * optionally cached.
     *
     * @param key             the key whose associated value is to be returned
     * @param query           a supplier to fetch the value if it is not present in the cache
     * @param cacheAfterQuery if true, the fetched value will be cached
     * @param executor        the executor to use for asynchronous execution
     * @return a CompletableFuture containing the value associated with the specified key
     */
    CompletableFuture<V> get(K key, Supplier<V> query, boolean cacheAfterQuery, Executor executor);
}