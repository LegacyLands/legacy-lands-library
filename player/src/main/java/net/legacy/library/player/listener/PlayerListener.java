package net.legacy.library.player.listener;

import io.fairyproject.bukkit.listener.RegisterAsListener;
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
public class PlayerListener implements Listener {
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uniqueId = event.getPlayer().getUniqueId();

        // Schedule a task to save player data to L2 cache
        PlayerQuitDataSaveTask.of(uniqueId, LockSettings.of(100, 100, TimeUnit.MILLISECONDS)).start();
    }
}
