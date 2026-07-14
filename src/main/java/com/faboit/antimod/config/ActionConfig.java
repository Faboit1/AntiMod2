package com.faboit.antimod.config;

import java.util.Collections;
import java.util.List;

/**
 * One action rule for a mod, triggered when a player's violation count
 * for that mod reaches (or exceeds) the given threshold.
 */
public final class ActionConfig {

    private final int threshold;
    private final List<String> commands;
    private final String broadcast;
    private final boolean staffOnly;
    private final String message;

    public ActionConfig(int threshold,
                        List<String> commands,
                        String broadcast,
                        boolean staffOnly,
                        String message) {
        this.threshold = threshold;
        this.commands = commands == null ? Collections.emptyList() : List.copyOf(commands);
        this.broadcast = broadcast;
        this.staffOnly = staffOnly;
        this.message = message;
    }

    public int getThreshold() {
        return threshold;
    }

    public List<String> getCommands() {
        return commands;
    }

    public String getBroadcast() {
        return broadcast;
    }

    public boolean isStaffOnly() {
        return staffOnly;
    }

    public String getMessage() {
        return message;
    }

    /**
     * A short human-readable label for this action, used in staff broadcasts.
     */
    public String describe() {
        if (!commands.isEmpty()) {
            String first = commands.get(0).toLowerCase();
            if (first.startsWith("kick ")) return "KICK";
            if (first.contains("ban")) return "BAN";
            if (first.startsWith("tempban ")) return "TEMPBAN";
            if (first.startsWith("mute ")) return "MUTE";
            return "COMMAND";
        }
        if (broadcast != null || message != null) return "WARN";
        return "NONE";
    }
}
