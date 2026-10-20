package com.faboit.antimod;

import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.command.AntiModCommand;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.detection.ChannelTracker;
import com.faboit.antimod.detection.DetectionManager;
import com.faboit.antimod.detection.SignResponseListener;
import com.faboit.antimod.listener.PlayerJoinListener;
import com.faboit.antimod.util.FoliaScheduler;
import com.faboit.antimod.violation.ViolationManager;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AntiMod extends JavaPlugin {

    private ConfigManager configManager;
    private ViolationManager violationManager;
    private DetectionManager detectionManager;

    @Override
    public void onLoad() {
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            getLogger().severe("PacketEvents is not installed. AntiMod cannot function without it.");
            return;
        }
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        if (PacketEvents.getAPI() == null) {
            getLogger().severe("PacketEvents is not installed. AntiMod cannot function without it.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PacketEvents.getAPI().init();

        // Configuration
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        // Violation storage
        this.violationManager = new ViolationManager(this);
        this.violationManager.load();

        // Action executor and detection pipeline
        ActionExecutor actionExecutor = new ActionExecutor(this, violationManager);
        ChannelTracker channelTracker = new ChannelTracker(this);
        this.detectionManager = new DetectionManager(this, actionExecutor, channelTracker);

        // PacketEvents listeners: channel registrations and UPDATE_SIGN responses
        channelTracker.register();
        new SignResponseListener(this, detectionManager).register();

        // Event listener for join/quit
        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this, detectionManager), this);

        // Command
        PluginCommand cmd = getCommand("antimod");
        if (cmd != null) {
            AntiModCommand executor = new AntiModCommand(this, detectionManager, violationManager);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // Periodic detection
        if (configManager.isPeriodicEnabled()) {
            long ticks = configManager.getPeriodicIntervalSeconds() * 20L;
            FoliaScheduler.runGlobalTaskTimer(this, () -> {
                for (var player : Bukkit.getOnlinePlayers()) {
                    if (!player.hasPermission("antimod.bypass")) {
                        detectionManager.runFullDetection(player);
                    }
                }
            }, ticks, ticks);
        }

        String foliaStatus = FoliaScheduler.isFoliaAvailable() ? " (Folia-compatible)" : "";
        String spoofStatus = configManager.isSpoofEnabled()
                ? " Spoof check: " + configManager.getSpoofMods().size() + " channel-confirmed mods."
                : "";
        getLogger().info("AntiMod enabled. Monitoring " + configManager.getMods().size() + " mods."
                + spoofStatus + foliaStatus);
    }

    @Override
    public void onDisable() {
        if (violationManager != null) violationManager.save();
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        getLogger().info("AntiMod disabled.");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public DetectionManager getDetectionManager() {
        return detectionManager;
    }
}
