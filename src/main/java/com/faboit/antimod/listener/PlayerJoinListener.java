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

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                manager.runFullDetection(event.getPlayer());
            }
        }, plugin.getConfigManager().getJoinDelayTicks());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
