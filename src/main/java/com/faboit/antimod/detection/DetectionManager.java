package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;
import com.faboit.antimod.AntiMod;
import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.config.ModConfig;
import com.faboit.antimod.util.FloodgateUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the detection lifecycle:
 *   - deciding what to probe
 *   - packing keys into signs (up to 4 keys / lines per sign)
 *   - firing every probe at once via SignPacketSender
 *   - accepting responses from SignResponseListener
 *   - handing detections off to ActionExecutor
 *
 * All of a player's probes are dispatched in a single burst (each sign at its
 * own stacked position) so the whole sweep completes during the join / loading
 * screen instead of trickling out one sign at a time.
 */
public final class DetectionManager {

    private final AntiMod plugin;
    private final SignPacketSender packetSender;
    private final ActionExecutor actionExecutor;

    /** All in-flight sessions per player — one per phantom sign in the burst. */
    private final Map<UUID, List<DetectionSession>> activeSessions = new ConcurrentHashMap<>();

    public DetectionManager(AntiMod plugin, ActionExecutor actionExecutor) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.packetSender = new SignPacketSender(plugin);
    }

    /**
     * Kicks off a full detection cycle for the given player: enumerates every
     * enabled mod, packs their keys into signs, and fires them all immediately.
     */
    public void runFullDetection(Player player) {
        if (player == null || !player.isOnline()) return;
        if (player.hasPermission("antimod.bypass")) return;

        ConfigManager cfg = plugin.getConfigManager();

        // Skip Floodgate players if disabled in config
        if (FloodgateUtil.isFloodgatePlayer(player) && !cfg.isFloodgateEnabled()) {
            return;
        }

        // Build a flat list of (modId, key) pairs from every enabled mod.
        List<PendingProbe> probes = new ArrayList<>();
        for (ModConfig mod : cfg.getMods().values()) {
            if (!mod.isEnabled()) continue;
            for (String key : mod.getTranslationKeys()) {
                probes.add(new PendingProbe(mod.getId(), key));
            }
        }
        if (probes.isEmpty()) return;

        int keysPerSign = cfg.getKeysPerSign();
        UUID uuid = player.getUniqueId();

        // Drop any stale sessions from a previous, still-pending run.
        List<DetectionSession> stale = activeSessions.remove(uuid);
        if (stale != null) {
            for (DetectionSession s : stale) {
                if (!s.isResponded()) packetSender.sendCleanup(player, s.getSignPosition());
            }
        }

        // Pack probes into signs (up to keysPerSign lines each), one session per
        // sign, each at its own stacked position.
        List<DetectionSession> sessions = new ArrayList<>();
        int signIndex = 0;
        for (int start = 0; start < probes.size(); start += keysPerSign) {
            int end = Math.min(start + keysPerSign, probes.size());
            Vector3i pos = packetSender.computeSignPosition(player, signIndex);
            DetectionSession session = new DetectionSession(uuid, cfg.getFallbackMarker(), pos);
            for (int i = start; i < end; i++) {
                PendingProbe p = probes.get(i);
                session.addLine(i - start, p.modId, p.key);
            }
            sessions.add(session);
            signIndex++;
        }

        // Register every session up-front, then fire them all in one burst — no
        // waiting for a response between signs.
        List<DetectionSession> batch = new CopyOnWriteArrayList<>(sessions);
        activeSessions.put(uuid, batch);
        for (DetectionSession session : sessions) {
            packetSender.send(player, session);
        }

        // Timeout: after the configured window, discard any sessions that never
        // got a response and clean up their ghost signs. Only touch our own
        // batch — a newer run for this player may have superseded it.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!activeSessions.remove(uuid, batch)) return;
            if (!player.isOnline()) return;
            for (DetectionSession s : batch) {
                if (!s.isResponded()) {
                    packetSender.sendCleanup(player, s.getSignPosition());
                }
            }
        }, cfg.getResponseTimeoutTicks());
    }

    /**
     * Called by the response listener. Finds the in-flight session for this
     * player whose phantom sign is at {@code pos}, removes it, and returns it —
     * or null if this UPDATE_SIGN is for a real sign we didn't send.
     */
    public DetectionSession matchAndRemove(UUID uuid, Vector3i pos) {
        List<DetectionSession> sessions = activeSessions.get(uuid);
        if (sessions == null) return null;
        for (DetectionSession s : sessions) {
            Vector3i sp = s.getSignPosition();
            if (sp.getX() == pos.getX() && sp.getY() == pos.getY() && sp.getZ() == pos.getZ()) {
                sessions.remove(s);
                // Drop the mapping once empty, but only if it's still this exact
                // batch (a newer run may have replaced it).
                if (sessions.isEmpty()) {
                    activeSessions.remove(uuid, sessions);
                }
                return s;
            }
        }
        return null;
    }

    /**
     * Sends a BLOCK_CHANGE for AIR at the given position to remove the ghost sign.
     */
    public void sendCleanup(Player player, Vector3i pos) {
        packetSender.sendCleanup(player, pos);
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
    }

    private record PendingProbe(String modId, String key) {}
}
