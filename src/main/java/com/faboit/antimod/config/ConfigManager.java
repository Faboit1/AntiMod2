package com.faboit.antimod.config;

import com.faboit.antimod.AntiMod;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
    private boolean floodgateEnabled;

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

    // Spoof detection
    public static final String SPOOF_MODS_FILE = "spoof-mods.yml";
    private boolean spoofEnabled;
    private boolean spoofWaitForChannels;
    private int spoofChannelWaitTicks;
    private int spoofChannelSettleTicks;
    private int spoofMinHiddenMods;
    private int spoofMaxModsPerSweep;
    private boolean spoofFlagRawKeyEcho;
    private boolean spoofLearnProxyChannels;
    private int spoofAutoDisableAfter;
    private List<Pattern> spoofIgnoredChannels = Collections.emptyList();
    private ModConfig spoofDetector;
    /** Swapped whole on reload; read from packet threads. */
    private volatile Map<String, SpoofModConfig> spoofMods = Collections.emptyMap();

    // Mod scan (/antimod mods)
    private volatile ModCatalog catalog = ModCatalog.empty();
    private int scanKeysPerLine;
    private int scanSignsPerTick;
    private int scanSlowSignsPerTick;
    private int scanResponseTimeoutTicks;

    // --- pack check (/antimod check <player> --pack) ---
    private boolean packCheckEnabled;
    private String packCheckBind;
    private int packCheckPort;
    private String packCheckPublicUrl;
    private int packCheckRedirects;
    private String packCheckPrompt;
    private int packCheckTimeoutSeconds;
    private boolean packCheckRunActions;
    private boolean packCheckOnJoin;
    private boolean packCheckOnJoinRequired;
    private int packCheckOnJoinDelayTicks;
    private boolean packCheckOnJoinKeepPack;

    // --- teleport check (Autism Client) ---
    private boolean teleportCheckEnabled;
    private boolean teleportCheckOnJoin;
    private int teleportCheckDelayTicks;
    private int teleportCheckTimeoutTicks;
    private boolean teleportCheckRunActions;
    private ModConfig teleportDetector;

    public ConfigManager(AntiMod plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // --- detection ---
        onJoinEnabled = cfg.getBoolean("detection.on-join", true);
        joinDelayTicks = Math.max(0, cfg.getInt("detection.join-delay-ticks", 0));
        periodicEnabled = cfg.getBoolean("detection.periodic.enabled", false);
        periodicIntervalSeconds = Math.max(1, cfg.getInt("detection.periodic.interval", 600));
        // At least one tick: a zero/negative window would retire every sweep
        // before the client could possibly answer it.
        responseTimeoutTicks = Math.max(1, cfg.getInt("detection.response-timeout-ticks", 100));
        // At least one block away, so a phantom sign never lands on the
        // player's own block.
        signYOffset = Math.max(1, cfg.getInt("detection.sign-y-offset", 100));
        fallbackMarker = cfg.getString("detection.fallback-marker", "am_fb");
        closeDelayTicks = Math.max(0, cfg.getInt("detection.close-delay-ticks", 0));
        keysPerSign = Math.max(1, Math.min(4, cfg.getInt("detection.keys-per-sign", 1)));
        floodgateEnabled = cfg.getBoolean("detection.floodgate-enabled", true);

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

                modMap.put(modId, new ModConfig(modId, displayName, enabled, keys, parseActions(m)));
            }
        }
        this.mods = Collections.unmodifiableMap(modMap);

        // --- spoof detection ---
        spoofEnabled = cfg.getBoolean("spoof-detection.enabled", true);
        spoofWaitForChannels = cfg.getBoolean("spoof-detection.wait-for-channels", true);
        spoofChannelWaitTicks = Math.max(0, cfg.getInt("spoof-detection.channel-wait-ticks", 20));
        spoofChannelSettleTicks = Math.max(0, cfg.getInt("spoof-detection.channel-settle-ticks", 2));
        spoofMinHiddenMods = Math.max(1, cfg.getInt("spoof-detection.min-hidden-mods", 2));
        spoofMaxModsPerSweep = Math.max(1, cfg.getInt("spoof-detection.max-mods-per-sweep", 8));
        spoofFlagRawKeyEcho = cfg.getBoolean("spoof-detection.flag-raw-key-echo", true);
        spoofLearnProxyChannels = cfg.getBoolean("spoof-detection.learn-proxy-channels", true);
        spoofAutoDisableAfter = Math.max(0, cfg.getInt("spoof-detection.auto-disable-after", 5));
        List<Pattern> ignored = new ArrayList<>();
        for (String glob : cfg.getStringList("spoof-detection.ignore-channels")) {
            ignored.add(SpoofModConfig.compileGlob(glob));
        }
        spoofIgnoredChannels = List.copyOf(ignored);
        ConfigurationSection spoofSection = cfg.getConfigurationSection("spoof-detection");
        spoofDetector = new ModConfig(
                cfg.getString("spoof-detection.id", "translation-spoofer"),
                cfg.getString("spoof-detection.display-name", "Translation Spoofer"),
                spoofEnabled,
                Collections.emptyList(),
                spoofSection == null ? Collections.emptyList() : parseActions(spoofSection));
        spoofMods = loadSpoofMods();

        // --- mod scan ---
        scanKeysPerLine = Math.max(1, Math.min(3, cfg.getInt("mod-scan.keys-per-line", 3)));
        scanSignsPerTick = Math.max(0, cfg.getInt("mod-scan.signs-per-tick", 0));
        scanSlowSignsPerTick = Math.max(1, cfg.getInt("mod-scan.slow-signs-per-tick", 2));
        scanResponseTimeoutTicks = Math.max(1, cfg.getInt("mod-scan.response-timeout-ticks", 100));

        // --- pack check ---
        packCheckEnabled = cfg.getBoolean("pack-check.enabled", true);
        packCheckBind = cfg.getString("pack-check.bind", "0.0.0.0");
        packCheckPort = cfg.getInt("pack-check.port", 0);
        packCheckPublicUrl = cfg.getString("pack-check.public-url", "").trim();
        packCheckRedirects = Math.max(1, Math.min(30, cfg.getInt("pack-check.redirects", 19)));
        packCheckPrompt = cfg.getString("pack-check.prompt", "").replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
        packCheckTimeoutSeconds = Math.max(5, cfg.getInt("pack-check.timeout-seconds", 60));
        packCheckRunActions = cfg.getBoolean("pack-check.run-actions", false);
        packCheckOnJoin = cfg.getBoolean("pack-check.on-join.enabled", false);
        packCheckOnJoinRequired = cfg.getBoolean("pack-check.on-join.required", false);
        packCheckOnJoinDelayTicks = Math.max(0, cfg.getInt("pack-check.on-join.delay-ticks", 20));
        packCheckOnJoinKeepPack = cfg.getBoolean("pack-check.on-join.keep-pack", true);

        // --- teleport check ---
        teleportCheckEnabled = cfg.getBoolean("teleport-check.enabled", true);
        teleportCheckOnJoin = cfg.getBoolean("teleport-check.on-join", true);
        teleportCheckDelayTicks = Math.max(0, cfg.getInt("teleport-check.delay-ticks", 40));
        teleportCheckTimeoutTicks = Math.max(20, cfg.getInt("teleport-check.timeout-ticks", 100));
        teleportCheckRunActions = cfg.getBoolean("teleport-check.run-actions", false);
        ConfigurationSection teleportSection = cfg.getConfigurationSection("teleport-check");
        teleportDetector = new ModConfig(
                cfg.getString("teleport-check.id", "autism-client"),
                cfg.getString("teleport-check.display-name", "Autism Client"),
                teleportCheckEnabled,
                Collections.emptyList(),
                teleportSection == null ? Collections.emptyList() : parseActions(teleportSection));
        File catalogFile = new File(plugin.getDataFolder(), ModCatalog.FILE);
        if (!catalogFile.exists()) {
            plugin.saveResource(ModCatalog.FILE, false);
        }
        catalog = ModCatalog.load(catalogFile);
    }

    private static List<ActionConfig> parseActions(ConfigurationSection section) {
        List<ActionConfig> actions = new ArrayList<>();
        for (Map<?, ?> actionMap : section.getMapList("actions")) {
            int threshold = coerceInt(actionMap.get("threshold"), 1);
            List<String> commands = coerceStringList(actionMap.get("commands"));
            String broadcast = coerceString(actionMap.get("broadcast"));
            boolean staffOnly = coerceBoolean(actionMap.get("staff-only"), false);
            String message = coerceString(actionMap.get("message"));
            actions.add(new ActionConfig(threshold, commands, broadcast, staffOnly, message));
        }
        return actions;
    }

    /** Re-reads spoof-mods.yml alone, e.g. after an entry was auto-disabled. */
    public void reloadSpoofMods() {
        spoofMods = loadSpoofMods();
    }

    /**
     * Loads the channel/key cross-check list from spoof-mods.yml, writing the
     * bundled default the first time.
     */
    private Map<String, SpoofModConfig> loadSpoofMods() {
        File file = new File(plugin.getDataFolder(), SPOOF_MODS_FILE);
        if (!file.exists()) {
            plugin.saveResource(SPOOF_MODS_FILE, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        Map<String, SpoofModConfig> result = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("mods");
        if (section == null) return Collections.emptyMap();
        for (String id : section.getKeys(false)) {
            ConfigurationSection m = section.getConfigurationSection(id);
            if (m == null) continue;
            List<String> channels = m.getStringList("channels");
            List<String> keys = m.getStringList("keys");
            if (channels.isEmpty() || keys.isEmpty()) {
                plugin.getLogger().warning(SPOOF_MODS_FILE + ": '" + id
                        + "' needs at least one channel and one key - skipped.");
                continue;
            }
            result.put(id, new SpoofModConfig(id,
                    m.getString("display-name", id),
                    m.getBoolean("enabled", true),
                    m.getBoolean("trusted", false),
                    channels,
                    keys));
        }
        return Collections.unmodifiableMap(result);
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
    public boolean isFloodgateEnabled() { return floodgateEnabled; }

    public boolean isConsoleLog() { return consoleLog; }
    public boolean isStaffBroadcast() { return staffBroadcast; }
    public String getStaffMessage() { return staffMessage; }
    public String getConsoleMessage() { return consoleMessage; }

    public String getStorageFile() { return storageFile; }
    public int getResetAfterDays() { return resetAfterDays; }

    public Map<String, ModConfig> getMods() { return mods; }

    /**
     * Looks up a banned mod by id. The spoof detector is resolvable here too,
     * so its violations show up and clear like any other mod's.
     */
    public ModConfig getMod(String id) {
        ModConfig mod = mods.get(id);
        if (mod == null && spoofDetector != null && spoofDetector.getId().equals(id)) {
            return spoofDetector;
        }
        if (mod == null && teleportDetector != null && teleportDetector.getId().equals(id)) {
            return teleportDetector;
        }
        return mod;
    }

    public boolean isSpoofEnabled() { return spoofEnabled; }
    public boolean isSpoofWaitForChannels() { return spoofWaitForChannels; }
    public int getSpoofChannelWaitTicks() { return spoofChannelWaitTicks; }
    public int getSpoofChannelSettleTicks() { return spoofChannelSettleTicks; }
    public int getSpoofMinHiddenMods() { return spoofMinHiddenMods; }
    public int getSpoofMaxModsPerSweep() { return spoofMaxModsPerSweep; }
    public boolean isSpoofFlagRawKeyEcho() { return spoofFlagRawKeyEcho; }
    public boolean isSpoofLearnProxyChannels() { return spoofLearnProxyChannels; }
    public int getSpoofAutoDisableAfter() { return spoofAutoDisableAfter; }

    /** Whether a registered channel matches spoof-detection.ignore-channels. */
    public boolean isSpoofIgnoredChannel(String channel) {
        String lower = channel.toLowerCase(Locale.ROOT);
        for (Pattern p : spoofIgnoredChannels) {
            if (p.matcher(lower).matches()) return true;
        }
        return false;
    }
    public ModConfig getSpoofDetector() { return spoofDetector; }
    public ModCatalog getCatalog() { return catalog; }
    public int getScanKeysPerLine() { return scanKeysPerLine; }
    public int getScanSignsPerTick() { return scanSignsPerTick; }
    public int getScanSlowSignsPerTick() { return scanSlowSignsPerTick; }
    public int getScanResponseTimeoutTicks() { return scanResponseTimeoutTicks; }

    public boolean isPackCheckEnabled() { return packCheckEnabled; }
    public String getPackCheckBind() { return packCheckBind; }
    public int getPackCheckPort() { return packCheckPort; }
    public String getPackCheckPublicUrl() { return packCheckPublicUrl; }
    public int getPackCheckRedirects() { return packCheckRedirects; }
    public String getPackCheckPrompt() { return packCheckPrompt; }
    public int getPackCheckTimeoutSeconds() { return packCheckTimeoutSeconds; }
    public boolean isPackCheckRunActions() { return packCheckRunActions; }
    public boolean isPackCheckOnJoin() { return packCheckEnabled && packCheckOnJoin; }
    public boolean isPackCheckOnJoinRequired() { return packCheckOnJoinRequired; }
    public int getPackCheckOnJoinDelayTicks() { return packCheckOnJoinDelayTicks; }
    public boolean isPackCheckOnJoinKeepPack() { return packCheckOnJoinKeepPack; }

    public boolean isTeleportCheckEnabled() { return teleportCheckEnabled; }
    public boolean isTeleportCheckOnJoin() { return teleportCheckEnabled && teleportCheckOnJoin; }
    public int getTeleportCheckDelayTicks() { return teleportCheckDelayTicks; }
    public int getTeleportCheckTimeoutTicks() { return teleportCheckTimeoutTicks; }
    public boolean isTeleportCheckRunActions() { return teleportCheckRunActions; }
    public ModConfig getTeleportDetector() { return teleportDetector; }
    public Map<String, SpoofModConfig> getSpoofMods() { return spoofMods; }
}
