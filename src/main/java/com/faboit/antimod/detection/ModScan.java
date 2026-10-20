package com.faboit.antimod.detection;

import com.faboit.antimod.config.ModCatalog;
import com.faboit.antimod.config.SpoofModConfig;
import com.faboit.antimod.util.ClientVersionUtil;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One {@code /antimod mods} scan: who asked, what was probed, and the report.
 *
 * A scan only reads; its probes never record violations or run actions.
 */
public final class ModScan {

    private final CommandSender requester;
    private final String targetName;
    /** probe owner (catalog id, or "config:<id>" for a banned mod) -> display name */
    private final Map<String, String> names;
    private final int signCount;
    /** Read when the scan starts: the client may have left by the time it is reported. */
    private final String brand;
    private final String version;
    private final AtomicBoolean finished = new AtomicBoolean();

    public ModScan(CommandSender requester, Player target, Map<String, String> names, int signCount) {
        this.requester = requester;
        this.targetName = target.getName();
        this.names = names;
        this.signCount = signCount;
        this.brand = target.getClientBrandName();
        int protocol = ClientVersionUtil.getProtocolVersion(target);
        this.version = protocol > 0 ? ClientVersion.getById(protocol).getReleaseName() : "unknown";
    }

    public CommandSender getRequester() { return requester; }
    public String getTargetName() { return targetName; }

    /** True exactly once, so a scan is reported once. */
    public boolean claimFinish() {
        return finished.compareAndSet(false, true);
    }

    /**
     * Sends the report: mods whose keys resolved, the mods the client's
     * channels say it has (and whether their keys resolved), and channel
     * namespaces nothing in the catalog accounts for.
     */
    public void report(ProbeBatch batch, Player target, ChannelTracker tracker, ModCatalog catalog,
                       Collection<SpoofModConfig> spoofMods, int unanswered) {
        send(Component.text("[AntiMod] Mods of " + targetName, NamedTextColor.YELLOW)
                .append(Component.text(" - brand: " + (brand == null ? "unknown" : brand) + ", version: " + version,
                        NamedTextColor.GRAY)));

        // Detected by translation keys.
        Set<String> detected = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String owner : batch.getCatalogResolved()) detected.add(names.getOrDefault(owner, owner));
        send(line("Detected by translation keys (" + detected.size() + "): ",
                detected.isEmpty() ? "none" : String.join(", ", detected), NamedTextColor.GREEN));

        // Expected from channels, grouped by mod.
        Map<String, String> expected = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); // name -> status
        Set<String> unknown = new TreeSet<>();
        int ignored = 0;
        for (String channel : tracker.getChannels(target)) {
            if (tracker.whyIgnored(channel) != null) {
                ignored++;
                continue;
            }
            ModCatalog.CatalogMod mod = catalog.forChannel(channel);
            if (mod != null) {
                String status = batch.getCatalogResolved().contains(mod.id()) ? "(verified)"
                        : batch.isCatalogHidden(mod.id()) ? "(keys hidden)" : "(no answer)";
                expected.merge(mod.name(), status, ModScan::stronger);
                continue;
            }
            String spoofName = spoofName(spoofMods, channel);
            if (spoofName != null) {
                expected.merge(spoofName, "(not in catalog)", ModScan::stronger);
            } else {
                int colon = channel.indexOf(':');
                unknown.add(colon > 0 ? channel.substring(0, colon) : channel);
            }
        }
        List<String> shown = new ArrayList<>();
        expected.forEach((name, status) -> shown.add(name + " " + status));
        send(line("Expected from channels (" + expected.size() + "): ",
                shown.isEmpty() ? "none" : String.join(", ", shown), NamedTextColor.AQUA));
        if (!unknown.isEmpty()) {
            send(line("Channels from unlisted mods: ", String.join(", ", unknown), NamedTextColor.GRAY));
        }
        List<String> notes = new ArrayList<>();
        if (expected.values().stream().anyMatch(s -> s.equals("(keys hidden)"))) {
            notes.add("keys hidden = channel registered but every key came back untranslated, "
                    + "the mark translation-hiding mods such as OpSec leave");
        }
        if (ignored > 0) notes.add(ignored + " proxy/ignored channel(s) left out");
        if (unanswered > 0) notes.add(unanswered + " of " + signCount + " signs got no answer");
        if (!notes.isEmpty()) send(Component.text("  (" + String.join("; ", notes) + ")", NamedTextColor.DARK_GRAY));
    }

    /** A status that says more wins when one mod has several channels. */
    private static String stronger(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String status) {
        return status.equals("(verified)") ? 2 : status.equals("(keys hidden)") ? 1 : 0;
    }

    private static String spoofName(Collection<SpoofModConfig> spoofMods, String channel) {
        for (SpoofModConfig mod : spoofMods) {
            if (mod.findChannel(List.of(channel)) != null) return mod.getDisplayName();
        }
        return null;
    }

    private static TextComponent line(String label, String body, NamedTextColor color) {
        return Component.text("  " + label, color).append(Component.text(body, NamedTextColor.WHITE));
    }

    private void send(Component message) {
        requester.sendMessage(message);
    }

    /** Builds the owner -> name map for a scan. */
    public static Map<String, String> namesOf(ModCatalog catalog) {
        Map<String, String> names = new LinkedHashMap<>();
        for (ModCatalog.CatalogMod mod : catalog.getMods()) names.put(mod.id(), mod.name());
        return names;
    }
}
