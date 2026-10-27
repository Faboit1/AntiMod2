package com.faboit.antimod.detection;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.util.ClientVersionUtil;
import com.faboit.antimod.util.FloodgateUtil;
import com.faboit.antimod.util.FoliaScheduler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Teleport trap for Autism Client.
 *
 * Autism's always-on "packet sanity" filter drops any server teleport whose
 * yaw or pitch is not a normal number (NaN, infinite, or beyond 1e9), and
 * with it the teleport confirmation. A vanilla client applies the teleport,
 * discards the non-finite rotation (Entity.setYRot ignores it) and always
 * confirms. So the player gets two teleports in one bundle, both to where
 * they already stand:
 *   A (trap):    yaw = NaN
 *   B (control): their current yaw/pitch, which also overwrites the NaN that
 *                vanilla stores as the previous-frame rotation
 * Both confirmed = normal client. Only B = the client drops invalid
 * teleports (Autism). Neither = no answer, inconclusive.
 *
 * Paper ignores confirmations for teleport ids it did not issue, so the
 * replies are only observed, never cancelled.
 */
public final class TeleportCheck extends PacketListenerAbstract implements Listener {

    /** 1.21.2: teleports carry a velocity and per-axis relative flags. */
    private static final int PROTOCOL_1_21_2 = 768;

    /** Relative position and velocity, all zero: the player stays put. */
    private static final RelativeFlag STAY = RelativeFlag.X.or(RelativeFlag.Y).or(RelativeFlag.Z)
            .or(RelativeFlag.DELTA_X).or(RelativeFlag.DELTA_Y).or(RelativeFlag.DELTA_Z);

    private static final class Probe {
        final UUID player;
        final String playerName;
        final int trapId;
        final int controlId;
        final CommandSender requester;
        final boolean onJoin;
        volatile boolean trapConfirmed;
        final AtomicBoolean finished = new AtomicBoolean();

        Probe(Player player, int trapId, int controlId, CommandSender requester, boolean onJoin) {
            this.player = player.getUniqueId();
            this.playerName = player.getName();
            this.trapId = trapId;
            this.controlId = controlId;
            this.requester = requester;
            this.onJoin = onJoin;
        }
    }

    private enum Verdict { CLEAN, DETECTED, INCONCLUSIVE }

    private final AntiMod plugin;
    private final ActionExecutor actionExecutor;
    private final Map<UUID, Probe> byPlayer = new ConcurrentHashMap<>();
    /** Teleport ids far below anything the server hands out (it counts up from 0). */
    private final AtomicInteger nextId = new AtomicInteger(Integer.MIN_VALUE / 2);

