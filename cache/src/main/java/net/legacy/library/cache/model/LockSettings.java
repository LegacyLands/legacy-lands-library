package net.legacy.library.cache.model;

import lombok.Data;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Encapsulation of the parameters passed in when calling the {@link Lock#tryLock()} method.
 *
 * @author qwq-dev
 * @see Lock
 * @see Lock#tryLock()
 * @since 2024-12-21 17:56
 */
@Data
public class LockSettings {
    private final long waitTime;
    private final long leaseTime;
    private final TimeUnit timeUnit;

    /**
     * Creates an instance of {@link LockSettings}.
     *
     * @param waitTime  the maximum time to wait for the lock
     * @param leaseTime the time to hold the lock after acquiring it
     * @param timeUnit  the time unit for the wait and lease times
     * @return a {@link LockSettings} instance
     */
    public static LockSettings of(long waitTime, long leaseTime, TimeUnit timeUnit) {
        return new LockSettings(waitTime, leaseTime, timeUnit);
    }
}
