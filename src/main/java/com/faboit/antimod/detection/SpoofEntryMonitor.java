package com.faboit.antimod.detection;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Retires spoof-mods.yml entries that turn out to be unreliable.
 *
 * When a player verifies every other channel-confirmed mod in a sweep but one
 * entry's keys all fail, that player is clearly not hiding translations, so
 * the entry itself is suspect (a mod build without the listed keys, or a
 * channel some other mod or plugin also uses). Once that happens for
 * {@code auto-disable-after} different players, the entry is commented out in
 * spoof-mods.yml and the list is reloaded. The tallies are kept in
 * spoof-mod-misses.yml so they survive restarts.
 */
public final class SpoofEntryMonitor {

    private static final String FILE = "spoof-mod-misses.yml";

    private final AntiMod plugin;
    private final Object lock = new Object();
    private final File file;
    private YamlConfiguration data;

    public SpoofEntryMonitor(AntiMod plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
        this.data = YamlConfiguration.loadConfiguration(file);
        // Entry ids are used as keys verbatim; a dot in one must not nest it.
        this.data.options().pathSeparator('\u0001');
    }

    /**
     * Records that {@code entryId} failed for a player who verified every
     * other probed mod. Does its file work off the calling thread.
     */
    public void recordMiss(String entryId, UUID player) {
        int threshold = plugin.getConfigManager().getSpoofAutoDisableAfter();
        if (threshold <= 0) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            synchronized (lock) {
                Set<String> players = new LinkedHashSet<>(data.getStringList(entryId));
                if (!players.add(player.toString())) return;
                data.set(entryId, new ArrayList<>(players));
                save();
                plugin.getLogger().info("Spoof check: '" + entryId + "' failed for a player who verified every "
                        + "other mod (" + players.size() + "/" + threshold + " players).");
                if (players.size() >= threshold && disable(entryId, players.size())) {
                    data.set(entryId, null);
                    save();
                }
            }
        });
    }

    /** Comments the entry out of spoof-mods.yml and reloads the list. */
    private boolean disable(String entryId, int players) {
        File modsFile = new File(plugin.getDataFolder(), ConfigManager.SPOOF_MODS_FILE);
        try {
            List<String> lines = Files.readAllLines(modsFile.toPath(), StandardCharsets.UTF_8);
            List<String> out = commentOut(lines, entryId, players);
            if (out == null) {
                plugin.getLogger().warning("Spoof check: '" + entryId + "' keeps failing for players who verified "
                        + "every other mod, but its block could not be found in " + ConfigManager.SPOOF_MODS_FILE
                        + ". Set 'enabled: false' on it by hand.");
                return false;
            }
            Files.write(modsFile.toPath(), out, StandardCharsets.UTF_8);
            plugin.getConfigManager().reloadSpoofMods();
            plugin.getLogger().warning("Spoof check: disabled '" + entryId + "' in " + ConfigManager.SPOOF_MODS_FILE
                    + " - it failed for " + players + " different players who verified every other mod.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not disable '" + entryId + "' in "
                    + ConfigManager.SPOOF_MODS_FILE, e);
            return false;
        }
    }

    /**
     * Prefixes the entry's block (its key line and everything indented below
     * it) with "# ", under a note saying why. Returns null if not found.
     */
    static List<String> commentOut(List<String> lines, String entryId, int players) {
        Pattern header = Pattern.compile("^(\\s+)[\"']?" + Pattern.quote(entryId) + "[\"']?\\s*:\\s*(#.*)?$");
        int start = -1;
        String indent = "";
        for (int i = 0; i < lines.size(); i++) {
            var m = header.matcher(lines.get(i));
            if (m.matches()) {
                start = i;
                indent = m.group(1);
                break;
            }
        }
        if (start < 0) return null;

        int end = start + 1;
        while (end < lines.size()) {
            String line = lines.get(end);
            if (!line.isBlank() && leading(line) <= indent.length()) break;
            end++;
        }
        // Leave trailing blank lines outside the commented block.
        while (end > start + 1 && lines.get(end - 1).isBlank()) end--;

        List<String> out = new ArrayList<>(lines.subList(0, start));
        out.add(indent + "# [AntiMod auto-disabled " + LocalDate.now() + "] failed for " + players
                + " different players who verified every other mod.");
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            out.add(line.isBlank() ? line : indent + "# " + line.substring(indent.length()));
        }
        out.addAll(lines.subList(end, lines.size()));
        return out;
    }

    private static int leading(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + FILE, e);
        }
    }
}
