package net.legacy.library.cache.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Expiration settings, including time to live and the time unit.
 * This class is used to define how long a cache item should be retained before it expires.
 *
 * @author qwq-dev
 * @since 2024-12-20 14:28
 */
@Data
@AllArgsConstructor
public class ExpirationSettings {
    private final long timeToLive;
    private final TimeUnit timeUnit;

    /**
     * Creates an instance of ExpirationSettings.
     *
     * @param timeToLive the time to live for the cache item
     * @param timeUnit   the time unit for the expiration
     * @return an ExpirationSettings instance
     * @see TimeUnit
     */
    public static ExpirationSettings of(long timeToLive, TimeUnit timeUnit) {
        return new ExpirationSettings(timeToLive, timeUnit);
    }

    /**
     * Converts the ExpirationSettings to a {@link Duration}.
     *
     * @return a {@link Duration} object representing the expiration time
     * @see Duration
     */
    public Duration toDuration() {
        return Duration.ofMillis(timeUnit.toMillis(timeToLive));
    }
}