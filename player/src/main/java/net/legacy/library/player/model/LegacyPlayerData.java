package net.legacy.library.player.model;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Represents player-specific data in the Legacy library.
 * This class allows for managing custom key-value pairs associated with a player.
 *
 * <p>Data is stored in a thread-safe {@link ConcurrentHashMap}, ensuring safe concurrent access.
 * The class provides utility methods for adding, removing, and retrieving data.
 *
 * @author qwq-dev
 * @since 2025-01-03 14:45
 */
@Getter
@RequiredArgsConstructor
public class LegacyPlayerData {

    /**
     * The unique identifier for the player.
     */
    @NonNull
    private final UUID uuid;

    /**
     * A map containing the custom key-value pairs associated with the player.
     */
    private final Map<String, String> data = new ConcurrentHashMap<>();

    /**
     * Creates a new {@link LegacyPlayerData} instance for the given player.
     *
     * @param player the Bukkit {@link Player} instance
     * @return a new {@link LegacyPlayerData} instance associated with the player's UUID
     */
    public static LegacyPlayerData of(Player player) {
        return of(player.getUniqueId());
    }

    /**
     * Creates a new {@link LegacyPlayerData} instance for the given UUID string.
     *
     * @param uuid the string representation of the player's UUID
     * @return a new {@link LegacyPlayerData} instance associated with the given UUID
     * @throws IllegalArgumentException if the UUID string is invalid
     */
    public static LegacyPlayerData of(String uuid) {
        return of(UUID.fromString(uuid));
    }

    /**
     * Creates a new {@link LegacyPlayerData} instance for the given UUID.
     *
     * @param uuid the {@link UUID} of the player
     * @return a new {@link LegacyPlayerData} instance associated with the given UUID
     */
    public static LegacyPlayerData of(UUID uuid) {
        return new LegacyPlayerData(uuid);
    }

    /**
     * Adds a key-value pair to the player's data.
     *
     * @param key   the key for the data
     * @param value the value to associate with the key
     * @return the current instance of {@link LegacyPlayerData} for method chaining
     */
    public LegacyPlayerData addData(String key, String value) {
        data.put(key, value);
        return this;
    }

    /**
     * Removes a key-value pair from the player's data by its key.
     *
     * @param key the key to remove from the data
     * @return the current instance of {@link LegacyPlayerData} for method chaining
     */
    public LegacyPlayerData removeData(String key) {
        data.remove(key);
        return this;
    }

    /**
     * Retrieves the value associated with the specified key.
     *
     * @param key the key whose associated value is to be returned
     * @return the value associated with the key, or {@code null} if the key is not present
     */
    public String getData(String key) {
        return data.get(key);
    }

    /**
     * Retrieves the value associated with the specified key and applies a transformation function to it.
     *
     * @param key      the key whose associated value is to be transformed
     * @param function the function to apply to the value
     * @param <R>      the type of the result produced by the function
     * @return the transformed value, or {@code null} if the key is not present
     */
    public <R> R getData(String key, Function<String, R> function) {
        return function.apply(data.get(key));
    }
}