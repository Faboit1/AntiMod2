package com.faboit.antimod.config;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for a single banned/monitored mod.
 */
public final class ModConfig {

    private final String id;
    private final String displayName;
    private final boolean enabled;
    private final List<String> translationKeys;
    private final List<ActionConfig> actions;

    public ModConfig(String id,
                     String displayName,
                     boolean enabled,
                     List<String> translationKeys,
                     List<ActionConfig> actions) {
        this.id = id;
        this.displayName = displayName == null ? id : displayName;
        this.enabled = enabled;
        this.translationKeys = translationKeys == null
                ? Collections.emptyList()
                : List.copyOf(translationKeys);
        this.actions = actions == null
                ? Collections.emptyList()
                : List.copyOf(actions);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getTranslationKeys() {
        return translationKeys;
    }

    public List<ActionConfig> getActions() {
        return actions;
    }

    /**
     * Finds the highest-threshold action whose threshold is &lt;= the given
     * violation count. Returns null if no action applies yet.
     */
    public ActionConfig findApplicableAction(int violations) {
        ActionConfig chosen = null;
        for (ActionConfig action : actions) {
            if (action.getThreshold() <= violations
                    && (chosen == null || action.getThreshold() > chosen.getThreshold())) {
                chosen = action;
            }
        }
        return chosen;
    }
}
