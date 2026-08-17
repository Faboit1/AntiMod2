package com.faboit.antimod.listener;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.detection.DetectionManager;
import com.faboit.antimod.util.FoliaScheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerJoinListener implements Listener {

    private final AntiMod plugin;
    private final DetectionManager manager;

    public PlayerJoinListener(AntiMod plugin, DetectionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isOnJoinEnabled()) return;
        if (event.getPlayer().hasPermission("antimod.bypass")) return;

        Runnable probe = () -> {
            if (event.getPlayer().isOnline()) {
                manager.runFullDetection(event.getPlayer());
            }
        };

        int delay = plugin.getConfigManager().getJoinDelayTicks();
        if (delay <= 0) {
            FoliaScheduler.runTask(plugin, event.getPlayer(), probe);
        } else {
            FoliaScheduler.runTaskLater(plugin, event.getPlayer(), probe, delay);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
