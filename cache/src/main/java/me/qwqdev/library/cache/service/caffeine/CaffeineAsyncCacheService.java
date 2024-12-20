package me.qwqdev.library.cache.service.caffeine;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * Implementation of the {@link CaffeineAsyncCacheServiceInterface} using Caffeine's {@link AsyncCache}.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 * @author qwq-dev
 * @see CaffeineAsyncCacheServiceInterface
 * @see AsyncCache
 * @since 2024-12-20 20:18
 */
@Data
public class CaffeineAsyncCacheService<K, V> implements CaffeineAsyncCacheServiceInterface<K, V> {
    private final AsyncCache<K, V> cache;

    /**
     * Instantiates with a default {@link AsyncCache}.
     *
     * @see Caffeine#buildAsync()
     */
    public CaffeineAsyncCacheService() {
        this.cache = Caffeine.newBuilder().buildAsync();
    }

    /**
     * Instantiates with a specified {@link AsyncCache}.
     *
     * @param cache the {@link AsyncCache} to be used by this service
     */
    public CaffeineAsyncCacheService(AsyncCache<K, V> cache) {
        this.cache = cache;
    }

    /**
     * {@inheritDoc}
     *
     * @param key             {@inheritDoc}
     * @param query           {@inheritDoc}
     * @param cacheAfterQuery {@inheritDoc}
     * @return
     */
    @Override
    public CompletableFuture<V> get(K key, Supplier<V> query, boolean cacheAfterQuery) {
        return get(key, query, cacheAfterQuery, ForkJoinPool.commonPool());
    }

    /**
     * {@inheritDoc}
     *
     * @param key             {@inheritDoc}
     * @param query           {@inheritDoc}
     * @param cacheAfterQuery {@inheritDoc}
     * @param executor        {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<V> get(K key, Supplier<V> query, boolean cacheAfterQuery, Executor executor) {
        CompletableFuture<V> cachedValue = cache.getIfPresent(key);
        return cachedValue != null ? cachedValue : fetchAndCache(key, query, cacheAfterQuery, executor);
    }

    private CompletableFuture<V> fetchAndCache(K key, Supplier<V> query, boolean cacheAfterQuery, Executor executor) {
        CompletableFuture<V> future = CompletableFuture.supplyAsync(query, executor);

        if (cacheAfterQuery) {
            future.thenAccept(value -> {
                if (value != null) {
                    cache.put(key, CompletableFuture.completedFuture(value));
                }
            });
        }

        return future;
    }
}