package net.legacy.library.player.listener;

import io.fairyproject.bukkit.listener.RegisterAsListener;
import net.legacy.library.player.task.PlayerQuitTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * @author qwq-dev
 * @since 2025-01-03 18:48
 */
@RegisterAsListener
public class PlayerListener implements Listener {
    @EventHandler
    public void onPlayerQuit(PlayerQuitTask event) {
        // TODO:
    }
}
