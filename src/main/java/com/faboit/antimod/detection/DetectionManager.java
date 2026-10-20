package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;
import com.faboit.antimod.AntiMod;
import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.config.ModConfig;
import com.faboit.antimod.config.ModCatalog;
import com.faboit.antimod.config.SpoofModConfig;
import com.faboit.antimod.detection.DetectionSession.ProbeKind;
import com.faboit.antimod.util.ClientVersionUtil;
import com.faboit.antimod.util.FloodgateUtil;
import com.faboit.antimod.util.FoliaScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
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
    private final SpoofEntryMonitor entryMonitor;

    /** The in-flight sweep per player, if any. */
    private final Map<UUID, ProbeBatch> activeBatches = new ConcurrentHashMap<>();

    /** Join probes still waiting for the client to announce its channels. */
    private final Map<UUID, PendingJoin> pendingJoins = new ConcurrentHashMap<>();

    public DetectionManager(AntiMod plugin, ActionExecutor actionExecutor, ChannelTracker channelTracker) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.channelTracker = channelTracker;
        this.entryMonitor = new SpoofEntryMonitor(plugin);
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
        if (!cfg.isSpoofEnabled() || !cfg.isSpoofWaitForChannels() || cfg.getSpoofMods().isEmpty()
                || channelTracker.isVanillaBrand(player)) {
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
            // A scan runs longer than a sweep's window; only an admin's
            // explicit check may cut it short.
            if (!force && (inFlight.getScan() != null || !inFlight.isExpired(timeoutMillis))) return;
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
                probes.add(new PendingProbe(mod.getId(), key, ProbeKind.BANNED));
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
                session.addLine(i - start, p.modId, p.key, p.kind);
            }
            batch.addSession(session);
        }

        // Register every session up-front, then fire them all in one burst — no
        // waiting for a response between signs.
        if (activeBatches.putIfAbsent(uuid, batch) != null) return;
        for (DetectionSession session : batch.getSessions()) {
            session.markSent();
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
        // Bedrock players run no Java mods; any channel on them came from the proxy.
        if (FloodgateUtil.isFloodgatePlayer(player)) return;
        Set<String> registered = channelTracker.getSpoofCheckChannels(player);
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
                probes.add(new PendingProbe(mod.getId(), key, ProbeKind.SPOOF_CHECK));
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
        if (!batch.isComplete()) return;
        if (batch.getScan() != null) {
            activeBatches.remove(player.getUniqueId(), batch);
            finishScan(player, batch);
        } else {
            evaluateSpoofCheck(player, batch);
        }
    }

    /**
     * Flags the player when the channel-confirmed mods that answered none of
     * their keys outnumber the ones that resolved theirs (and there are at
     * least {@code min-hidden-mods} of them), or when the client echoed a
     * probed key back verbatim. A client hiding translations hides them for
     * every mod, so when most mods verify, the few that did not point at
     * those entries rather than the player; an entry that fails for a player
     * who verified everything else is reported to the {@link SpoofEntryMonitor}.
     *
     * The echo only counts for Java clients on 1.19.4+: older clients ignore
     * the fallback and show the raw key as plain vanilla behaviour. Runs at
     * most once per sweep.
     */
    private void evaluateSpoofCheck(Player player, ProbeBatch batch) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isSpoofEnabled() || player == null) return;
        boolean echoed = cfg.isSpoofFlagRawKeyEcho() && !batch.getRawEchoKeys().isEmpty()
                && !FloodgateUtil.isFloodgatePlayer(player)
                && ClientVersionUtil.supportsTranslationFallback(player);
        if (!batch.hasSpoofChecks() && !echoed) return;
        if (!batch.claimSpoofEvaluation()) return;

        List<String> hidden = batch.getHiddenSpoofMods();
        List<String> verified = batch.getVerifiedSpoofMods();
        if (hidden.size() == 1 && !verified.isEmpty()) {
            entryMonitor.recordMiss(hidden.get(0), player.getUniqueId());
        }
        boolean enoughHidden = hidden.size() >= cfg.getSpoofMinHiddenMods() && hidden.size() > verified.size();
        if (!enoughHidden && !echoed) return;

        List<String> evidence = new ArrayList<>();
        if (enoughHidden) {
            List<String> names = new ArrayList<>();
            for (String id : hidden) {
                SpoofModConfig mod = cfg.getSpoofMods().get(id);
                String name = mod == null ? id : mod.getDisplayName();
                names.add(name + " [" + batch.getSpoofCheckChannel(id) + "]");
            }
            evidence.add("hid " + hidden.size() + " of " + (hidden.size() + verified.size())
                    + " channel-confirmed mods: " + String.join(", ", names));
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

    // ------------------------------------------------------------------
    //   Mod scan (/antimod mods)
    // ------------------------------------------------------------------

    /**
     * Probes the player for every mod in mod-catalog.yml (and the banned
     * mods), then reports to {@code requester}. A scan only reads: nothing it
     * finds records a violation or runs an action. Its signs go out in waves
     * of {@code mod-scan.signs-per-tick} so a large catalog doesn't arrive as
     * one burst.
     */
    public void runModScan(Player target, CommandSender requester) {
        FoliaScheduler.runNowOrSoon(plugin, target, () -> runModScanNow(target, requester));
    }

    private void runModScanNow(Player target, CommandSender requester) {
        if (!target.isOnline()) {
            requester.sendMessage(Component.text("Player not online.", NamedTextColor.RED));
            return;
        }
        if (FloodgateUtil.isFloodgatePlayer(target)) {
            requester.sendMessage(Component.text("[AntiMod] " + target.getName()
                    + " is a Bedrock player: there are no Java mods to scan for.", NamedTextColor.YELLOW));
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();
        UUID uuid = target.getUniqueId();
        ProbeBatch inFlight = activeBatches.get(uuid);
        if (inFlight != null && inFlight.getScan() != null) {
            requester.sendMessage(Component.text("[AntiMod] A scan of " + target.getName()
                    + " is already running.", NamedTextColor.YELLOW));
            return;
        }
        if (inFlight != null && activeBatches.remove(uuid, inFlight)) {
            cleanupUnanswered(target, inFlight);
        }

        ModCatalog catalog = cfg.getCatalog();
        Map<String, String> names = ModScan.namesOf(catalog);
        List<PendingProbe> probes = new ArrayList<>();
        ProbeBatch batch = new ProbeBatch(uuid);
        for (ModCatalog.CatalogMod mod : catalog.getMods()) {
            for (String key : mod.keys()) probes.add(new PendingProbe(mod.id(), key, ProbeKind.CATALOG));
            batch.expectCatalog(mod.id(), mod.keys());
        }
        // The banned mods too, reported by name but never acted on here.
        for (ModConfig mod : cfg.getMods().values()) {
            String owner = "config:" + mod.getId();
            names.put(owner, mod.getDisplayName());
            for (String key : mod.getTranslationKeys()) probes.add(new PendingProbe(owner, key, ProbeKind.CATALOG));
            batch.expectCatalog(owner, mod.getTranslationKeys());
        }
        if (probes.isEmpty()) {
            requester.sendMessage(Component.text("[AntiMod] The mod catalog is empty.", NamedTextColor.RED));
            return;
        }

        int keysPerSign = cfg.getScanKeysPerSign();
        int signCount = (probes.size() + keysPerSign - 1) / keysPerSign;
        List<Vector3i> positions = packetSender.computeSignPositions(target, signCount);
        List<DetectionSession> sessions = new ArrayList<>(signCount);
        for (int signIndex = 0; signIndex < signCount; signIndex++) {
            int start = signIndex * keysPerSign;
            int end = Math.min(start + keysPerSign, probes.size());
            DetectionSession session = new DetectionSession(uuid, cfg.getFallbackMarker(), positions.get(signIndex));
            for (int i = start; i < end; i++) {
                PendingProbe p = probes.get(i);
                session.addLine(i - start, p.modId, p.key, p.kind);
            }
            sessions.add(session);
            batch.addSession(session);
        }
        ModScan scan = new ModScan(requester, target, names, signCount);
        batch.setScan(scan);
        if (activeBatches.putIfAbsent(uuid, batch) != null) {
            requester.sendMessage(Component.text("[AntiMod] " + target.getName()
                    + " is being probed right now; try again in a moment.", NamedTextColor.YELLOW));
            return;
        }
        requester.sendMessage(Component.text("[AntiMod] Scanning " + target.getName() + " for "
                + names.size() + " mods (" + signCount + " signs)...", NamedTextColor.GRAY));

        int perTick = cfg.getScanSignsPerTick();
        int waves = (signCount + perTick - 1) / perTick;
        for (int wave = 0; wave < waves; wave++) {
            List<DetectionSession> slice = sessions.subList(wave * perTick, Math.min(signCount, (wave + 1) * perTick));
            Runnable send = () -> {
                if (activeBatches.get(uuid) != batch || !target.isOnline()) return;
                for (DetectionSession s : slice) {
                    s.markSent();
                    packetSender.send(target, s);
                }
            };
            if (wave == 0) send.run();
            else FoliaScheduler.runTaskLater(plugin, target, send, wave);
        }

        FoliaScheduler.runGlobalTaskLater(plugin, () -> {
            if (activeBatches.remove(uuid, batch)) {
                cleanupUnanswered(target, batch);
            }
            finishScan(target, batch);
        }, waves + cfg.getScanResponseTimeoutTicks());
    }

    private void finishScan(Player target, ProbeBatch batch) {
        ModScan scan = batch.getScan();
        if (scan == null || !scan.claimFinish()) return;
        int unanswered = batch.getSessions().size();
        ConfigManager cfg = plugin.getConfigManager();
        FoliaScheduler.runGlobalTask(plugin, () -> {
            try {
                scan.report(batch, target, channelTracker, cfg.getCatalog(), cfg.getSpoofMods().values(), unanswered);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to report the mod scan of " + scan.getTargetName(), t);
            }
        });
    }

    /** Clears the ghost signs left by any session in the batch that never answered. */
    private void cleanupUnanswered(Player player, ProbeBatch batch) {
        if (player == null || !player.isOnline()) return;
        for (DetectionSession s : batch.getSessions()) {
            if (s.isSent() && !s.isResponded()) {
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

    private record PendingProbe(String modId, String key, ProbeKind kind) {}

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
