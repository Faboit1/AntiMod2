package com.faboit.antimod.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Utility for checking if a player is a Floodgate (Bedrock) player.
 * Uses reflection to avoid compile-time dependency on Floodgate.
 * Floodgate players can now be probed just like Java players.
 */
public final class FloodgateUtil {

    private static Boolean floodgateAvailable = null;
    private static Method isFloodgatePlayerMethod = null;
    private static Object floodgateApiInstance = null;

    static {
        initFloodgate();
    }

    private static void initFloodgate() {
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
                floodgateAvailable = false;
                return;
            }

            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            floodgateApiInstance = getInstance.invoke(null);

            if (floodgateApiInstance == null) {
                floodgateAvailable = false;
                return;
            }

            isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            floodgateAvailable = true;
        } catch (Exception ignored) {
            floodgateAvailable = false;
        }
    }

    /**
     * Checks if Floodgate is installed and available.
     */
    public static boolean isFloodgateAvailable() {
        return floodgateAvailable != null && floodgateAvailable;
    }

    /**
     * Checks if a player is a Floodgate (Bedrock) player.
     */
    public static boolean isFloodgatePlayer(Player player) {
        if (!isFloodgateAvailable()) return false;
        try {
            return (boolean) isFloodgatePlayerMethod.invoke(floodgateApiInstance, player.getUniqueId());
        } catch (Exception ignored) {
            return false;
        }
    }

    private FloodgateUtil() {}
}
