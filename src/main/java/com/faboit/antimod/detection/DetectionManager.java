package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;
import com.faboit.antimod.AntiMod;
import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.config.ModConfig;
import com.faboit.antimod.util.FloodgateUtil;
import com.faboit.antimod.util.FoliaScheduler;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns the detection lifecycle:
 *   - deciding what to probe
 *   - packing keys into signs (up to 4 keys / lines per sign)
 *   - firing every probe at once via SignPacketSender
 *   - accepting responses from SignResponseListener
 *   - handing detections off to ActionExecutor
 *
 * All of a player's probes are dispatched in a single burst (each sign at its
 * own position) so the whole sweep completes during the join / loading screen
 * instead of trickling out one sign at a time. Exactly one sweep is in flight
 * per player at a time: a second sweep would reuse the same sign positions and
 * make the client's answers ambiguous, so it is skipped until the first one
 * either completes or times out.
 */
public final class DetectionManager {

    private final AntiMod plugin;
    private final SignPacketSender packetSender;
    private final ActionExecutor actionExecutor;

    /** The in-flight sweep per player, if any. */
    private final Map<UUID, ProbeBatch> activeBatches = new ConcurrentHashMap<>();

    public DetectionManager(AntiMod plugin, ActionExecutor actionExecutor) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.packetSender = new SignPacketSender(plugin);
    }

    /**
     * Kicks off a full detection cycle for the given player: enumerates every
     * enabled mod, packs their keys into signs, and fires them all immediately.
     *
     * Safe to call from any thread — building the probes reads the player's
     * location, so the work is moved onto the thread that owns the player
     * first (immediately, if the caller is already on it).
     */
    public void runFullDetection(Player player) {
        runFullDetection(player, false);
    }

    /**
     * @param force retire any sweep that is still in flight and start a fresh
     *              one anyway. Used by {@code /antimod check}, where an admin
     *              has explicitly asked for a probe right now; the automatic
     *              join and periodic sweeps pass false so they never stomp on
     *              answers that are still on the wire.
     */
    public void runFullDetection(Player player, boolean force) {
        if (player == null || !player.isOnline()) return;
        FoliaScheduler.runNowOrSoon(plugin, player, () -> runFullDetectionNow(player, force));
    }

    private void runFullDetectionNow(Player player, boolean force) {
        if (!player.isOnline()) return;
        if (player.hasPermission("antimod.bypass")) return;

        ConfigManager cfg = plugin.getConfigManager();

        // Skip Floodgate players if disabled in config
        if (FloodgateUtil.isFloodgatePlayer(player) && !cfg.isFloodgateEnabled()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long timeoutMillis = Math.max(1L, cfg.getResponseTimeoutTicks()) * 50L;

        // A sweep that is still within its response window owns this player's
        // sign positions. Starting another one now would overwrite its phantom
        // signs client-side and throw away answers that are still on the wire —
        // which is exactly how a detection (and its punishment command) goes
        // missing. Let the in-flight sweep finish instead.
        ProbeBatch inFlight = activeBatches.get(uuid);
        if (inFlight != null) {
            if (!force && !inFlight.isExpired(timeoutMillis)) return;
            // Expired (or explicitly superseded): retire it and clean up
            // whatever it left on the client.
            if (activeBatches.remove(uuid, inFlight)) {
                cleanupUnanswered(player, inFlight);
            }
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
        int signCount = (probes.size() + keysPerSign - 1) / keysPerSign;

        // Every sign needs its own block position, or the client keeps only the
        // last one and the rest are never answered.
        List<Vector3i> positions = packetSender.computeSignPositions(player, signCount);

        ProbeBatch batch = new ProbeBatch(uuid);
        for (int signIndex = 0; signIndex < signCount; signIndex++) {
            int start = signIndex * keysPerSign;
            int end = Math.min(start + keysPerSign, probes.size());
            DetectionSession session = new DetectionSession(
                    uuid, cfg.getFallbackMarker(), positions.get(signIndex));
            for (int i = start; i < end; i++) {
                PendingProbe p = probes.get(i);
                session.addLine(i - start, p.modId, p.key);
            }
            batch.addSession(session);
        }

        // Register every session up-front, then fire them all in one burst — no
        // waiting for a response between signs.
        if (activeBatches.putIfAbsent(uuid, batch) != null) return;
        for (DetectionSession session : batch.getSessions()) {
            packetSender.send(player, session);
        }

        // Timeout: after the configured window, discard any sessions that never
        // got a response and clean up their ghost signs. Only touch our own
        // batch — a newer sweep for this player may have superseded it.
        //
        // This runs on the global scheduler rather than the player's entity
        // scheduler so it still fires (and still frees the player's slot) when
        // the player has logged off in the meantime.
        FoliaScheduler.runGlobalTaskLater(plugin, () -> {
            if (!activeBatches.remove(uuid, batch)) return;
            cleanupUnanswered(player, batch);
        }, cfg.getResponseTimeoutTicks());
    }

    /** Clears the ghost signs left by any session in the batch that never answered. */
    private void cleanupUnanswered(Player player, ProbeBatch batch) {
        if (player == null || !player.isOnline()) return;
        for (DetectionSession s : batch.getSessions()) {
            if (!s.isResponded()) {
                packetSender.sendCleanup(player, s.getSignPosition());
            }
        }
    }

    /**
     * Called by the response listener. Finds the in-flight session for this
     * player whose phantom sign is at {@code pos}, removes it, and returns it
     * together with its sweep — or null if this UPDATE_SIGN is for a real sign
     * we didn't send.
     */
    public Match matchAndRemove(UUID uuid, Vector3i pos) {
        ProbeBatch batch = activeBatches.get(uuid);
        if (batch == null) return null;
        DetectionSession session = batch.matchAndRemove(pos);
        if (session == null) return null;
        // Drop the mapping once every sign has answered, but only if it is
        // still this exact sweep (a newer one may have replaced it).
        if (batch.isComplete()) {
            activeBatches.remove(uuid, batch);
        }
        return new Match(batch, session);
    }

    /**
     * Sends a BLOCK_CHANGE for AIR at the given position to remove the ghost sign.
     */
    public void sendCleanup(Player player, Vector3i pos) {
        packetSender.sendCleanup(player, pos);
    }

    /**
     * Runs the configured action for a mod that came back translated.
     *
     * Deliberately does not require the player to still be online: a player who
     * disconnects the instant their client answers (or who was just kicked by
     * another mod's action in the same sweep) must still have their violation
     * recorded and their punishment command dispatched.
     *
     * @param matchedKeys comma-separated list of keys that came back translated
     */
    public void handleDetection(Player player, String modId, String matchedKeys) {
        if (player == null) return;
        ModConfig mod = plugin.getConfigManager().getMod(modId);
        if (mod == null) return;
        try {
            actionExecutor.execute(player, mod, matchedKeys);
        } catch (Throwable t) {
            // One mod's action blowing up must not swallow the actions of the
            // other mods detected in the same sweep.
            plugin.getLogger().log(Level.WARNING,
                    "Failed to run the action for " + modId + " on " + player.getName(), t);
        }
    }

    public void onPlayerQuit(UUID uuid) {
        activeBatches.remove(uuid);
    }

    /** A matched sign response: the session that was probed and the sweep it belongs to. */
    public record Match(ProbeBatch batch, DetectionSession session) {}

    private record PendingProbe(String modId, String key) {}
}
