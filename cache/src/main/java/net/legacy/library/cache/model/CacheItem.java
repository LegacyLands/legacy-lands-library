package net.legacy.library.cache.model;

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
     * <p>If the ttl is set to -1, the item will never expire.
     *
     * @param value              the cached value
     * @param expirationSettings the expiration settings
     * @see ExpirationSettings
     */
    public CacheItem(V value, ExpirationSettings expirationSettings) {
        this.value = value;
        this.expirationTime = expirationSettings.getTimeToLive() <= -1 ? -1 :
                System.currentTimeMillis() + expirationSettings.getTimeUnit().toMillis(expirationSettings.getTimeToLive());
    }

    /**
     * Checks if the cache item has expired.
     *
     * @return true if the item is expired, false otherwise
     */
    public boolean isExpired() {
        if (value == null) {
            return true;
        }

        return expirationTime > -1 && System.currentTimeMillis() > expirationTime;
    }
}