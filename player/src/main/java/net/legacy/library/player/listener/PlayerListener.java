package net.legacy.library.player.listener;

import io.fairyproject.bukkit.listener.RegisterAsListener;
import io.fairyproject.container.InjectableComponent;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

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
        LegacyPlayerDataService.LEGACY_PLAYER_DATA_SERVICES.getResource().asMap().forEach((name, service) ->
                L1ToL2PlayerDataSyncTask.of(uniqueId, service).start());
    }

}
