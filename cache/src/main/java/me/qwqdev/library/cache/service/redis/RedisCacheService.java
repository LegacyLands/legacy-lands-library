package me.qwqdev.library.cache.service.redis;

import io.fairyproject.log.Log;
import me.qwqdev.library.cache.model.ExpirationSettings;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Redis Cache Service that supports flexible functional programming to operate the cache.
 *
 * @param <K> the type of the key
 * @param <V> the type of the value
 * @author qwq-dev
 * @since 2024-12-21 12:00
 */
public class RedisCacheService<K, V> {
    private final RedissonClient redissonClient;

    /**
     * Initializes the Redis Cache Service with the provided Redisson configuration.
     *
     * @param config the Redisson configuration
     */
    public RedisCacheService(Config config) {
        this.redissonClient = Redisson.create(config);
    }

    /**
     * {@inheritDoc}
     *
     * @param key                {@inheritDoc}
     * @param function           {@inheritDoc}
     * @param query              {@inheritDoc}
     * @param cacheAfterQuery    {@inheritDoc}
     * @param expirationSettings {@inheritDoc}
     * @return
     */
    public V get(K key, Function<RedissonClient, RBucket<V>> function, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings) {
        return executeCacheOperation(function, key, query, cacheAfterQuery, expirationSettings);
    }

    /**
     * {@inheritDoc}
     *
     * @param key                {@inheritDoc}
     * @param function           {@inheritDoc}
     * @param query              {@inheritDoc}
     * @param cacheAfterQuery    {@inheritDoc}
     * @param expirationSettings {@inheritDoc}
     * @return {@inheritDoc}
     */
    public V getWithLock(K key, Function<RedissonClient, RBucket<V>> function, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings) {
        return executeCacheOperationWithLock(function, key, query, cacheAfterQuery, expirationSettings);
    }

    private V executeCacheOperation(Function<RedissonClient, RBucket<V>> function, K key, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings) {
        RBucket<V> rBucket = function.apply(redissonClient);
        return retrieveOrStoreInCache(rBucket, key, query, cacheAfterQuery, expirationSettings);
    }

    private V executeCacheOperationWithLock(Function<RedissonClient, RBucket<V>> function, K key, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings) {
        RLock lock = redissonClient.getLock("lock:" + key);

        try {
            if (lock.tryLock(30, 10, TimeUnit.SECONDS)) {
                return executeCacheOperation(function, key, query, cacheAfterQuery, expirationSettings);
            }
            String msg = "Could not acquire lock for key: " + key;
            Log.error(msg);
            throw new RuntimeException(msg);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            String msg = "Thread interrupted while trying to acquire lock for key: " + key;
            Log.error(msg, exception);
            throw new RuntimeException(msg, exception);
        } finally {
            try {
                lock.unlock();
            } catch (Exception exception) {
                Log.error("Error unlocking lock for key: " + key, exception);
            }
        }
    }

    private V retrieveOrStoreInCache(RBucket<V> rBucket, K key, Supplier<V> query, boolean cacheAfterQuery, ExpirationSettings expirationSettings) {
        V value = rBucket.get();

        if (value != null) {
            return value;
        }

        value = query.get();

        if (cacheAfterQuery) {
            storeInCache(rBucket, value, expirationSettings);
        }

        return value;
    }

    private void storeInCache(RBucket<V> rBucket, V value, ExpirationSettings expirationSettings) {
        if (expirationSettings != null) {
            rBucket.set(value, expirationSettings.toDuration());
        } else {
            rBucket.set(value);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void shutdown() {
        if (redissonClient != null) {
            try {
                redissonClient.shutdown();
            } catch (Exception exception) {
                Log.error("Error shutting down Redisson client", exception);
            }
        }
    }
}
