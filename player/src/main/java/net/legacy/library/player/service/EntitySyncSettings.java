package net.legacy.library.player.service;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;

/**
 * Settings of the cross-server synchronization of a {@link LegacyEntityDataService}.
 *
 * <p>Every setting has a default; build only what differs:
 * {@code EntitySyncSettings.builder().streamRetention(Duration.ofMinutes(30)).build()}.
 *
 * @author qwq-dev
 * @since 2026-10-07 03:30
 */
@Getter
@Builder
public class EntitySyncSettings {

    /**
     * Default expiry of the update a save publishes (5 minutes).
     */
    public static final Duration DEFAULT_UPDATE_EXPIRATION = Duration.ofMinutes(5);

    /**
     * Default retention of messages in the Redis stream (1 hour).
     */
    public static final Duration DEFAULT_STREAM_RETENTION = Duration.ofHours(1);

    /**
     * Default retention of the stamps of removals (24 hours).
     */
    public static final Duration DEFAULT_TOMBSTONE_RETENTION = Duration.ofHours(24);

    /**
     * How long the update a save publishes stays in the stream before it expires. A server that reads the stream
     * later than this misses the update; it still loads the entity from L2 or the database when it first needs it.
     */
    @Builder.Default
    private final Duration updateExpiration = DEFAULT_UPDATE_EXPIRATION;

    /**
     * How long any message is kept in the stream at most, expired or not; bounds messages published without an
     * expiry. Should be longer than {@link #updateExpiration}.
     */
    @Builder.Default
    private final Duration streamRetention = DEFAULT_STREAM_RETENTION;

    /**
     * How long the stamp of a removal is kept. Once it is gone, an older copy that still holds the removed attribute
     * or relationship brings it back when merged, so this must be longer than any server may be offline or any copy
     * may be out of date.
     */
    @Builder.Default
    private final Duration tombstoneRetention = DEFAULT_TOMBSTONE_RETENTION;

    /**
     * Gets the default settings.
     *
     * @return settings with every default
     */
    public static EntitySyncSettings defaults() {
        return builder().build();
    }

}
