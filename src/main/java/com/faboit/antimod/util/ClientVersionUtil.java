package com.faboit.antimod.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Resolves the protocol version a player's client actually speaks.
 *
 * With ViaVersion on the server, the connection is translated, so ViaVersion
 * is asked first; PacketEvents' view of the client is the fallback. ViaVersion
 * is reached by reflection to avoid a compile-time dependency.
 */
public final class ClientVersionUtil {

    /** 1.19.4: the first release whose translate components honour "fallback". */
    private static final int PROTOCOL_1_19_4 = 762;

    private static volatile boolean viaResolved;
    private static Object viaApi;
    private static Method viaGetPlayerVersion;

    /** The client's protocol version, or -1 if it cannot be determined. */
    public static int getProtocolVersion(Player player) {
        int via = viaVersion(player.getUniqueId());
        if (via > 0) return via;
        try {
            ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
            if (version != null && version != ClientVersion.UNKNOWN) return version.getProtocolVersion();
        } catch (Exception ignored) {
            // Fall through to "unknown".
        }
        return -1;
    }

    /**
     * Whether the client renders a translate component's fallback for an
     * unknown key. Older clients show the raw key instead, so a raw key from
     * them is the vanilla answer, not a sign of tampering. Unknown counts as
     * "no", which only ever makes the plugin more lenient.
     */
    public static boolean supportsTranslationFallback(Player player) {
        return getProtocolVersion(player) >= PROTOCOL_1_19_4;
    }

    private static int viaVersion(UUID uuid) {
        if (!viaResolved) resolveVia();
        if (viaApi == null) return -1;
        try {
            return (int) viaGetPlayerVersion.invoke(viaApi, uuid);
        } catch (Exception e) {
            return -1;
        }
    }

    private static synchronized void resolveVia() {
        if (viaResolved) return;
        try {
            if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                Object api = via.getMethod("getAPI").invoke(null);
                // Through the public interface: the implementation class need not be public.
                viaGetPlayerVersion = Class.forName("com.viaversion.viaversion.api.ViaAPI")
                        .getMethod("getPlayerVersion", UUID.class);
                viaApi = api;
            }
        } catch (Exception ignored) {
            viaApi = null;
        }
        viaResolved = true;
    }

    private ClientVersionUtil() {}
}
