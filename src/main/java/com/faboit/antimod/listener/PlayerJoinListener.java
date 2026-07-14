package com.faboit.antimod.listener;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.detection.DetectionManager;
import org.bukkit.Bukkit;
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

        // 0 (or less) = probe as early as possible, on the very next tick, so the
        // whole sweep runs while the client is still on the loading screen.
        int delay = plugin.getConfigManager().getJoinDelayTicks();
        if (delay <= 0) {
            Bukkit.getScheduler().runTask(plugin, probe);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, probe, delay);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
