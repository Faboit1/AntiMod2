package com.faboit.antimod.detection;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.detection.DetectionSession.ProbeKind;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.faboit.antimod.util.FoliaScheduler;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Intercepts the client-bound-to-server UPDATE_SIGN packet.
 *
 * When we probe a player, we file a DetectionSession keyed by their UUID.
 * When the client replies with UPDATE_SIGN, we look up the session, check
 * each of the four returned lines against our stored mapping, and hand
 * detected mods off to the DetectionManager.
 *
 * We only intercept responses for our known phantom position; anything
 * else is passed through untouched.
 */
public final class SignResponseListener extends PacketListenerAbstract {

    private final AntiMod plugin;
    private final DetectionManager manager;

    public SignResponseListener(AntiMod plugin, DetectionManager manager) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.manager = manager;
    }

    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        try {
            Player player = event.getPlayer();
            if (player == null) return;

            UUID uuid = player.getUniqueId();

            WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
            Vector3i pos = packet.getBlockPosition();
            if (pos == null) return;

            // Look up the in-flight session at this exact position. If there
            // isn't one, this UPDATE_SIGN is for a real sign — pass it through.
            DetectionManager.Match match = manager.matchAndRemove(uuid, pos);
            if (match == null) return;

            DetectionSession session = match.session();
            ProbeBatch batch = match.batch();

            // This is our fake sign; cancel so vanilla doesn't try to process it.
            event.setCancelled(true);
            session.markResponded();

            // Clear the ghost sign straight away. Packet sends are thread-safe,
            // so this does not need to wait for a server tick.
            manager.sendCleanup(player, pos);

            // Read the 4 lines.
            String[] lines = packet.getTextLines();
            Map<String, String> answered = new LinkedHashMap<>();
            try {
                if (lines == null) return;

                // Compare each line to the fallback marker; anything else means
                // the mod translated the key.
                for (int i = 0; i < lines.length && i < 4; i++) {
                    if (!session.hasLine(i)) continue;
                    String modId = session.getModAt(i);
                    String probedKey = session.getKeyAt(i);
                    String returned = lines[i];

                    boolean fallback = returned == null || returned.equals(session.getFallbackMarker());
                    // The key itself is never proof the mod is installed:
                    // clients before 1.19.4 ignore the fallback and show the
                    // raw key for any key they don't know, so that is the
                    // vanilla answer there. On newer clients it is a sign of
                    // tampering, which the spoof check weighs separately.
                    boolean rawEcho = probedKey.equals(returned);

                    ProbeKind kind = session.getKindAt(i);
                    if (kind == ProbeKind.CATALOG) {
                        // Only reported: a real, non-empty translation counts.
                        batch.recordCatalogAnswer(modId, probedKey,
                                !fallback && !rawEcho && !returned.isEmpty());
                        continue;
                    }
                    if (kind == ProbeKind.SPOOF_CHECK) {
                        // An empty answer is a key that resolved to an empty
                        // translation, so the mod is there.
                        batch.recordSpoofAnswer(modId, probedKey, !fallback && !rawEcho, rawEcho);
                        continue;
                    }
                    if (rawEcho) {
                        batch.recordRawEcho(probedKey);
                        continue;
                    }
                    if (fallback || returned.isEmpty()) continue; // mod not present

                    answered.merge(modId, probedKey, (a, b) -> a + "," + b);
                }
            } finally {
                // Every line of this sign is recorded; if it was the sweep's
                // last one, the spoof check can be judged now.
                manager.onSessionAnswered(player, batch);
            }
            if (answered.isEmpty()) return;

            // A mod with several probed keys answers on several signs. Only the
            // first sign to flag it counts, so one sweep is one offense per mod
            // rather than one per key — otherwise a warn/kick/ban ladder skips
            // its intermediate rungs.
            Map<String, String> detected = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : answered.entrySet()) {
                if (batch.flagMod(e.getKey())) {
                    detected.put(e.getKey(), e.getValue());
                }
            }
            if (detected.isEmpty()) return;

            // Hop to the global region thread for the game-facing actions:
            // console commands and broadcasts must not run from a Netty thread,
            // and the global scheduler (unlike the player's entity scheduler)
            // still runs the task if the player disconnects first.
            FoliaScheduler.runGlobalTask(plugin, () -> {
                for (Map.Entry<String, String> e : detected.entrySet()) {
                    manager.handleDetection(player, e.getKey(), e.getValue());
                }
            });

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Error while processing UPDATE_SIGN packet.", e);
        }
    }
}
