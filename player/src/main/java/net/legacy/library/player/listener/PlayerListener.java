package net.legacy.library.player.listener;

import io.fairyproject.bukkit.listener.RegisterAsListener;
import io.fairyproject.container.InjectableComponent;
import net.legacy.library.player.PlayerLauncher;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;
import net.legacy.library.player.task.redis.RStreamTask;
import net.legacy.library.player.task.redis.impl.L1ToL2PlayerDataSyncByNameRStreamAccepter;
import net.legacy.library.player.task.redis.impl.PlayerDataUpdateByNameRStreamAccepter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Listener class to handle player-related events such quit events.
 *
 * @author qwq-dev
 * @since 2025-01-03 18:48
 */
@RegisterAsListener
@InjectableComponent
public class PlayerListener implements Listener {
    // DEBUG
    @EventHandler
    public void on(AsyncPlayerChatEvent event) {
        if (!PlayerLauncher.DEBUG) {
            return;
        }

        Optional<LegacyPlayerDataService> legacyPlayerDataService = LegacyPlayerDataService.getLegacyPlayerDataService("player-data-service");
        LegacyPlayerDataService legacyPlayerDataService1 = legacyPlayerDataService.get();
        LegacyPlayerData legacyPlayerData = legacyPlayerDataService1.getLegacyPlayerData(event.getPlayer().getUniqueId());

        // test player data L1 L2 sync by rstream
        RStreamTask rStreamTask1 = L1ToL2PlayerDataSyncByNameRStreamAccepter.createRStreamTask(
                "PsycheQwQ", Duration.ofSeconds(5)
        );
        RStreamTask rStreamTask2 = L1ToL2PlayerDataSyncByNameRStreamAccepter.createRStreamTask(
                "PsycheQwQ2", Duration.ofSeconds(5)
        );
        legacyPlayerDataService1.pubRStreamTask(rStreamTask1);
        legacyPlayerDataService1.pubRStreamTask(rStreamTask2);


        // test player data update by rstream
        Map<String, String> testData = new HashMap<>();
        testData.put("time", String.valueOf(System.currentTimeMillis()));
        RStreamTask rStreamTask3 = PlayerDataUpdateByNameRStreamAccepter.createRStreamTask(
                "PsycheQwQ", testData, Duration.ofSeconds(5)
        );
        legacyPlayerDataService1.pubRStreamTask(rStreamTask3);


        // the mission has just been released
        // and the player data should not have been updated yet
        System.out.println(legacyPlayerData.getData());

        // delay 1s
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // try again
        System.out.println(legacyPlayerData.getData());
    }

    /**
     * Handles player quit events to initiate synchronization tasks for player data.
     *
     * <p>When a player quits, this method retrieves all cached
     * {@link LegacyPlayerDataService} instances and starts synchronization tasks
     * to ensure that player data is persisted appropriately.
     *
     * @param event the player quit event triggered when a player leaves the server
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uniqueId = event.getPlayer().getUniqueId();

        // L1 L2 sync
        LegacyPlayerDataService.LEGACY_PLAYER_DATA_SERVICES.getCache().asMap().forEach((name, service) -> L1ToL2PlayerDataSyncTask.of(uniqueId, service).start());
    }
}
