package net.legacy.library.player.listener;

import io.fairyproject.bukkit.listener.RegisterAsListener;
import io.fairyproject.container.InjectableComponent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.L1ToL2DataSyncTask;
import org.apache.commons.lang3.tuple.Pair;
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
        System.out.println("1");
        Optional<LegacyPlayerDataService> legacyPlayerDataService = LegacyPlayerDataService.getLegacyPlayerDataService("player-data-service");
        LegacyPlayerDataService legacyPlayerDataService1 = legacyPlayerDataService.get();
        LegacyPlayerData legacyPlayerData = legacyPlayerDataService1.getLegacyPlayerData(event.getPlayer().getUniqueId());

        Pair<String, String> pair = Pair.of("player-data-sync-name", "PsycheQwQ");
        Pair<String, String> pair2 = Pair.of("player-data-sync-name", "PsycheQwQ2");

        legacyPlayerDataService1.redisStreamPubTask(pair, Duration.ofSeconds(60));
        legacyPlayerDataService1.redisStreamPubTask(pair2, Duration.ofSeconds(65));

        System.out.println(legacyPlayerData.getData());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uniqueId = event.getPlayer().getUniqueId();

        // L1 L2 sync
        LegacyPlayerDataService.LEGACY_PLAYER_DATA_SERVICES.getCache().asMap().forEach((name, service) -> L1ToL2DataSyncTask.of(uniqueId, service).start());
    }
}
