package com.faboit.antimod;

import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.command.AntiModCommand;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.detection.DetectionManager;
import com.faboit.antimod.detection.SignResponseListener;
import com.faboit.antimod.listener.PlayerJoinListener;
import com.faboit.antimod.violation.ViolationManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AntiMod extends JavaPlugin {

    private ConfigManager configManager;
    private ViolationManager violationManager;
    private DetectionManager detectionManager;

    @Override
    public void onEnable() {
        // Configuration
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        // Violation storage
        this.violationManager = new ViolationManager(this);
        this.violationManager.load();

        // Action executor and detection pipeline
        ActionExecutor actionExecutor = new ActionExecutor(this, violationManager);
        this.detectionManager = new DetectionManager(this, actionExecutor);

        // ProtocolLib packet listener for UPDATE_SIGN responses
        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib is not installed. AntiMod cannot function without it.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
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
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (var player : Bukkit.getOnlinePlayers()) {
                    if (!player.hasPermission("antimod.bypass")) {
                        detectionManager.runFullDetection(player);
                    }
                }
            }, ticks, ticks);
        }

        getLogger().info("AntiMod enabled. Monitoring " + configManager.getMods().size() + " mods.");
    }

    @Override
    public void onDisable() {
        if (violationManager != null) violationManager.save();
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
