package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;
import com.faboit.antimod.AntiMod;
import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.config.ModConfig;
import com.faboit.antimod.config.SpoofModConfig;
import com.faboit.antimod.util.FloodgateUtil;
import com.faboit.antimod.util.FoliaScheduler;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * Spoof check: every sweep also probes the keys of each spoof-mods.yml entry
 * whose plugin channel the client registered. A registered channel proves
 * the mod is installed, so its keys must translate; if they all come back as
 * the fallback, something on the client is suppressing translations.
 */
public final class DetectionManager {

    private final AntiMod plugin;
    private final SignPacketSender packetSender;
    private final ActionExecutor actionExecutor;
    private final ChannelTracker channelTracker;

    /** The in-flight sweep per player, if any. */
    private final Map<UUID, ProbeBatch> activeBatches = new ConcurrentHashMap<>();

    /** Join probes still waiting for the client to announce its channels. */
    private final Map<UUID, PendingJoin> pendingJoins = new ConcurrentHashMap<>();

    public DetectionManager(AntiMod plugin, ActionExecutor actionExecutor, ChannelTracker channelTracker) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.channelTracker = channelTracker;
        this.packetSender = new SignPacketSender(plugin);
        channelTracker.setPlayRegistrationListener(this::onPlayRegistration);
    }

    // ------------------------------------------------------------------
    //   Join scheduling
    // ------------------------------------------------------------------

    /**
     * Schedules the on-join sweep.
     *
     * With the spoof check on, the sweep waits for the client's play-phase
     * channel registration (plus a short settle window, since it can span
     * several packets) so the channel-confirmed mods are known when the signs
     * go out. A client that reports the vanilla brand has no channels to wait
     * for and is probed straight away; any other client that never registers
     * is probed once {@code channel-wait-ticks} have passed.
     */
    public void scheduleJoinProbe(Player player) {
        ConfigManager cfg = plugin.getConfigManager();
        int joinDelay = cfg.getJoinDelayTicks();
        String brand = player.getClientBrandName();
        boolean vanillaBrand = brand != null && brand.equalsIgnoreCase("vanilla");
        if (!cfg.isSpoofEnabled() || !cfg.isSpoofWaitForChannels() || cfg.getSpoofMods().isEmpty()
                || vanillaBrand) {
            runJoinProbeLater(player, null, joinDelay);
            return;
        }

        PendingJoin pending = new PendingJoin(player);
        pendingJoins.put(player.getUniqueId(), pending);

        // Fallback for clients that never register a channel.
        runJoinProbeLater(player, pending, Math.max(joinDelay, cfg.getSpoofChannelWaitTicks()));

        // The registration can beat the join event on a fast connection.
        if (channelTracker.hasPlayRegistration(player.getUniqueId())) {
            onPlayRegistration(player.getUniqueId());
        }
    }

    /** ChannelTracker callback, on a Netty thread: (re)start the settle window. */
    private void onPlayRegistration(UUID uuid) {
        PendingJoin pending = pendingJoins.get(uuid);
        if (pending == null || pending.fired.get()) return;
        ConfigManager cfg = plugin.getConfigManager();
        int generation = pending.generation.incrementAndGet();
        int delay = Math.max(cfg.getJoinDelayTicks(), cfg.getSpoofChannelSettleTicks());
        Runnable task = () -> {
            // A later registration restarted the window; let its task fire instead.
            if (pending.generation.get() != generation) return;
            fireJoinProbe(pending);
        };
        if (delay <= 0) {
            FoliaScheduler.runTask(plugin, pending.player, task);
        } else {
            FoliaScheduler.runTaskLater(plugin, pending.player, task, delay);
        }
    }

    private void runJoinProbeLater(Player player, PendingJoin pending, int delay) {
        Runnable task = pending == null
                ? () -> { if (player.isOnline()) runFullDetection(player); }
                : () -> fireJoinProbe(pending);
        if (delay <= 0) {
            FoliaScheduler.runTask(plugin, player, task);
        } else {
            FoliaScheduler.runTaskLater(plugin, player, task, delay);
        }
    }

    private void fireJoinProbe(PendingJoin pending) {
        if (!pending.fired.compareAndSet(false, true)) return;
        pendingJoins.remove(pending.player.getUniqueId(), pending);
        if (pending.player.isOnline()) runFullDetection(pending.player);
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
                probes.add(new PendingProbe(mod.getId(), key, false));
            }
        }

        // Spoof check: the keys of every mod whose channel the client announced.
        ProbeBatch batch = new ProbeBatch(uuid);
        if (cfg.isSpoofEnabled()) {
            addSpoofCheckProbes(player, cfg, batch, probes);
        }
        if (probes.isEmpty()) return;

        int keysPerSign = cfg.getKeysPerSign();
        int signCount = (probes.size() + keysPerSign - 1) / keysPerSign;

        // Every sign needs its own block position, or the client keeps only the
        // last one and the rest are never answered.
        List<Vector3i> positions = packetSender.computeSignPositions(player, signCount);

        for (int signIndex = 0; signIndex < signCount; signIndex++) {
            int start = signIndex * keysPerSign;
            int end = Math.min(start + keysPerSign, probes.size());
            DetectionSession session = new DetectionSession(
                    uuid, cfg.getFallbackMarker(), positions.get(signIndex));
            for (int i = start; i < end; i++) {
                PendingProbe p = probes.get(i);
                session.addLine(i - start, p.modId, p.key, p.spoofCheck);
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
            if (activeBatches.remove(uuid, batch)) {
                cleanupUnanswered(player, batch);
            }
            // Judge whatever did come back. A no-op if the sweep completed
            // (and was judged) already.
            evaluateSpoofCheck(player, batch);
        }, cfg.getResponseTimeoutTicks());
    }

    /**
     * Adds the keys of every enabled spoof-check mod whose channel this client
     * registered, up to {@code max-mods-per-sweep} mods.
     */
    private void addSpoofCheckProbes(Player player, ConfigManager cfg, ProbeBatch batch,
                                     List<PendingProbe> probes) {
        Set<String> registered = channelTracker.getChannels(player);
        if (registered.isEmpty()) return;
        int budget = cfg.getSpoofMaxModsPerSweep();
        for (SpoofModConfig mod : cfg.getSpoofMods().values()) {
            if (budget <= 0) break;
            if (!mod.isEnabled()) continue;
            String channel = mod.findChannel(registered);
            if (channel == null) continue;
            List<String> keys = new ArrayList<>(new LinkedHashSet<>(mod.getTranslationKeys()));
            batch.expectSpoofCheck(mod.getId(), channel, keys);
            for (String key : keys) {
                probes.add(new PendingProbe(mod.getId(), key, true));
            }
            budget--;
        }
    }

    /**
     * Called by the response listener once a sign's answer has been recorded.
     * When that was the sweep's last sign, the spoof verdict is reached now
     * rather than at the timeout.
     */
    public void onSessionAnswered(Player player, ProbeBatch batch) {
        if (batch.isComplete()) {
            evaluateSpoofCheck(player, batch);
        }
    }

    /**
     * Flags the player when a channel-confirmed mod answered none of its keys,
     * or when the client echoed a probed key back verbatim (a vanilla client
     * always answers with the supplied fallback). Runs at most once per sweep.
     */
    private void evaluateSpoofCheck(Player player, ProbeBatch batch) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isSpoofEnabled() || player == null) return;
        boolean echoed = cfg.isSpoofFlagRawKeyEcho() && !batch.getRawEchoKeys().isEmpty();
        if (!batch.hasSpoofChecks() && !echoed) return;
        if (!batch.claimSpoofEvaluation()) return;

        List<String> hidden = batch.getHiddenSpoofMods();
        boolean enoughHidden = hidden.size() >= cfg.getSpoofMinHiddenMods();
        if (!enoughHidden && !echoed) return;

        List<String> evidence = new ArrayList<>();
        if (enoughHidden) {
            for (String id : hidden) {
                SpoofModConfig mod = cfg.getSpoofMods().get(id);
                String name = mod == null ? id : mod.getDisplayName();
                evidence.add(name + " [" + batch.getSpoofCheckChannel(id) + "]");
            }
        }
        if (echoed) {
            evidence.add("raw key echo: " + String.join(", ", batch.getRawEchoKeys()));
        }
        String details = String.join("; ", evidence);

        FoliaScheduler.runGlobalTask(plugin, () -> {
            try {
                actionExecutor.execute(player, cfg.getSpoofDetector(), details);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to run the spoof-detection action on " + player.getName(), t);
            }
        });
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
        pendingJoins.remove(uuid);
        channelTracker.forget(uuid);
    }

    public ChannelTracker getChannelTracker() {
        return channelTracker;
    }

    /** A matched sign response: the session that was probed and the sweep it belongs to. */
    public record Match(ProbeBatch batch, DetectionSession session) {}

    private record PendingProbe(String modId, String key, boolean spoofCheck) {}

    /** A join probe waiting for the client's channel registration. */
    private static final class PendingJoin {
        final Player player;
        final AtomicBoolean fired = new AtomicBoolean();
        /** Bumped by every registration so only the latest settle window fires. */
        final AtomicInteger generation = new AtomicInteger();

        PendingJoin(Player player) {
            this.player = player;
        }
    }
}
