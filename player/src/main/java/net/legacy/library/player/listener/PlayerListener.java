package net.legacy.library.player.listener;

import io.fairyproject.bukkit.listener.RegisterAsListener;
import io.fairyproject.container.InjectableComponent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;
import net.legacy.library.player.task.redis.RStreamTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * @author qwq-dev
 * @since 2025-01-03 18:48
 */
@RegisterAsListener
@InjectableComponent
public class PlayerListener implements Listener {
    // DEBUG
    @EventHandler
    public void on(AsyncChatEvent event) {
        Optional<LegacyPlayerDataService> legacyPlayerDataService = LegacyPlayerDataService.getLegacyPlayerDataService("player-data-service");
        LegacyPlayerDataService legacyPlayerDataService1 = legacyPlayerDataService.get();
        LegacyPlayerData legacyPlayerData = legacyPlayerDataService1.getLegacyPlayerData(event.getPlayer().getUniqueId());

        RStreamTask rStreamTask =
                RStreamTask.of("player-data-sync-name", "PsycheQwQ", Duration.ofSeconds(5));
        RStreamTask rStreamTask2 =
                RStreamTask.of("player-data-sync-name", "PsycheQwQ2", Duration.ofSeconds(5));

        legacyPlayerDataService1.pubRStreamTask(rStreamTask);
        legacyPlayerDataService1.pubRStreamTask(rStreamTask2);

        System.out.println(legacyPlayerData.getData());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uniqueId = event.getPlayer().getUniqueId();

        // L1 L2 sync
        LegacyPlayerDataService.LEGACY_PLAYER_DATA_SERVICES.getCache().asMap().forEach((name, service) -> L1ToL2PlayerDataSyncTask.of(uniqueId, service).start());
    }
}
