package com.faboit.antimod.violation;

import com.faboit.antimod.AntiMod;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Persists per-player, per-mod violation counts to a YAML file.
 *
 * Schema:
 *   <uuid>:
 *     name: last-known-player-name
 *     <mod-id>:
 *       count: 3
 *       last: 1750000000000  (epoch millis of most recent offense)
 */
public final class ViolationManager {

    private final AntiMod plugin;

    /**
     * YamlConfiguration is not thread-safe and detections can be resolved from
     * more than one thread (several region threads on Folia, or a probe
     * response racing an admin command). Every read and write of {@link #data}
     * goes through this lock so a concurrent update cannot lose a violation —
     * a lost increment means the wrong threshold is picked and the configured
     * command for that threshold never runs.
     */
    private final Object lock = new Object();

    private File file;
    private YamlConfiguration data;

    public ViolationManager(AntiMod plugin) {
        this.plugin = plugin;
    }

    public void load() {
        synchronized (lock) {
            loadLocked();
        }
    }

    private void loadLocked() {
        String fileName = plugin.getConfigManager().getStorageFile();
        this.file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create " + fileName, e);
            }
        }
        this.data = YamlConfiguration.loadConfiguration(file);
        pruneExpired();
    }

    public void save() {
        synchronized (lock) {
            saveLocked();
        }
    }

    private void saveLocked() {
        if (data == null || file == null) return;
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save violations file.", e);
        }
    }

    /**
     * Records a new offense and returns the new violation count for that mod.
     */
    public int recordViolation(UUID uuid, String playerName, String modId) {
        synchronized (lock) {
            return recordViolationLocked(uuid, playerName, modId);
        }
    }

    private int recordViolationLocked(UUID uuid, String playerName, String modId) {
        String base = uuid.toString();
        ConfigurationSection player = data.getConfigurationSection(base);
        if (player == null) player = data.createSection(base);
        player.set("name", playerName);

        ConfigurationSection mod = player.getConfigurationSection(modId);
        if (mod == null) mod = player.createSection(modId);
        int count = mod.getInt("count", 0) + 1;
        mod.set("count", count);
        mod.set("last", System.currentTimeMillis());
        saveLocked();
        return count;
    }

    public int getViolationCount(UUID uuid, String modId) {
        synchronized (lock) {
            return getViolationCountLocked(uuid, modId);
        }
    }

    private int getViolationCountLocked(UUID uuid, String modId) {
        ConfigurationSection player = data.getConfigurationSection(uuid.toString());
        if (player == null) return 0;
        ConfigurationSection mod = player.getConfigurationSection(modId);
        if (mod == null) return 0;
        return mod.getInt("count", 0);
    }

    public Map<String, Integer> getAllViolations(UUID uuid) {
        synchronized (lock) {
            return getAllViolationsLocked(uuid);
        }
    }

    private Map<String, Integer> getAllViolationsLocked(UUID uuid) {
        Map<String, Integer> result = new HashMap<>();
        ConfigurationSection player = data.getConfigurationSection(uuid.toString());
        if (player == null) return result;
        for (String modId : player.getKeys(false)) {
            if (modId.equals("name")) continue;
            ConfigurationSection mod = player.getConfigurationSection(modId);
            if (mod != null) {
                result.put(modId, mod.getInt("count", 0));
            }
        }
        return result;
    }

    public String getStoredName(UUID uuid) {
        synchronized (lock) {
            ConfigurationSection player = data.getConfigurationSection(uuid.toString());
            return player == null ? null : player.getString("name");
        }
    }

    public void clear(UUID uuid) {
        synchronized (lock) {
            data.set(uuid.toString(), null);
            saveLocked();
        }
    }

    public void clear(UUID uuid, String modId) {
        synchronized (lock) {
            ConfigurationSection player = data.getConfigurationSection(uuid.toString());
            if (player == null) return;
            player.set(modId, null);
            saveLocked();
        }
    }

    /**
     * Removes entries older than reset-after-days. Callers must hold {@link #lock}.
     */
    private void pruneExpired() {
        int resetDays = plugin.getConfigManager().getResetAfterDays();
        if (resetDays <= 0) return;
        long cutoff = System.currentTimeMillis() - (resetDays * 24L * 60L * 60L * 1000L);

        Set<String> playerKeys = new HashSet<>(data.getKeys(false));
        for (String uuidKey : playerKeys) {
            ConfigurationSection player = data.getConfigurationSection(uuidKey);
            if (player == null) continue;
            Set<String> modKeys = new HashSet<>(player.getKeys(false));
            for (String key : modKeys) {
                if (key.equals("name")) continue;
                ConfigurationSection mod = player.getConfigurationSection(key);
                if (mod == null) continue;
                long last = mod.getLong("last", 0);
                if (last > 0 && last < cutoff) {
                    player.set(key, null);
                }
            }
            // Remove player entirely if only the name entry remains
            Set<String> remaining = new HashSet<>(player.getKeys(false));
            remaining.remove("name");
            if (remaining.isEmpty()) {
                data.set(uuidKey, null);
            }
        }
        saveLocked();
    }
}
