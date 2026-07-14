package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;
import com.faboit.antimod.AntiMod;
import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.config.ModConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the detection lifecycle:
 *   - deciding what to probe
 *   - packing keys into signs (up to 4 keys / lines per sign)
 *   - sending each probe via SignPacketSender
 *   - accepting responses from SignResponseListener
 *   - handing detections off to ActionExecutor
 */
public final class DetectionManager {

    private final AntiMod plugin;
    private final SignPacketSender packetSender;
    private final ActionExecutor actionExecutor;

    /** One live session per player; each session covers one sign (up to 4 keys). */
    private final Map<UUID, DetectionSession> activeSessions = new ConcurrentHashMap<>();

    /** Queued follow-up probes (for players with more mods to check than fit in one sign). */
    private final Map<UUID, List<PendingProbe>> queuedProbes = new ConcurrentHashMap<>();

    public DetectionManager(AntiMod plugin, ActionExecutor actionExecutor) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.packetSender = new SignPacketSender(plugin);
    }

    /**
     * Kicks off a full detection cycle for the given player: enumerates every
     * enabled mod, packs their keys into 4-line signs, and dispatches them.
     */
    public void runFullDetection(Player player) {
        if (player == null || !player.isOnline()) return;
        if (player.hasPermission("antimod.bypass")) return;

        ConfigManager cfg = plugin.getConfigManager();
        Collection<ModConfig> mods = cfg.getMods().values();

        // Build a flat list of (modId, key) pairs from every enabled mod.
        List<PendingProbe> probes = new ArrayList<>();
        for (ModConfig mod : mods) {
            if (!mod.isEnabled()) continue;
            for (String key : mod.getTranslationKeys()) {
                probes.add(new PendingProbe(mod.getId(), key));
            }
        }
        if (probes.isEmpty()) return;

        int keysPerSign = cfg.getKeysPerSign();

        // Break into batches of `keysPerSign`.
        UUID uuid = player.getUniqueId();
        List<PendingProbe> queue = new ArrayList<>();
        for (int i = keysPerSign; i < probes.size(); i++) {
            queue.add(probes.get(i));
        }
        if (!queue.isEmpty()) {
            queuedProbes.put(uuid, queue);
        } else {
            queuedProbes.remove(uuid);
        }

        List<PendingProbe> firstBatch = probes.subList(0, Math.min(keysPerSign, probes.size()));
        dispatchBatch(player, firstBatch);
    }

    /**
     * Sends a single sign carrying up to `keysPerSign` probes.
     */
    private void dispatchBatch(Player player, List<PendingProbe> batch) {
        if (player == null || !player.isOnline() || batch.isEmpty()) return;

        UUID uuid = player.getUniqueId();
        ConfigManager cfg = plugin.getConfigManager();

        Vector3i pos = packetSender.computeSignPosition(player);
        DetectionSession session = new DetectionSession(uuid, cfg.getFallbackMarker(), pos);
        for (int i = 0; i < batch.size() && i < cfg.getKeysPerSign(); i++) {
            PendingProbe p = batch.get(i);
            session.addLine(i, p.modId, p.key);
        }
        activeSessions.put(uuid, session);
        packetSender.send(player, session);

        // Schedule a timeout — if no response arrives, clean up the ghost sign,
        // discard the session, and try the next queued batch if any.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            DetectionSession current = activeSessions.get(uuid);
            if (current != null && current == session && !current.isResponded()) {
                activeSessions.remove(uuid);
                if (player.isOnline()) {
                    packetSender.sendCleanup(player, session.getSignPosition());
                }
                dispatchNextQueued(player);
            }
        }, cfg.getResponseTimeoutTicks());
    }

    /**
     * Returns (without removing) the current session for the player, or null.
     * Used by the listener to check if an UPDATE_SIGN belongs to us.
     */
    public DetectionSession peekSession(UUID uuid) {
        return activeSessions.get(uuid);
    }

    /**
     * Called by the response listener. Returns and removes the current session
     * for the player, or null if none.
     */
    public DetectionSession consumeSession(UUID uuid) {
        DetectionSession session = activeSessions.remove(uuid);
        if (session != null) {
            // Move on to the next queued batch (if any) after this one resolves.
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> dispatchNextQueued(player),
                        Math.max(1, plugin.getConfigManager().getCloseDelayTicks() + 2));
            }
        }
        return session;
    }

    /**
     * Sends a BLOCK_CHANGE for AIR at the given position to remove the ghost sign.
     */
    public void sendCleanup(Player player, Vector3i pos) {
        packetSender.sendCleanup(player, pos);
    }

    private void dispatchNextQueued(Player player) {
        UUID uuid = player.getUniqueId();
        List<PendingProbe> queue = queuedProbes.get(uuid);
        if (queue == null || queue.isEmpty()) {
            queuedProbes.remove(uuid);
            return;
        }
        int keysPerSign = plugin.getConfigManager().getKeysPerSign();
        int take = Math.min(keysPerSign, queue.size());
        List<PendingProbe> next = new ArrayList<>(queue.subList(0, take));
        List<PendingProbe> remaining = new ArrayList<>(queue.subList(take, queue.size()));
        if (remaining.isEmpty()) {
            queuedProbes.remove(uuid);
        } else {
            queuedProbes.put(uuid, remaining);
        }
        dispatchBatch(player, next);
    }

    /**
     * Called on the main thread when the response listener determines a mod
     * was translated.
     *
     * @param matchedKeys comma-separated list of keys that came back translated
     */
    public void handleDetection(Player player, String modId, String matchedKeys) {
        if (player == null || !player.isOnline()) return;
        ModConfig mod = plugin.getConfigManager().getMod(modId);
        if (mod == null) return;
        actionExecutor.execute(player, mod, matchedKeys);
    }

    public void onPlayerQuit(UUID uuid) {
        activeSessions.remove(uuid);
        queuedProbes.remove(uuid);
    }

    private record PendingProbe(String modId, String key) {}
}
