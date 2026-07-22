package com.faboit.antimod.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Utility for checking if a player is a Floodgate (Bedrock) player.
 * Uses reflection to avoid compile-time dependency on Floodgate.
 * Floodgate players can now be probed just like Java players.
 */
public final class FloodgateUtil {

    private static Boolean floodgateAvailable = null;
    private static Method isFloodgatePlayerMethod = null;
    private static Object floodgateApiInstance = null;
    private static String initError = null;

    static {
        initFloodgate();
    }

    private static void initFloodgate() {
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
                initError = "Floodgate plugin not found";
                floodgateAvailable = false;
                return;
            }

            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            floodgateApiInstance = getInstance.invoke(null);

            if (floodgateApiInstance == null) {
                initError = "FloodgateApi.getInstance() returned null";
                floodgateAvailable = false;
                return;
            }

            isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            floodgateAvailable = true;
            Bukkit.getLogger().log(Level.INFO, "[AntiMod] Floodgate support initialized successfully");
        } catch (ClassNotFoundException e) {
            initError = "FloodgateApi class not found: " + e.getMessage();
            floodgateAvailable = false;
            Bukkit.getLogger().log(Level.WARNING, "[AntiMod] Floodgate API class not available: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            initError = "isFloodgatePlayer method not found: " + e.getMessage();
            floodgateAvailable = false;
            Bukkit.getLogger().log(Level.WARNING, "[AntiMod] Floodgate isFloodgatePlayer method not found: " + e.getMessage());
        } catch (Exception e) {
            initError = "Unexpected error during initialization: " + e.getMessage();
            floodgateAvailable = false;
            Bukkit.getLogger().log(Level.WARNING, "[AntiMod] Unexpected error initializing Floodgate support", e);
        }
    }

    /**
     * Checks if Floodgate is installed and available.
     */
    public static boolean isFloodgateAvailable() {
        return floodgateAvailable != null && floodgateAvailable;
    }

    /**
     * Gets the last initialization error, if any.
     */
    public static String getInitError() {
        return initError;
    }

    /**
     * Checks if a player is a Floodgate (Bedrock) player.
     */
    public static boolean isFloodgatePlayer(Player player) {
        if (!isFloodgateAvailable() || player == null) return false;
        try {
            UUID playerUuid = player.getUniqueId();
            if (playerUuid == null) return false;
            Object result = isFloodgatePlayerMethod.invoke(floodgateApiInstance, playerUuid);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "[AntiMod] Error checking Floodgate player status for " + player.getName(), e);
            return false;
        }
    }

    private FloodgateUtil() {}
}
