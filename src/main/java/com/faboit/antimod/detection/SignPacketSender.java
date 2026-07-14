package com.faboit.antimod.detection;

import com.faboit.antimod.AntiMod;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;

/**
 * Sends the packet sequence that primes a "phantom sign" on the client
 * and forces the sign editor open so the client will echo back the
 * translated (or fallback) text.
 *
 * Sequence per detection:
 *   1. BLOCK_CHANGE     - claim a sign now exists at the phantom position
 *   2. TILE_ENTITY_DATA - fill the sign with translation-key components
 *   3. OPEN_SIGN_EDITOR - trick the client into opening the sign editor
 *   4. CLOSE_WINDOW     - after a tick, force-close so the client submits
 *                         its current sign text via UPDATE_SIGN
 */
public final class SignPacketSender {

    private final AntiMod plugin;

    public SignPacketSender(AntiMod plugin) {
        this.plugin = plugin;
    }

    /**
     * Computes the phantom sign position relative to the player.
     * Same X/Z as the player, Y offset downward by the configured amount.
     */
    public Vector3i computeSignPosition(Player player) {
        int offset = plugin.getConfigManager().getSignYOffset();
        return new Vector3i(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY() - offset,
                player.getLocation().getBlockZ()
        );
    }

    /**
     * Sends the full detection sequence to the player for the given session.
     * The session must already contain its line-to-key mappings and position.
     */
    public void send(Player player, DetectionSession session) {
        try {
            Vector3i pos = session.getSignPosition();

            // 1. Block change - convince the client a sign exists here.
            // Use the StateType's default state (correct global palette id);
            // getByString would silently fall back to AIR because its lookup
            // keys include block-state properties (e.g. "oak_sign[rotation=0,...]").
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerBlockChange(pos, StateTypes.OAK_SIGN.createBlockState()));

            // 2. Block entity data - the sign's text with translation components
            NBTCompound nbt = buildSignNbt(session);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));

            // 3. Open sign editor
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerOpenSignEditor(pos, true));

            // 4. Close the window (immediately or after a small delay)
            int closeDelay = plugin.getConfigManager().getCloseDelayTicks();
            if (closeDelay <= 0) {
                sendCloseWindow(player);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> sendCloseWindow(player),
                        closeDelay);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to send detection packets for " + player.getName(), e);
        }
    }

    /**
     * Sends a BLOCK_CHANGE for AIR at the session's phantom position,
     * removing the ghost sign from the client's view.
     */
    public void sendCleanup(Player player, Vector3i pos) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerBlockChange(pos, StateTypes.AIR.createBlockState()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to send cleanup block for " + player.getName(), e);
        }
    }

    private void sendCloseWindow(Player player) {
        try {
            // The container id when closing the sign editor: it's 0 for
            // the sign editor (which behaves like the player inventory
            // slot 0 from a container perspective).
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerCloseWindow(0));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to send close-window to " + player.getName(), e);
        }
    }

    // ------------------------------------------------------------------
    //   NBT builders
    // ------------------------------------------------------------------

    /**
     * Builds the sign block-entity NBT with translation components for each
     * probed key. Layout for 1.20.5+:
     *
     * {
     *   front_text: { color:"black", has_glowing_text:0, messages:[ ... ] },
     *   back_text:  { color:"black", has_glowing_text:0, messages:[ empty ] },
     *   is_waxed: 0
     * }
     *
     * Each entry in messages is a text-component compound. For probed lines:
     *   { translate:"key.mod.foo", fallback:"am_fb" }
     * For empty lines:
     *   { text:"" }
     */
    private NBTCompound buildSignNbt(DetectionSession session) {
        NBTCompound tag = new NBTCompound();

        NBTCompound frontText = new NBTCompound();
        frontText.setTag("color", new NBTString("black"));
        frontText.setTag("has_glowing_text", new NBTByte((byte) 0));
        frontText.setTag("messages", buildMessagesList(session, session.getFallbackMarker()));
        tag.setTag("front_text", frontText);

        NBTCompound backText = new NBTCompound();
        backText.setTag("color", new NBTString("black"));
        backText.setTag("has_glowing_text", new NBTByte((byte) 0));
        backText.setTag("messages", buildEmptyMessagesList());
        tag.setTag("back_text", backText);

        tag.setTag("is_waxed", new NBTByte((byte) 0));
        return tag;
    }

    private NBTList<NBTCompound> buildMessagesList(DetectionSession session, String fallback) {
        NBTList<NBTCompound> messages = new NBTList<>(NBTType.COMPOUND);
        for (int line = 0; line < 4; line++) {
            NBTCompound msg = new NBTCompound();
            if (session.hasLine(line)) {
                msg.setTag("translate", new NBTString(session.getKeyAt(line)));
                msg.setTag("fallback", new NBTString(fallback));
            } else {
                msg.setTag("text", new NBTString(""));
            }
            messages.addTag(msg);
        }
        return messages;
    }

    private NBTList<NBTCompound> buildEmptyMessagesList() {
        NBTList<NBTCompound> messages = new NBTList<>(NBTType.COMPOUND);
        for (int i = 0; i < 4; i++) {
            NBTCompound msg = new NBTCompound();
            msg.setTag("text", new NBTString(""));
            messages.addTag(msg);
        }
        return messages;
    }
}
