package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import net.legacy.library.player.task.redis.RStreamTask;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.util.Map;

/**
 * An {@link RStreamAccepterInterface} implementation that updates player data by <b>player name</b>.
 *
 * <p>The action name for tasks recognized by this class is {@code "player-data-update-name"}.
 * Once a task is received, this accepter attempts to find the player (by name) and, if they are online,
 * updates their data in L1 cache.
 *
 * <p>Classes annotated with {@link RStreamAccepterRegister} are automatically
 * discovered and registered for handling Redis stream tasks.
 *
 * <p>If the player is offline, no update operation is performed.
 * This ensures that online players always receive timely updates.
 *
 * @author qwq-dev
 * @since 2025-01-05 12:37
 */
@RStreamAccepterRegister
public class PlayerDataUpdateByNameRStreamAccepter implements RStreamAccepterInterface {
    /**
     * Creates a new {@link RStreamTask} for updating player data based on the player's name.
     *
     * @param name           the name of the player
     * @param playerData     the map of data to be updated
     * @param expirationTime the duration after which the task expires
     * @return a {@link RStreamTask} instance for updating data by player name
     */
    public static RStreamTask createRStreamTask(String name, Map<String, String> playerData, Duration expirationTime) {
        return RStreamTask.of("player-data-update-name", GsonUtil.getGson().toJson(Pair.of(name, playerData)), expirationTime);
    }

    /**
     * {@inheritDoc}
     *
     * @return the action name associated with this accepter, which is {@code "player-data-update-name"}
     */
    @Override
    public String getActionName() {
        return "player-data-update-name";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}, indicating that this accepter processes each task only once per connection
     */
    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method deserializes the incoming JSON (a pair of player name and data map),
     * checks if the player is online, and if so, updates their {@link net.legacy.library.player.model.LegacyPlayerData}
     * in L1 cache. The stream message is then acknowledged and removed if the update is successful.
     */
    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                       LegacyPlayerDataService legacyPlayerDataService, String data) {
        Pair<String, Map<String, String>> pairData =
                GsonUtil.getGson().fromJson(data, new TypeToken<Pair<String, Map<String, String>>>() {
                }.getType());

        String playerName = pairData.getLeft();
        Map<String, String> dataMap = pairData.getRight();

        Player player = Bukkit.getPlayer(playerName);

        if (player != null && player.isOnline()) {
            legacyPlayerDataService.getLegacyPlayerData(player.getUniqueId()).addData(dataMap);
            ack(rStream, streamMessageId);
        }
    }
}