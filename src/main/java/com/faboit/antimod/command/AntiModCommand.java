package com.faboit.antimod.command;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.config.ModConfig;
import com.faboit.antimod.config.SpoofModConfig;
import com.faboit.antimod.detection.ChannelTracker;
import com.faboit.antimod.detection.DetectionManager;
import com.faboit.antimod.violation.ViolationManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AntiModCommand implements CommandExecutor, TabCompleter {

    private final AntiMod plugin;
    private final DetectionManager detection;
    private final ViolationManager violations;

    public AntiModCommand(AntiMod plugin, DetectionManager detection, ViolationManager violations) {
        this.plugin = plugin;
        this.detection = detection;
        this.violations = violations;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("antimod.admin")) {
            sender.sendMessage(Component.text("You lack permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> reload(sender);
            case "check"  -> check(sender, args);
            case "violations" -> showViolations(sender, args);
            case "clear"  -> clear(sender, args);
            case "info"   -> info(sender);
            case "channels" -> channels(sender, args);
            case "mods"   -> mods(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("AntiMod commands:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/antimod reload", NamedTextColor.AQUA)
                .append(Component.text(" - reload config", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/antimod check <player>", NamedTextColor.AQUA)
                .append(Component.text(" - probe a player now", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/antimod violations <player>", NamedTextColor.AQUA)
                .append(Component.text(" - show violation history", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/antimod clear <player> [mod]", NamedTextColor.AQUA)
                .append(Component.text(" - clear violations", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/antimod info", NamedTextColor.AQUA)
                .append(Component.text(" - list configured mods", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/antimod channels <player>", NamedTextColor.AQUA)
                .append(Component.text(" - show a player's registered plugin channels", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/antimod mods <player> [--slow]", NamedTextColor.AQUA)
                .append(Component.text(" - scan a player for known mods (report only); --slow spreads it out",
                        NamedTextColor.GRAY)));
    }

    private void reload(CommandSender sender) {
        try {
            plugin.getConfigManager().load();
            sender.sendMessage(Component.text("[AntiMod] Configuration reloaded (" +
                    plugin.getConfigManager().getMods().size() + " mods, " +
                    plugin.getConfigManager().getSpoofMods().size() + " spoof-check mods).",
                    NamedTextColor.GREEN));
        } catch (Exception e) {
            sender.sendMessage(Component.text("[AntiMod] Reload failed: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void check(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /antimod check <player>", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not online.", NamedTextColor.RED));
            return;
        }
        detection.runFullDetection(target, true);
        sender.sendMessage(Component.text("[AntiMod] Detection dispatched for " + target.getName() + ".",
                NamedTextColor.GREEN));
    }

    private void showViolations(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /antimod violations <player>", NamedTextColor.RED));
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            sender.sendMessage(Component.text("Unknown player.", NamedTextColor.RED));
            return;
        }
        Map<String, Integer> map = violations.getAllViolations(uuid);
        if (map.isEmpty()) {
            sender.sendMessage(Component.text("[AntiMod] No violations for " + args[1] + ".",
                    NamedTextColor.GREEN));
            return;
        }
        sender.sendMessage(Component.text("[AntiMod] Violations for " + args[1] + ":",
                NamedTextColor.YELLOW));
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            ModConfig mod = plugin.getConfigManager().getMod(e.getKey());
            String name = mod == null ? e.getKey() : mod.getDisplayName();
            sender.sendMessage(Component.text("  " + name + ": " + e.getValue(), NamedTextColor.AQUA));
        }
    }

    private void clear(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /antimod clear <player> [mod]", NamedTextColor.RED));
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            sender.sendMessage(Component.text("Unknown player.", NamedTextColor.RED));
            return;
        }
        if (args.length >= 3) {
            violations.clear(uuid, args[2]);
            sender.sendMessage(Component.text("[AntiMod] Cleared " + args[2] + " violations for " + args[1] + ".",
                    NamedTextColor.GREEN));
        } else {
            violations.clear(uuid);
            sender.sendMessage(Component.text("[AntiMod] Cleared all violations for " + args[1] + ".",
                    NamedTextColor.GREEN));
        }
    }

    private void info(CommandSender sender) {
        sender.sendMessage(Component.text("[AntiMod] Configured mods:", NamedTextColor.YELLOW));
        for (ModConfig mod : plugin.getConfigManager().getMods().values()) {
            String state = mod.isEnabled() ? "&aenabled" : "&cdisabled";
            sender.sendMessage(Component.text(String.format("  %s (%s) - %d keys, %d actions - %s",
                    mod.getDisplayName(),
                    mod.getId(),
                    mod.getTranslationKeys().size(),
                    mod.getActions().size(),
                    state.replace("&a", "").replace("&c", ""))
            , mod.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        ConfigManager cfg = plugin.getConfigManager();
        long enabledSpoof = cfg.getSpoofMods().values().stream().filter(SpoofModConfig::isEnabled).count();
        sender.sendMessage(Component.text(String.format("[AntiMod] Spoof check: %s - %d/%d channel-confirmed mods enabled (%s)",
                        cfg.isSpoofEnabled() ? "on" : "off", enabledSpoof, cfg.getSpoofMods().size(),
                        ConfigManager.SPOOF_MODS_FILE),
                cfg.isSpoofEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void mods(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /antimod mods <player> [--slow]", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not online.", NamedTextColor.RED));
            return;
        }
        boolean slow = Arrays.stream(args).skip(2).anyMatch(a -> a.equalsIgnoreCase("--slow"));
        detection.runModScan(target, sender, slow);
    }

    private void channels(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /antimod channels <player>", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not online.", NamedTextColor.RED));
            return;
        }
        ChannelTracker tracker = detection.getChannelTracker();
        Set<String> registered = tracker.getChannels(target);
        String brand = target.getClientBrandName();
        sender.sendMessage(Component.text("[AntiMod] " + target.getName() + " - brand: "
                + (brand == null ? "unknown" : brand) + ", " + registered.size() + " channels:", NamedTextColor.YELLOW));
        List<String> shown = new ArrayList<>();
        for (String channel : registered) {
            String why = tracker.whyIgnored(channel);
            shown.add(why == null ? channel : channel + " [ignored: " + why + "]");
        }
        if (!shown.isEmpty()) {
            sender.sendMessage(Component.text("  " + String.join(", ", shown), NamedTextColor.GRAY));
        }
        if (tracker.isVanillaBrand(target)) {
            sender.sendMessage(Component.text("  Vanilla brand: channels are not used as evidence.", NamedTextColor.GRAY));
        }

        Set<String> evidence = tracker.getSpoofCheckChannels(target);
        List<String> confirmed = new ArrayList<>();
        for (SpoofModConfig mod : plugin.getConfigManager().getSpoofMods().values()) {
            String channel = mod.findChannel(evidence);
            if (channel != null) confirmed.add(mod.getDisplayName() + " (" + channel + ")");
        }
        sender.sendMessage(Component.text("  Spoof-check mods confirmed by channel: "
                + (confirmed.isEmpty() ? "none" : String.join(", ", confirmed)), NamedTextColor.AQUA));
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.getUniqueId();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("antimod.admin")) return List.of();
        if (args.length == 1) {
            List<String> subs = Arrays.asList("reload", "check", "violations", "clear", "info", "channels", "mods");
            return filter(subs, args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("info")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("mods")) {
            return filter(List.of("--slow"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("clear")) {
            List<String> ids = new ArrayList<>(plugin.getConfigManager().getMods().keySet());
            ids.add(plugin.getConfigManager().getSpoofDetector().getId());
            return filter(ids, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) out.add(s);
        }
        return out;
    }
}
