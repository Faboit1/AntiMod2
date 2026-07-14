package com.faboit.antimod.detection;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.faboit.antimod.AntiMod;
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
public final class SignResponseListener extends PacketAdapter {

    private final AntiMod plugin;
    private final DetectionManager manager;

    public SignResponseListener(AntiMod plugin, DetectionManager manager) {
        super(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.UPDATE_SIGN);
        this.plugin = plugin;
        this.manager = manager;
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        try {
            Player player = event.getPlayer();
            if (player == null) return;

            UUID uuid = player.getUniqueId();

            // Look up this player's active session first; if none exists,
            // this UPDATE_SIGN is for a real sign — pass it through.
            DetectionSession session = manager.peekSession(uuid);
            if (session == null) return;

            BlockPosition pos = event.getPacket().getBlockPositionModifier().read(0);
            if (pos == null) return;

            // Verify the packet's position matches the session's phantom sign.
            BlockPosition expected = session.getSignPosition();
            if (pos.getX() != expected.getX()
                    || pos.getY() != expected.getY()
                    || pos.getZ() != expected.getZ()) {
                return; // real sign — leave alone
            }

            // This is our fake sign; cancel so vanilla doesn't try to process it.
            event.setCancelled(true);

            // Consume the session (removes it from the active map).
            manager.consumeSession(uuid);
            session.markResponded();

            // Read the 4 lines. In 1.20+ the packet stores them as String[]
            // in the arrays modifier at index 0.
            String[] lines = event.getPacket().getStringArrays().read(0);
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

            // Hop back to the main thread for cleanup and game actions.
            final BlockPosition cleanupPos = expected;
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Remove the ghost sign from the client by sending AIR.
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
