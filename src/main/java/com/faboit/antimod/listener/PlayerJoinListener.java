package com.faboit.antimod.listener;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.detection.DetectionManager;
import com.faboit.antimod.detection.PackCheck;
import com.faboit.antimod.util.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerJoinListener implements Listener {

    /**
     * Longest wait for the client to report it has loaded the world. Paper
     * fires that event itself after 3 seconds if the client stays silent, so
     * this only matters on a server that never fires it.
     */
    private static final int WORLD_LOAD_FALLBACK_TICKS = 200;

    private final AntiMod plugin;
    private final DetectionManager manager;
    private final PackCheck packCheck;
    private final boolean waitForWorldLoad;

    /** Players who joined and whose client has not loaded the world yet. */
    private final Set<UUID> awaitingWorld = ConcurrentHashMap.newKeySet();

    public PlayerJoinListener(AntiMod plugin, DetectionManager manager, PackCheck packCheck,
                              boolean waitForWorldLoad) {
        this.plugin = plugin;
        this.manager = manager;
        this.packCheck = packCheck;
        this.waitForWorldLoad = waitForWorldLoad;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isOnJoinEnabled() && !plugin.getConfigManager().isPackCheckOnJoin()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("antimod.bypass")) return;

        if (!waitForWorldLoad) {
            onReady(player);
            return;
        }
        // A client still on the loading screen has no chunks yet: it drops the
        // phantom signs and never answers. Probe once it has loaded the world.
        UUID uuid = player.getUniqueId();
        awaitingWorld.add(uuid);
        FoliaScheduler.runTaskLater(plugin, player, () -> {
            if (awaitingWorld.remove(uuid) && player.isOnline()) onReady(player);
        }, WORLD_LOAD_FALLBACK_TICKS);
    }

    /** Called by {@link WorldLoadedListener} once the client has loaded the world. */
    void onClientLoadedWorld(Player player) {
        // Only the first load after joining; respawns and world changes fire it again.
        if (awaitingWorld.remove(player.getUniqueId())) onReady(player);
    }

    /** The client is in the world: start whatever runs on join. */
    private void onReady(Player player) {
        // The manager decides when: after join-delay-ticks, or - with the
        // spoof check on - once the client has announced its plugin channels.
        if (plugin.getConfigManager().isOnJoinEnabled()) manager.scheduleJoinProbe(player);
        if (plugin.getConfigManager().isPackCheckOnJoin()) packCheck.scheduleOnJoin(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingWorld.remove(event.getPlayer().getUniqueId());
        manager.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
