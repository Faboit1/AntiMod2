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
import java.util.Map;

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

        // Notify staff / console regardless of whether an action fires.
        notify(player, mod, placeholders);

        if (action == null) return;

        // Player message (before commands, so they can see the warning before being kicked).
        if (action.getMessage() != null && !action.getMessage().isEmpty()) {
            Component msg = legacy.deserialize(applyPlaceholders(action.getMessage(), placeholders));
            player.sendMessage(msg);
        }

        // Broadcast
        if (action.getBroadcast() != null && !action.getBroadcast().isEmpty()) {
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
        }

        // Commands (console-executed)
        for (String cmd : action.getCommands()) {
            String finalCmd = applyPlaceholders(cmd, placeholders);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to execute command: " + finalCmd + " - " + t.getMessage());
            }
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
