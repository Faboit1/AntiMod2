package com.faboit.antimod.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A mod used to cross-check a client's translation answers against the
 * plugin channels it registered.
 *
 * If the client registers one of this mod's channels, the mod is installed,
 * so its translation keys must resolve. A client that announces the channel
 * but answers every key with the fallback is hiding its translations.
 */
public final class SpoofModConfig {

    private final String id;
    private final String displayName;
    private final boolean enabled;
    private final List<String> channelPatterns;
    private final List<Pattern> compiledPatterns;
    private final List<String> translationKeys;

    public SpoofModConfig(String id,
                          String displayName,
                          boolean enabled,
                          List<String> channelPatterns,
                          List<String> translationKeys) {
        this.id = id;
        this.displayName = displayName == null ? id : displayName;
        this.enabled = enabled;
        this.channelPatterns = channelPatterns == null
                ? Collections.emptyList()
                : List.copyOf(channelPatterns);
        this.translationKeys = translationKeys == null
                ? Collections.emptyList()
                : List.copyOf(translationKeys);

        List<Pattern> compiled = new ArrayList<>(this.channelPatterns.size());
        for (String p : this.channelPatterns) {
            compiled.add(compileGlob(p));
        }
        this.compiledPatterns = List.copyOf(compiled);
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
    public List<String> getChannelPatterns() { return channelPatterns; }
    public List<String> getTranslationKeys() { return translationKeys; }

    /**
     * Returns the first registered channel that belongs to this mod, or null
     * if the client announced none of them.
     */
    public String findChannel(Collection<String> channels) {
        for (String channel : channels) {
            String lower = channel.toLowerCase(Locale.ROOT);
            for (Pattern p : compiledPatterns) {
                if (p.matcher(lower).matches()) return channel;
            }
        }
        return null;
    }

    /** Glob to regex: {@code *} matches anything, everything else is literal. */
    static Pattern compileGlob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (regex.length() > 0) regex.append(".*");
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }
}
