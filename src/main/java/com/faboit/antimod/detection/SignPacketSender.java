package com.faboit.antimod.detection;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.comphenix.protocol.wrappers.nbt.NbtBase;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import com.comphenix.protocol.wrappers.nbt.NbtList;
import com.faboit.antimod.AntiMod;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
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
    private final ProtocolManager protocol;

    private volatile Object cachedSignBlockEntityType;

    public SignPacketSender(AntiMod plugin) {
        this.plugin = plugin;
        this.protocol = ProtocolLibrary.getProtocolManager();
    }

    /**
     * Computes the phantom sign position relative to the player.
     * Same X/Z as the player, Y offset downward by the configured amount.
     */
    public BlockPosition computeSignPosition(Player player) {
        int offset = plugin.getConfigManager().getSignYOffset();
        return new BlockPosition(
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
            BlockPosition pos = session.getSignPosition();

            // 1. Block change - convince the client a sign exists here
            protocol.sendServerPacket(player, buildBlockChange(pos));

            // 2. Block entity data - the sign's text with translation components
            PacketContainer entityData = buildBlockEntityData(pos, session);
            if (entityData == null) return; // resolver failed — abort silently (already logged)
            protocol.sendServerPacket(player, entityData);

            // 3. Open sign editor
            protocol.sendServerPacket(player, buildOpenSignEditor(pos));

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
    public void sendCleanup(Player player, BlockPosition pos) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
            packet.getBlockPositionModifier().write(0, pos);
            packet.getBlockData().write(0, WrappedBlockData.createData(Material.AIR));
            protocol.sendServerPacket(player, packet);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to send cleanup block for " + player.getName(), e);
        }
    }

    private void sendCloseWindow(Player player) {
        try {
            PacketContainer close = protocol.createPacket(PacketType.Play.Server.CLOSE_WINDOW);
            // The container id when closing the sign editor: it's 0 for
            // the sign editor (which behaves like the player inventory
            // slot 0 from a container perspective).
            StructureModifier<Integer> ints = close.getIntegers();
            if (ints.size() > 0) {
                ints.write(0, 0);
            }
            protocol.sendServerPacket(player, close);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to send close-window to " + player.getName(), e);
        }
    }

    // ------------------------------------------------------------------
    //   Packet builders
    // ------------------------------------------------------------------

    private PacketContainer buildBlockChange(BlockPosition pos) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
        packet.getBlockPositionModifier().write(0, pos);
        // Use OAK_SIGN as the block; the client's sign editor doesn't
        // care about the specific wood type as long as it's a sign.
        packet.getBlockData().write(0, WrappedBlockData.createData(Material.OAK_SIGN));
        return packet;
    }

    private PacketContainer buildBlockEntityData(BlockPosition pos, DetectionSession session) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.TILE_ENTITY_DATA);

        // Position
        packet.getBlockPositionModifier().write(0, pos);

        // Block entity type — MUST be set or the packet encoder crashes the client.
        Object signType = resolveSignBlockEntityType();
        if (signType == null) {
            plugin.getLogger().severe(
                    "[AntiMod] CRITICAL: Could not resolve BlockEntityType.SIGN. " +
                    "Detection is disabled to prevent player kicks. " +
                    "Please report this with your server version to the AntiMod issue tracker.");
            return null;
        }

        // The TILE_ENTITY_DATA packet is a record: (BlockPos, BlockEntityType<?>, CompoundTag).
        // On Mojang-mapped Paper, field index 1 is the BlockEntityType.
        // Write it directly — don't search by current value (it's null in a fresh packet).
        try {
            packet.getModifier().write(1, signType);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "[AntiMod] Failed to write BlockEntityType to packet at index 1.", e);
            return null;
        }

        // NBT payload
        NbtCompound tag = buildSignNbt(session);
        packet.getNbtModifier().write(0, tag);

        return packet;
    }

    private PacketContainer buildOpenSignEditor(BlockPosition pos) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.OPEN_SIGN_EDITOR);
        packet.getBlockPositionModifier().write(0, pos);
        // In 1.20+ this packet has a boolean 'is_front_text'. Set true.
        StructureModifier<Boolean> booleans = packet.getBooleans();
        if (booleans.size() > 0) {
            booleans.write(0, true);
        }
        return packet;
    }

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
    private NbtCompound buildSignNbt(DetectionSession session) {
        NbtCompound tag = NbtFactory.ofCompound("");

        NbtCompound frontText = NbtFactory.ofCompound("front_text");
        frontText.put("color", "black");
        frontText.put("has_glowing_text", (byte) 0);
        frontText.put(buildMessagesList("messages", session, session.getFallbackMarker()));
        tag.put(frontText);

        NbtCompound backText = NbtFactory.ofCompound("back_text");
        backText.put("color", "black");
        backText.put("has_glowing_text", (byte) 0);
        backText.put(buildEmptyMessagesList("messages"));
        tag.put(backText);

        tag.put("is_waxed", (byte) 0);
        return tag;
    }

    @SuppressWarnings("unchecked")
    private NbtList<NbtBase<?>> buildMessagesList(String name,
                                                  DetectionSession session,
                                                  String fallback) {
        List<NbtBase<?>> entries = new ArrayList<>(4);
        for (int line = 0; line < 4; line++) {
            NbtCompound msg = NbtFactory.ofCompound("");
            if (session.hasLine(line)) {
                msg.put("translate", session.getKeyAt(line));
                msg.put("fallback", fallback);
            } else {
                msg.put("text", "");
            }
            entries.add((NbtBase<?>) (NbtBase) msg);
        }
        return (NbtList<NbtBase<?>>) (NbtList) NbtFactory.ofList(name, entries);
    }

    @SuppressWarnings("unchecked")
    private NbtList<NbtBase<?>> buildEmptyMessagesList(String name) {
        List<NbtBase<?>> entries = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            NbtCompound msg = NbtFactory.ofCompound("");
            msg.put("text", "");
            entries.add((NbtBase<?>) (NbtBase) msg);
        }
        return (NbtList<NbtBase<?>>) (NbtList) NbtFactory.ofList(name, entries);
    }

    /**
     * Resolves the NMS BlockEntityType.SIGN singleton.
     * Uses multiple strategies for maximum compatibility across Paper/Purpur versions.
     * Cached after first successful resolution.
     */
    private Object resolveSignBlockEntityType() {
        if (cachedSignBlockEntityType != null) return cachedSignBlockEntityType;

        // Use the server's own class loader — essential when plugins like
        // Nemesis/PacketEvents run alongside and may affect class resolution.
        ClassLoader serverCL = Bukkit.getServer().getClass().getClassLoader();

        // Strategy 1: Direct field access on Mojang-mapped Paper/Purpur (1.20.5+).
        try {
            Class<?> clazz = Class.forName(
                    "net.minecraft.world.level.block.entity.BlockEntityType", true, serverCL);
            try {
                Field f = clazz.getField("SIGN");
                f.setAccessible(true);
                Object val = f.get(null);
                if (val != null) {
                    cachedSignBlockEntityType = val;
                    plugin.getLogger().info("[AntiMod] Resolved BlockEntityType.SIGN via direct field.");
                    return cachedSignBlockEntityType;
                }
            } catch (NoSuchFieldException e) {
                // Field doesn't exist under this name — dump what's available.
                plugin.getLogger().warning("[AntiMod] Strategy 1: No field named 'SIGN'. Fields with 'sign' in name:");
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getName().toLowerCase().contains("sign")) {
                        plugin.getLogger().warning("[AntiMod]   - " + f.getName() + " (type: " + f.getType().getSimpleName() + ")");
                    }
                }
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("[AntiMod] Strategy 1 failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Strategy 2: Construct a SignBlockEntity and call getType() on it.
        // This avoids field-name issues entirely — we instantiate the NMS object
        // and let it return its own type.
        try {
            Class<?> signBE = Class.forName(
                    "net.minecraft.world.level.block.entity.SignBlockEntity", true, serverCL);
            Class<?> blockPosClass = Class.forName(
                    "net.minecraft.core.BlockPos", true, serverCL);
            Class<?> blockStateClass = Class.forName(
                    "net.minecraft.world.level.block.state.BlockState", true, serverCL);
            Class<?> blocksClass = Class.forName(
                    "net.minecraft.world.level.block.Blocks", true, serverCL);

            // Get BlockPos.ZERO
            Field zeroPosField = blockPosClass.getField("ZERO");
            Object zeroPos = zeroPosField.get(null);

            // Get Blocks.OAK_SIGN.defaultBlockState()
            Field oakSignField = blocksClass.getField("OAK_SIGN");
            Object oakSignBlock = oakSignField.get(null);
            Method defaultBlockState = oakSignBlock.getClass().getMethod("defaultBlockState");
            Object defaultState = defaultBlockState.invoke(oakSignBlock);

            // Construct SignBlockEntity(BlockPos, BlockState)
            java.lang.reflect.Constructor<?> ctor = signBE.getConstructor(blockPosClass, blockStateClass);
            Object signEntity = ctor.newInstance(zeroPos, defaultState);

            // Call getType() which returns BlockEntityType<SignBlockEntity>
            Method getType = signEntity.getClass().getMethod("getType");
            Object type = getType.invoke(signEntity);
            if (type != null) {
                cachedSignBlockEntityType = type;
                plugin.getLogger().info("[AntiMod] Resolved BlockEntityType.SIGN via SignBlockEntity.getType().");
                return cachedSignBlockEntityType;
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("[AntiMod] Strategy 2 (construct SignBlockEntity) failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                plugin.getLogger().warning("[AntiMod]   Caused by: "
                        + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
        }

        // Strategy 3: Registry lookup via BuiltInRegistries.BLOCK_ENTITY_TYPE.
        try {
            Class<?> registries = Class.forName(
                    "net.minecraft.core.registries.BuiltInRegistries", true, serverCL);
            Field regField = registries.getField("BLOCK_ENTITY_TYPE");
            Object registry = regField.get(null);

            Object signRL = null;
            Class<?> rlClass = Class.forName(
                    "net.minecraft.resources.ResourceLocation", true, serverCL);

            for (String methodName : new String[]{"parse", "withDefaultNamespace"}) {
                try {
                    Method m = rlClass.getMethod(methodName, String.class);
                    signRL = m.invoke(null,
                            methodName.equals("parse") ? "minecraft:sign" : "sign");
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (signRL == null) {
                try {
                    signRL = rlClass.getConstructor(String.class, String.class)
                            .newInstance("minecraft", "sign");
                } catch (NoSuchMethodException ignored) {}
            }

            if (signRL != null) {
                // Walk up the class hierarchy to find the getter method
                // (Registry extends MappedRegistry extends WritableRegistry etc.)
                for (String getter : new String[]{"getValue", "get", "getValueOrThrow"}) {
                    try {
                        Method m = findMethod(registry.getClass(), getter, rlClass);
                        if (m != null) {
                            m.setAccessible(true);
                            Object val = m.invoke(registry, signRL);
                            if (val != null) {
                                cachedSignBlockEntityType = val;
                                plugin.getLogger().info("[AntiMod] Resolved BlockEntityType.SIGN via registry (" + getter + ").");
                                return cachedSignBlockEntityType;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("[AntiMod] Strategy 3 (registry) failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Strategy 4: ProtocolLib MinecraftReflection + field scan by toString.
        try {
            Class<?> clazz = MinecraftReflection.getMinecraftClass(
                    "world.level.block.entity.BlockEntityType",
                    "world.level.block.entity.TileEntityTypes");
            for (Field f : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().equals(clazz)) continue;
                f.setAccessible(true);
                Object val = f.get(null);
                if (val != null) {
                    String str = val.toString().toLowerCase();
                    if (str.contains("sign") && !str.contains("hanging")) {
                        cachedSignBlockEntityType = val;
                        plugin.getLogger().info("[AntiMod] Resolved BlockEntityType.SIGN via field scan (field: " + f.getName() + ").");
                        return cachedSignBlockEntityType;
                    }
                }
            }
            plugin.getLogger().warning("[AntiMod] Strategy 4: class loaded but no sign field found. " +
                    "Fields of type " + clazz.getSimpleName() + ": " + countFields(clazz));
        } catch (Throwable e) {
            plugin.getLogger().warning("[AntiMod] Strategy 4 (field scan) failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        plugin.getLogger().severe("[AntiMod] All strategies to resolve BlockEntityType.SIGN failed! " +
                "Server: " + Bukkit.getVersion() + " / " + Bukkit.getBukkitVersion());
        return null;
    }

    /** Walk a class hierarchy to find a method by name and parameter type. */
    private static Method findMethod(Class<?> clazz, String name, Class<?> paramType) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramType);
            } catch (NoSuchMethodException ignored) {}
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            try {
                return iface.getDeclaredMethod(name, paramType);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    /** Count static fields of the same type as the class (diagnostic). */
    private static int countFields(Class<?> clazz) {
        int count = 0;
        for (Field f : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType().equals(clazz)) count++;
        }
        return count;
    }
}
