package me.qwqdev.library.cache.model;

import lombok.Data;

/**
 * Represents a cached item, which includes a value and an expiration time.
 * Expired items will not be returned by the cache.
 *
 * @param <V> the type of the cached value
 * @author qwq-dev
 * @since 2024-12-20 14:06
 */
@Data
public class CacheItem<V> {
    private final V value;
    private final long expirationTime;

    /**
     * Constructs a CacheItem with a specified value and expiration settings.
     *
     * @param value              the cached value
     * @param expirationSettings the expiration settings
     * @see ExpirationSettings
     */
    public CacheItem(V value, ExpirationSettings expirationSettings) {
        this.value = value;
        this.expirationTime = System.currentTimeMillis() + expirationSettings.getTimeUnit().toMillis(expirationSettings.getTimeToLive());
    }

    /**
     * Checks if the cache item has expired.
     *
     * @return true if the item is expired, false otherwise
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expirationTime;
    }
}