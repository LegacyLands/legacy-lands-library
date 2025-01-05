package net.legacy.library.player.listener;

import io.fairyproject.bukkit.listener.RegisterAsListener;
import io.fairyproject.container.InjectableComponent;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.player.task.PlayerQuitDataSaveTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author qwq-dev
 * @since 2025-01-03 18:48
 */
@RegisterAsListener
@InjectableComponent
public class PlayerListener implements Listener {
    // DEBUG
//    @EventHandler
//    public void on(AsyncChatEvent event) {
//        System.out.println("1");
//        Optional<LegacyPlayerDataService> legacyPlayerDataService = LegacyPlayerDataService.getLegacyPlayerDataService("player-data-service");
//        LegacyPlayerDataService legacyPlayerDataService1 = legacyPlayerDataService.get();
//        LegacyPlayerData legacyPlayerData = legacyPlayerDataService1.getLegacyPlayerData(event.getPlayer().getUniqueId());
//        System.out.println(legacyPlayerData.getData());
//    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uniqueId = event.getPlayer().getUniqueId();

        // Schedule a task to save player data to L2 cache
        PlayerQuitDataSaveTask.of(uniqueId, LockSettings.of(100, 100, TimeUnit.MILLISECONDS)).start();
    }
}
