package com.faboit.antimod.action;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.config.ActionConfig;
import com.faboit.antimod.config.ModConfig;
import com.faboit.antimod.violation.ViolationManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Runs the configured action(s) for a detected mod: increments the
 * violation counter, resolves the matching threshold action, then
 * executes its commands / broadcast / player message.
 */
public final class ActionExecutor {

    private final AntiMod plugin;
    private final ViolationManager violations;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    public ActionExecutor(AntiMod plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    public void execute(Player player, ModConfig mod, String matchedKeys) {
        // Bump the counter first.
        int count = violations.recordViolation(player.getUniqueId(), player.getName(), mod.getId());

        // Resolve the applicable action.
        ActionConfig action = mod.findApplicableAction(count);

        // Build placeholder map.
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("uuid", player.getUniqueId().toString());
        placeholders.put("mod", mod.getDisplayName());
        placeholders.put("mod-id", mod.getId());
        placeholders.put("violations", String.valueOf(count));
        placeholders.put("keys", matchedKeys == null ? "" : matchedKeys);
        placeholders.put("action", action == null ? "NONE" : action.describe());

        // Notify staff / console regardless of whether an action fires. A
        // malformed message must not stop the punishment that follows it, so
        // every messaging step below is isolated from the command dispatch.
        try {
            notify(player, mod, placeholders);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to send the detection notification.", t);
        }

        if (action == null) return;

        // Player message (before commands, so they can see the warning before being kicked).
        if (action.getMessage() != null && !action.getMessage().isEmpty()) {
            try {
                Component msg = legacy.deserialize(applyPlaceholders(action.getMessage(), placeholders));
                player.sendMessage(msg);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to send the action message.", t);
            }
        }

        // Broadcast
        if (action.getBroadcast() != null && !action.getBroadcast().isEmpty()) {
            try {
                String rawText = applyPlaceholders(action.getBroadcast(), placeholders);
                Component broadcastComp = legacy.deserialize(rawText);
                if (action.isStaffOnly()) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission("antimod.notify")) {
                            p.sendMessage(broadcastComp);
                        }
                    }
                    Bukkit.getConsoleSender().sendMessage(broadcastComp);
                } else {
                    Bukkit.broadcast(broadcastComp);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to send the action broadcast.", t);
            }
        }

        // Commands (console-executed)
        for (String cmd : action.getCommands()) {
            runConsoleCommand(applyPlaceholders(cmd, placeholders));
        }
    }

    /**
     * Dispatches one configured command from the console.
     *
     * The command is normalised first: Bukkit expects the bare command line, so
     * a leading slash — which is how most people write commands in a config —
     * would otherwise be dispatched as the literal command "/kick" and silently
     * do nothing. A command Bukkit could not run at all is logged rather than
     * dropped, since a missing punishment is the whole failure mode here.
     */
    private void runConsoleCommand(String rawCommand) {
        String cmd = rawCommand == null ? "" : rawCommand.trim();
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        if (cmd.isEmpty()) return;
        // /kick shows its reason as plain text, so "&c" would reach the player
        // literally: turn &-codes into real colour codes for kick commands.
        String label = cmd.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.equals("kick") || label.endsWith(":kick")) {
            cmd = cmd.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
        }

        try {
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
                plugin.getLogger().warning("Command did not run: \"" + cmd
                        + "\" - the server rejected it (unknown command, or the plugin that "
                        + "provides it is not installed).");
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to execute command: " + cmd, t);
        }
    }

    private void notify(Player player, ModConfig mod, Map<String, String> placeholders) {
        if (plugin.getConfigManager().isConsoleLog()) {
            String line = applyPlaceholders(plugin.getConfigManager().getConsoleMessage(), placeholders);
            plugin.getLogger().info(stripColorCodes(line));
        }
        if (plugin.getConfigManager().isStaffBroadcast()) {
            String raw = applyPlaceholders(plugin.getConfigManager().getStaffMessage(), placeholders);
            Component msg;
            // Support both MiniMessage (<tag>) and legacy (&) formats.
            if (raw.contains("<") && raw.contains(">")) {
                try { msg = mini.deserialize(raw); }
                catch (Throwable t) { msg = legacy.deserialize(raw); }
            } else {
                msg = legacy.deserialize(raw);
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("antimod.notify")) {
                    p.sendMessage(msg);
                }
            }
        }
    }

    private static String applyPlaceholders(String input, Map<String, String> placeholders) {
        if (input == null) return "";
        String out = input;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
            // Also support MiniMessage-style placeholders <player>, <mod>, etc.
            out = out.replace("<" + e.getKey() + ">", e.getValue());
        }
        return out;
    }

    private static String stripColorCodes(String s) {
        if (s == null) return "";
        return s.replaceAll("&[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
}
