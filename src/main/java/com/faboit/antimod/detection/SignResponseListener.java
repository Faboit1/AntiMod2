package com.faboit.antimod.detection;

import com.faboit.antimod.AntiMod;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.faboit.antimod.util.FoliaScheduler;
import org.bukkit.Bukkit;
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
            DetectionSession session = manager.matchAndRemove(uuid, pos);
            if (session == null) return;

            // This is our fake sign; cancel so vanilla doesn't try to process it.
            event.setCancelled(true);
            session.markResponded();

            // Read the 4 lines.
            String[] lines = packet.getTextLines();
            if (lines == null) return;

            // Compare each line to the fallback marker; anything else means
            // the mod translated the key.
            Map<String, String> detected = new LinkedHashMap<>();
            for (int i = 0; i < lines.length && i < 4; i++) {
                if (!session.hasLine(i)) continue;
                String modId = session.getModAt(i);
                String probedKey = session.getKeyAt(i);
                String returned = lines[i];

                if (returned == null || returned.equals(session.getFallbackMarker())) {
                    continue; // fallback — mod not present
                }
                if (returned.isEmpty()) continue;

                detected.merge(modId, probedKey, (a, b) -> a + "," + b);
            }

            // Hop back to the entity's region thread for cleanup and game actions.
            final Vector3i cleanupPos = pos;
            FoliaScheduler.runTask(plugin, player, () -> {
                manager.sendCleanup(player, cleanupPos);

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
