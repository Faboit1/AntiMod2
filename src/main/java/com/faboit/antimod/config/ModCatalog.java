package com.faboit.antimod.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The mods {@code /antimod mods} can recognise, from mod-catalog.yml.
 *
 * Each entry is keyed by the mod's id, which is also the namespace of the
 * plugin channels it registers, so a channel can be traced back to its mod.
 * Entries may list extra channel patterns for mods that use another namespace.
 */
public final class ModCatalog {

    public static final String FILE = "mod-catalog.yml";

    /** One recognisable mod. */
    public record CatalogMod(String id, String name, List<String> keys, List<Pattern> channelPatterns) {}

    private final Map<String, CatalogMod> mods;
    private final Map<String, CatalogMod> byNamespace;
    private final List<CatalogMod> withPatterns;

    private ModCatalog(Map<String, CatalogMod> mods) {
        this.mods = Collections.unmodifiableMap(mods);
        Map<String, CatalogMod> ns = new HashMap<>();
        List<CatalogMod> patterned = new ArrayList<>();
        for (CatalogMod mod : mods.values()) {
            ns.putIfAbsent(mod.id().toLowerCase(Locale.ROOT), mod);
            if (!mod.channelPatterns().isEmpty()) patterned.add(mod);
        }
        this.byNamespace = ns;
        this.withPatterns = List.copyOf(patterned);
    }

    public static ModCatalog empty() {
        return new ModCatalog(new LinkedHashMap<>());
    }

    public static ModCatalog load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("mods");
        Map<String, CatalogMod> mods = new LinkedHashMap<>();
        if (section == null) return new ModCatalog(mods);
        for (String id : section.getKeys(false)) {
            ConfigurationSection m = section.getConfigurationSection(id);
            if (m == null || !m.getBoolean("enabled", true)) continue;
            List<String> keys = m.getStringList("keys");
            List<Pattern> patterns = new ArrayList<>();
            for (String glob : m.getStringList("channels")) {
                patterns.add(SpoofModConfig.compileGlob(glob));
            }
            mods.put(id, new CatalogMod(id, m.getString("name", id), List.copyOf(keys), List.copyOf(patterns)));
        }
        return new ModCatalog(mods);
    }

    public Collection<CatalogMod> getMods() { return mods.values(); }

    public int size() { return mods.size(); }

    /** The catalog mod a registered channel belongs to, or null if none is known. */
    public CatalogMod forChannel(String channel) {
        String lower = channel.toLowerCase(Locale.ROOT);
        for (CatalogMod mod : withPatterns) {
            for (Pattern p : mod.channelPatterns()) {
                if (p.matcher(lower).matches()) return mod;
            }
        }
        int colon = lower.indexOf(':');
        return colon > 0 ? byNamespace.get(lower.substring(0, colon)) : null;
    }
}
