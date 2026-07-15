package com.faboit.antimod.config;

import com.faboit.antimod.AntiMod;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and holds the plugin configuration. All settings live in config.yml
 * and are parsed into strongly-typed classes here.
 */
public final class ConfigManager {

    private final AntiMod plugin;

    // Detection
    private boolean onJoinEnabled;
    private int joinDelayTicks;
    private boolean periodicEnabled;
    private int periodicIntervalSeconds;
    private int responseTimeoutTicks;
    private int signYOffset;
    private String fallbackMarker;
    private int closeDelayTicks;
    private int keysPerSign;

    // Notifications
    private boolean consoleLog;
    private boolean staffBroadcast;
    private String staffMessage;
    private String consoleMessage;

    // Storage
    private String storageFile;
    private int resetAfterDays;

    // Mods (preserved insertion order)
    private Map<String, ModConfig> mods = Collections.emptyMap();

    public ConfigManager(AntiMod plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // --- detection ---
        onJoinEnabled = cfg.getBoolean("detection.on-join", true);
        joinDelayTicks = cfg.getInt("detection.join-delay-ticks", 0);
        periodicEnabled = cfg.getBoolean("detection.periodic.enabled", false);
        periodicIntervalSeconds = cfg.getInt("detection.periodic.interval", 600);
        responseTimeoutTicks = cfg.getInt("detection.response-timeout-ticks", 100);
        signYOffset = cfg.getInt("detection.sign-y-offset", 100);
        fallbackMarker = cfg.getString("detection.fallback-marker", "am_fb");
        closeDelayTicks = cfg.getInt("detection.close-delay-ticks", 0);
        keysPerSign = Math.max(1, Math.min(4, cfg.getInt("detection.keys-per-sign", 1)));

        // --- notifications ---
        consoleLog = cfg.getBoolean("notifications.console-log", true);
        staffBroadcast = cfg.getBoolean("notifications.staff-broadcast", true);
        staffMessage = cfg.getString("notifications.staff-message",
                "<red>[AntiMod]</red> <yellow><player></yellow> detected <red><mod></red>");
        consoleMessage = cfg.getString("notifications.console-message",
                "[AntiMod] {player} detected {mod} (violation #{violations}, action: {action})");

        // --- storage ---
        storageFile = cfg.getString("storage.file", "violations.yml");
        resetAfterDays = cfg.getInt("storage.reset-after-days", 30);

        // --- mods ---
        Map<String, ModConfig> modMap = new LinkedHashMap<>();
        ConfigurationSection modsSection = cfg.getConfigurationSection("mods");
        if (modsSection != null) {
            for (String modId : modsSection.getKeys(false)) {
                ConfigurationSection m = modsSection.getConfigurationSection(modId);
                if (m == null) continue;

                String displayName = m.getString("display-name", modId);
                boolean enabled = m.getBoolean("enabled", true);
                List<String> keys = m.getStringList("keys");

                List<ActionConfig> actions = new ArrayList<>();
                List<Map<?, ?>> actionMaps = m.getMapList("actions");
                for (Map<?, ?> actionMap : actionMaps) {
                    int threshold = coerceInt(actionMap.get("threshold"), 1);
                    List<String> commands = coerceStringList(actionMap.get("commands"));
                    String broadcast = coerceString(actionMap.get("broadcast"));
                    boolean staffOnly = coerceBoolean(actionMap.get("staff-only"), false);
                    String message = coerceString(actionMap.get("message"));
                    actions.add(new ActionConfig(threshold, commands, broadcast, staffOnly, message));
                }

                modMap.put(modId, new ModConfig(modId, displayName, enabled, keys, actions));
            }
        }
        this.mods = Collections.unmodifiableMap(modMap);
    }

    // --- coercion helpers (YAML map values are Object) ---

    private static int coerceInt(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static boolean coerceBoolean(Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    private static String coerceString(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> coerceStringList(Object o) {
        if (o instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        return Collections.emptyList();
    }

    // ---------- getters ----------

    public boolean isOnJoinEnabled() { return onJoinEnabled; }
    public int getJoinDelayTicks() { return joinDelayTicks; }
    public boolean isPeriodicEnabled() { return periodicEnabled; }
    public int getPeriodicIntervalSeconds() { return periodicIntervalSeconds; }
    public int getResponseTimeoutTicks() { return responseTimeoutTicks; }
    public int getSignYOffset() { return signYOffset; }
    public String getFallbackMarker() { return fallbackMarker; }
    public int getCloseDelayTicks() { return closeDelayTicks; }
    public int getKeysPerSign() { return keysPerSign; }

    public boolean isConsoleLog() { return consoleLog; }
    public boolean isStaffBroadcast() { return staffBroadcast; }
    public String getStaffMessage() { return staffMessage; }
    public String getConsoleMessage() { return consoleMessage; }

    public String getStorageFile() { return storageFile; }
    public int getResetAfterDays() { return resetAfterDays; }

    public Map<String, ModConfig> getMods() { return mods; }

    public ModConfig getMod(String id) { return mods.get(id); }
}