    public TeleportCheck(AntiMod plugin, ActionExecutor actionExecutor) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
    }

    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /** {@code /antimod check <player> --teleport}: the verdict goes to {@code requester}. */
    public void start(Player target, CommandSender requester) {
        String problem = probe(target, requester, false);
        requester.sendMessage(problem != null
                ? Component.text("[AntiMod] " + problem, NamedTextColor.RED)
                : Component.text("[AntiMod] Teleport check sent to " + target.getName() + ".", NamedTextColor.GRAY));
    }

    /** teleport-check.on-join: checks {@code player} once their client has loaded the world (plus the delay). */
    public void scheduleOnJoin(Player player) {
        FoliaScheduler.runTaskLater(plugin, player, () -> {
            if (player.isOnline()) probe(player, null, true);
        }, Math.max(1, plugin.getConfigManager().getTeleportCheckDelayTicks()));
    }

    /** Sends the two teleports. Returns why it could not, or null once they are on their way. */
    private String probe(Player target, CommandSender requester, boolean onJoin) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isTeleportCheckEnabled()) return "The teleport check is disabled (teleport-check.enabled).";
        if (FloodgateUtil.isFloodgatePlayer(target)) {
            return target.getName() + " is a Bedrock player; the teleport check only applies to Java clients.";
        }
        if (ClientVersionUtil.getProtocolVersion(target) < PROTOCOL_1_21_2) {
            return target.getName() + "'s client is older than 1.21.2; the teleport check needs a newer one "
                    + "(Autism Client only runs on 26.x).";
        }
        int trapId = nextId.getAndAdd(2);
        Probe probe = new Probe(target, trapId, trapId + 1, requester, onJoin);
        if (byPlayer.putIfAbsent(target.getUniqueId(), probe) != null) {
            return "A teleport check for " + target.getName() + " is already running.";
        }

        // The rotation is read on the player's own thread (Folia).
        FoliaScheduler.runNowOrSoon(plugin, target, () -> {
            if (!target.isOnline()) return;
            Location at = target.getLocation();
            Vector3d zero = new Vector3d(0, 0, 0);
            // Yaw absolute NaN (no YAW flag), pitch relative 0. No ROTATE_DELTA:
            // rotating the velocity by NaN would break it.
            WrapperPlayServerPlayerPositionAndLook trap = new WrapperPlayServerPlayerPositionAndLook(
                    probe.trapId, zero, zero, Float.NaN, 0f, STAY.or(RelativeFlag.PITCH));
            WrapperPlayServerPlayerPositionAndLook control = new WrapperPlayServerPlayerPositionAndLook(
                    probe.controlId, zero, zero, at.getYaw(), at.getPitch(), STAY);
            // One bundle: the client handles both in the same tick, so it never
            // draws a frame between them.
            var players = PacketEvents.getAPI().getPlayerManager();
            players.sendPacket(target, new WrapperPlayServerBundle());
            players.sendPacket(target, trap);
            players.sendPacket(target, control);
            players.sendPacket(target, new WrapperPlayServerBundle());
        });
        FoliaScheduler.runGlobalTaskLater(plugin, () -> finish(probe, Verdict.INCONCLUSIVE,
                "no teleport confirmation within " + (cfg.getTeleportCheckTimeoutTicks() / 20) + "s"),
                cfg.getTeleportCheckTimeoutTicks());
        return null;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.TELEPORT_CONFIRM) return;
        UUID uuid = event.getUser().getUUID();
        Probe probe = uuid == null ? null : byPlayer.get(uuid);
        if (probe == null) return;
        int id = new WrapperPlayClientTeleportConfirm(event).getTeleportId();
        if (id == probe.trapId) {
            probe.trapConfirmed = true;
        } else if (id == probe.controlId) {
            // Confirmations arrive in order, so the trap's would already be here.
            if (probe.trapConfirmed) {
                finish(probe, Verdict.CLEAN, "confirmed both teleports");
            } else {
                finish(probe, Verdict.DETECTED, "confirmed the normal teleport but silently dropped the one "
                        + "with an invalid rotation (Autism Client's packet filter)");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Probe probe = byPlayer.get(event.getPlayer().getUniqueId());
        if (probe != null) finish(probe, Verdict.INCONCLUSIVE, "left the server");
    }

    private void finish(Probe probe, Verdict verdict, String why) {
        if (!probe.finished.compareAndSet(false, true)) return;
        byPlayer.remove(probe.player, probe);

        NamedTextColor color = switch (verdict) {
            case CLEAN -> NamedTextColor.GREEN;
            case DETECTED -> NamedTextColor.RED;
            case INCONCLUSIVE -> NamedTextColor.GOLD;
        };
        String label = switch (verdict) {
            case CLEAN -> "clean";
            case DETECTED -> plugin.getConfigManager().getTeleportDetector().getDisplayName() + " detected";
            case INCONCLUSIVE -> "inconclusive";
        };
        String line = "[AntiMod] " + (probe.onJoin ? "Join teleport check" : "Teleport check") + " on "
                + probe.playerName + ": " + label + " - " + why + ".";
        FoliaScheduler.runGlobalTask(plugin, () -> {
            if (probe.requester != null) probe.requester.sendMessage(Component.text(line, color));
            if (!(probe.requester instanceof ConsoleCommandSender)) {
                plugin.getLogger().info(line.substring("[AntiMod] ".length()));
            }
            if (verdict != Verdict.DETECTED) return;
            if (probe.onJoin) {
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("antimod.notify")) staff.sendMessage(Component.text(line, color));
                }
            }
            Player player = Bukkit.getPlayer(probe.player);
            if (player != null && player.isOnline() && plugin.getConfigManager().isTeleportCheckRunActions()) {
                try {
                    actionExecutor.execute(player, plugin.getConfigManager().getTeleportDetector(),
                            "dropped a teleport with an invalid rotation");
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Failed to run the teleport-check action on "
                            + probe.playerName, t);
                }
            }
        });
    }
}
