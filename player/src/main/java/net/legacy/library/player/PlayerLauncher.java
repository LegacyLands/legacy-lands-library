package net.legacy.library.player;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;

/**
 * The type Player launcher.
 *
 * @author qwq-dev
 * @since 2025-1-3 14:12
 */
@FairyLaunch
@InjectableComponent
public class PlayerLauncher extends Plugin {
    @Override
    public void onPluginEnable() {
        // TODO: try get leader from redis
    }

    @Override
    public void onPluginDisable() {
        // TODO: if leader, then save redis data to mongodb
    }
}
