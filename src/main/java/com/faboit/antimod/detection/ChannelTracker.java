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
 * connect, which makes a registered channel hard evidence that the mod is
 * installed. The packets are only read, never altered or cancelled.
 */
public final class ChannelTracker extends PacketListenerAbstract {

    private static final String REGISTER = "minecraft:register";
    private static final String LEGACY_REGISTER = "REGISTER";

    private final AntiMod plugin;
    private final Map<UUID, Set<String>> channels = new ConcurrentHashMap<>();
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
            if (!REGISTER.equals(channel) && !LEGACY_REGISTER.equals(channel)) return;
            if (data == null || data.length == 0) return;

            Set<String> set = channels.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
            for (String c : new String(data, StandardCharsets.UTF_8).split("\0")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty()) set.add(trimmed);
            }

            if (play) {
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

    public boolean hasPlayRegistration(UUID uuid) {
        return playRegistered.contains(uuid);
    }

    public void forget(UUID uuid) {
        channels.remove(uuid);
        playRegistered.remove(uuid);
    }
}
