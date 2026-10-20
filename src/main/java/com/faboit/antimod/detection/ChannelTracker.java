package com.faboit.antimod.detection;

import com.faboit.antimod.AntiMod;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Records every plugin channel a client announces through
 * {@code minecraft:register}, in both the configuration and the play phase.
 *
 * Modded clients register the channels their mods listen on as soon as they
 * connect, which makes a registered channel evidence that the mod is
 * installed - but only once channels that did not come from the client are
 * set aside. A proxy registers its own plugins' channels on the backend for
 * every player (Velocity does this for e.g. voicechat-velocity's
 * "voicechat:secret"), so those reach this server looking exactly like the
 * client's. They are dropped from the spoof check by the configured
 * ignore-channels patterns and by learning: a vanilla client never registers
 * anything, so every channel a vanilla-brand connection carries was added on
 * the way and is ignored for all players from then on.
 *
 * The packets are only read, never altered or cancelled.
 */
public final class ChannelTracker extends PacketListenerAbstract {

    private static final String REGISTER = "minecraft:register";
    private static final String LEGACY_REGISTER = "REGISTER";
    private static final String BRAND = "minecraft:brand";
    private static final String LEGACY_BRAND = "MC|Brand";

    private final AntiMod plugin;
    private final Map<UUID, Set<String>> channels = new ConcurrentHashMap<>();
    private final Map<UUID, String> brands = new ConcurrentHashMap<>();
    /** Channels seen on vanilla-brand connections, so not registered by any client mod. */
    private final Set<String> learnedProxyChannels = ConcurrentHashMap.newKeySet();
    /** Players whose client has sent a play-phase registration. */
    private final Set<UUID> playRegistered = ConcurrentHashMap.newKeySet();

    /** Called (on a Netty thread) whenever a play-phase registration arrives. */
    private volatile Consumer<UUID> playRegistrationListener = uuid -> {};

    public ChannelTracker(AntiMod plugin) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
    }

    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    public void setPlayRegistrationListener(Consumer<UUID> listener) {
        this.playRegistrationListener = listener == null ? uuid -> {} : listener;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        boolean play = event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE;
        boolean config = event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE;
        if (!play && !config) return;

        try {
            User user = event.getUser();
            UUID uuid = user == null ? null : user.getUUID();
            if (uuid == null) return;

            String channel;
            byte[] data;
            if (play) {
                WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
                channel = packet.getChannelName();
                data = packet.getData();
            } else {
                WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
                channel = packet.getChannelName();
                data = packet.getData();
            }
            if (data == null || data.length == 0) return;
            if (BRAND.equals(channel) || LEGACY_BRAND.equals(channel)) {
                String brand = readBrand(data);
                if (brand == null) return;
                brands.put(uuid, brand);
                if (isVanilla(brand)) learn(channels.getOrDefault(uuid, Set.of()));
                return;
            }
            if (!REGISTER.equals(channel) && !LEGACY_REGISTER.equals(channel)) return;

            Set<String> set = channels.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
            Set<String> added = new TreeSet<>();
            for (String c : new String(data, StandardCharsets.UTF_8).split("\0")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty() && set.add(trimmed)) added.add(trimmed);
            }
            if (isVanilla(brands.get(uuid))) learn(added);

            // Only the client's own announcement may start the join probe. A
            // proxy registers its channels on the backend first, and timing
            // the probe off that would send it before the client's channels
            // arrive.
            boolean fromClient = added.stream().anyMatch(c -> whyIgnored(c) == null);
            if (play && fromClient) {
                playRegistered.add(uuid);
                playRegistrationListener.accept(uuid);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "Could not read a channel registration.", e);
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        User user = event.getUser();
        if (user != null && user.getUUID() != null) forget(user.getUUID());
    }

    /**
     * Every channel the player's client has announced, merged with what the
     * server itself recorded. Sorted for stable output.
     */
    public Set<String> getChannels(Player player) {
        Set<String> result = new TreeSet<>(channels.getOrDefault(player.getUniqueId(), Collections.emptySet()));
        try {
            result.addAll(player.getListeningPluginChannels());
        } catch (Exception ignored) {
            // Not available on every server fork; our own record is enough.
        }
        return result;
    }

    /**
     * The channels the spoof check may treat as proof of an installed mod:
     * {@link #getChannels} minus ignore-channels and learned proxy channels.
     * Always empty for a vanilla-brand client, whose channels can only have
     * been added by the proxy or server.
     */
    public Set<String> getSpoofCheckChannels(Player player) {
        if (isVanillaBrand(player)) return Collections.emptySet();
        Set<String> result = getChannels(player);
        result.removeAll(learnedProxyChannels);
        result.removeIf(plugin.getConfigManager()::isSpoofIgnoredChannel);
        return result;
    }

    /** Whether the channel is excluded from the spoof check, and why (null if it is not). */
    public String whyIgnored(String channel) {
        if (plugin.getConfigManager().isSpoofIgnoredChannel(channel)) return "ignore-channels";
        if (learnedProxyChannels.contains(channel)) return "added by proxy/server";
        return null;
    }

    public boolean isVanillaBrand(Player player) {
        String brand = brands.get(player.getUniqueId());
        if (brand == null) brand = player.getClientBrandName();
        return isVanilla(brand);
    }

    public boolean hasPlayRegistration(UUID uuid) {
        return playRegistered.contains(uuid);
    }

    public void forget(UUID uuid) {
        channels.remove(uuid);
        brands.remove(uuid);
        playRegistered.remove(uuid);
    }

    private void learn(Set<String> carried) {
        if (!plugin.getConfigManager().isSpoofLearnProxyChannels()) return;
        for (String c : carried) {
            if (plugin.getConfigManager().isSpoofIgnoredChannel(c)) continue;
            if (learnedProxyChannels.add(c)) {
                plugin.getLogger().info("Spoof check: ignoring channel '" + c + "' - a vanilla client carried it, "
                        + "so the proxy or server registers it for every player.");
            }
        }
    }

    private static boolean isVanilla(String brand) {
        return brand != null && brand.trim().equalsIgnoreCase("vanilla");
    }

    /** A brand payload is one VarInt-length-prefixed UTF-8 string. */
    private static String readBrand(byte[] data) {
        int length = 0, shift = 0, i = 0;
        while (i < data.length && i < 5) {
            byte b = data[i++];
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (length < 0 || i + length > data.length) return null;
                return new String(data, i, length, StandardCharsets.UTF_8);
            }
            shift += 7;
        }
        return null;
    }
}
