package com.faboit.antimod;

import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.command.AntiModCommand;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.detection.ChannelTracker;
import com.faboit.antimod.detection.DetectionManager;
import com.faboit.antimod.detection.PackCheck;
import com.faboit.antimod.detection.SignResponseListener;
import com.faboit.antimod.listener.PlayerJoinListener;
import com.faboit.antimod.listener.WorldLoadedListener;
import com.faboit.antimod.util.FoliaScheduler;
import com.faboit.antimod.violation.ViolationManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class AntiMod extends JavaPlugin {

    private ConfigManager configManager;
    private ViolationManager violationManager;
    private DetectionManager detectionManager;
    private PackCheck packCheck;
    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();
    /** The instance AntiMod's listeners were registered on. */
    private PacketEventsAPI<?> packetEvents;
    /** True only when AntiMod had to start its own PacketEvents instance (see onEnable). */
    private boolean ownsPacketEvents;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            getLogger().severe("PacketEvents is not installed. AntiMod cannot function without it.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // The PacketEvents plugin owns the shared API instance that every
        // PacketEvents-based plugin registers on, so AntiMod only uses it and
        // never shuts it down. The one exception: if it has already been shut
        // down (an older AntiMod hot-unloaded with PlugMan terminates it on its
        // way out), start a private instance so AntiMod still works.
        PacketEventsAPI<?> shared = PacketEvents.getAPI();
        if (shared == null || shared.isTerminated()) {
            getLogger().warning("The shared PacketEvents instance has been shut down (an older AntiMod unloaded "
                    + "at runtime does this). Starting AntiMod's own; other PacketEvents plugins need a server "
                    + "restart to recover.");
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            PacketEvents.getAPI().load();
            PacketEvents.getAPI().init();
            ownsPacketEvents = true;
        } else if (!shared.isInitialized()) {
            shared.init();
        }
        packetEvents = PacketEvents.getAPI();

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
        SignResponseListener signListener = new SignResponseListener(this, detectionManager);
        channelTracker.register();
        signListener.register();
        packetListeners.add(channelTracker);
        packetListeners.add(signListener);

        // Event listener for join/quit. On Paper 1.21.4+ the join sweep waits
        // until the client reports it has loaded the world.
        boolean worldLoadEvent = WorldLoadedListener.isSupported();
        PlayerJoinListener joinListener = new PlayerJoinListener(this, detectionManager, worldLoadEvent);
        getServer().getPluginManager().registerEvents(joinListener, this);
        if (worldLoadEvent) {
            getServer().getPluginManager().registerEvents(new WorldLoadedListener(joinListener), this);
        }

        // On-demand resource-pack check (/antimod check <player> --pack)
        this.packCheck = new PackCheck(this, actionExecutor);
        packCheck.start();
        getServer().getPluginManager().registerEvents(packCheck, this);

        // Command
        PluginCommand cmd = getCommand("antimod");
        if (cmd != null) {
            AntiModCommand executor = new AntiModCommand(this, detectionManager, violationManager, packCheck);
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
        if (packCheck != null) packCheck.stop();
        if (violationManager != null) violationManager.save();
        if (packetEvents != null) {
            // Remove only AntiMod's own listeners: terminating the shared
            // instance would cut packet handling for every other
            // PacketEvents plugin until a restart.
            for (PacketListenerCommon listener : packetListeners) {
                packetEvents.getEventManager().unregisterListener(listener);
            }
            if (ownsPacketEvents) packetEvents.terminate();
        }
        packetListeners.clear();
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
