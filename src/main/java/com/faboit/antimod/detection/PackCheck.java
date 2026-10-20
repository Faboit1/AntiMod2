package com.faboit.antimod.detection;

import com.faboit.antimod.AntiMod;
import com.faboit.antimod.action.ActionExecutor;
import com.faboit.antimod.config.ConfigManager;
import com.faboit.antimod.util.FloodgateUtil;
import com.faboit.antimod.util.FoliaScheduler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code /antimod check <player> --pack}: tells ExploitPreventer apart from a
 * normal client by how it downloads a server resource pack.
 *
 * ExploitPreventer replaces Java's redirect handling for pack downloads with
 * its own loop, which gives up one redirect early: Java follows 19 redirects
 * and downloads on the 20th request, ExploitPreventer throws once it has made
 * that 20th request. So AntiMod serves a tiny empty pack behind exactly 19
 * redirects from its own HTTP server. A normal client reports it downloaded;
 * ExploitPreventer reports a failed download even though the last hop was
 * served. Installed mods and translation keys play no part.
 */
public final class PackCheck implements Listener {

    private static final String PATH = "/antimod-pack/";

    /** One check in flight. */
    private static final class Probe {
        final UUID player;
        final String playerName;
        final UUID packId;
        final String token;
        final CommandSender requester;
        final Set<Integer> hopsServed = ConcurrentHashMap.newKeySet();
        final AtomicBoolean finished = new AtomicBoolean();

        Probe(Player player, UUID packId, String token, CommandSender requester) {
            this.player = player.getUniqueId();
            this.playerName = player.getName();
            this.packId = packId;
            this.token = token;
            this.requester = requester;
        }
    }

    private final AntiMod plugin;
    private final ActionExecutor actionExecutor;
    private final SecureRandom random = new SecureRandom();
    private final byte[] pack;
    private final byte[] packSha1;
    private final Map<String, Probe> byToken = new ConcurrentHashMap<>();
    private final Map<UUID, Probe> byPlayer = new ConcurrentHashMap<>();

    private HttpServer server;
    private ExecutorService httpExecutor;

    public PackCheck(AntiMod plugin, ActionExecutor actionExecutor) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.pack = buildPack();
        this.packSha1 = sha1(pack);
    }

    /** Starts the HTTP server when pack-check is enabled with a port. */
    public void start() {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isPackCheckEnabled() || cfg.getPackCheckPort() <= 0) return;
        try {
            server = HttpServer.create(new InetSocketAddress(cfg.getPackCheckBind(), cfg.getPackCheckPort()), 0);
            httpExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "AntiMod-PackCheck-HTTP");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(httpExecutor);
            server.createContext(PATH, this::handle);
            server.start();
            plugin.getLogger().info("Pack check listening on " + cfg.getPackCheckBind() + ":" + cfg.getPackCheckPort()
                    + (cfg.getPackCheckPublicUrl().isEmpty() ? " (set pack-check.public-url to use it)"
                    : ", public URL " + cfg.getPackCheckPublicUrl()));
        } catch (IOException | RuntimeException e) {
            server = null;
            if (httpExecutor != null) httpExecutor.shutdownNow();
            plugin.getLogger().warning("Pack check could not listen on port " + cfg.getPackCheckPort()
                    + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
        server = null;
        byToken.clear();
        byPlayer.clear();
    }

    /** Pushes the probe pack to {@code target}; the verdict goes to {@code requester}. */
    public void start(Player target, CommandSender requester) {
        ConfigManager cfg = plugin.getConfigManager();
        String base = cfg.getPackCheckPublicUrl();
        if (server == null || base.isEmpty()) {
            requester.sendMessage(Component.text("[AntiMod] The pack check is off. Set pack-check.port and "
                    + "pack-check.public-url in config.yml (a port players can reach), then restart.", NamedTextColor.RED));
            return;
        }
        if (FloodgateUtil.isFloodgatePlayer(target)) {
            requester.sendMessage(Component.text("[AntiMod] " + target.getName()
                    + " is a Bedrock player; the pack check only applies to Java clients.", NamedTextColor.RED));
            return;
        }
        Probe probe = new Probe(target, UUID.randomUUID(), HexFormat.of().formatHex(randomBytes(12)), requester);
        if (byPlayer.putIfAbsent(target.getUniqueId(), probe) != null) {
            requester.sendMessage(Component.text("[AntiMod] A pack check for " + target.getName()
                    + " is already running.", NamedTextColor.RED));
            return;
        }
        byToken.put(probe.token, probe);

        String url = hopUrl(base, probe.token, cfg.getPackCheckRedirects());
        String prompt = cfg.getPackCheckPrompt();
        FoliaScheduler.runNowOrSoon(plugin, target, () ->
                target.addResourcePack(probe.packId, url, packSha1, prompt.isEmpty() ? null : prompt, false));
        requester.sendMessage(Component.text("[AntiMod] Pack check sent to " + target.getName()
                + ". Waiting for their client...", NamedTextColor.GRAY));
        FoliaScheduler.runGlobalTaskLater(plugin, () -> finish(probe, Verdict.INCONCLUSIVE,
                "no answer within " + cfg.getPackCheckTimeoutSeconds() + "s"), cfg.getPackCheckTimeoutSeconds() * 20L);
    }

    // ------------------------------------------------------------------
    //   HTTP: <base>/antimod-pack/<token>/<hops left>
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String[] parts = exchange.getRequestURI().getPath().substring(PATH.length()).split("/");
            Probe probe = parts.length == 2 ? byToken.get(parts[0]) : null;
            int hop;
            try {
                hop = probe == null ? -1 : Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                hop = -1;
            }
            if (probe == null || hop < 0 || hop > 64) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            probe.hopsServed.add(hop);
            if (hop > 0) {
                exchange.getResponseHeaders().set("Location",
                        hopUrl(plugin.getConfigManager().getPackCheckPublicUrl(), probe.token, hop - 1));
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, pack.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(pack);
            }
        }
    }

    private static String hopUrl(String base, String token, int hop) {
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + PATH + token + "/" + hop;
    }

    // ------------------------------------------------------------------
    //   Client answer
    // ------------------------------------------------------------------

    private enum Verdict { CLEAN, DETECTED, INCONCLUSIVE }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Probe probe = byPlayer.get(event.getPlayer().getUniqueId());
        if (probe == null || !probe.packId.equals(event.getID())) return;
        int redirects = plugin.getConfigManager().getPackCheckRedirects();
        switch (event.getStatus()) {
            case DOWNLOADED, SUCCESSFULLY_LOADED, FAILED_RELOAD ->
                    finish(probe, Verdict.CLEAN, "followed all " + redirects + " redirects and downloaded the pack");
            case FAILED_DOWNLOAD -> {
                if (probe.hopsServed.contains(0)) {
                    finish(probe, Verdict.DETECTED, "fetched the pack after " + redirects
                            + " redirects, then reported the download as failed: ExploitPreventer's redirect handling");
                } else {
                    finish(probe, Verdict.INCONCLUSIVE, "download failed after " + probe.hopsServed.size()
                            + " of " + (redirects + 1) + " requests (network or URL problem)");
                }
            }
            case DECLINED -> finish(probe, Verdict.INCONCLUSIVE, "declined the resource pack");
            case INVALID_URL -> finish(probe, Verdict.INCONCLUSIVE, "rejected the pack URL as invalid");
            case DISCARDED -> finish(probe, Verdict.INCONCLUSIVE, "discarded the pack");
            default -> { } // ACCEPTED: the download follows
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
        byToken.remove(probe.token, probe);

        NamedTextColor color = switch (verdict) {
            case CLEAN -> NamedTextColor.GREEN;
            case DETECTED -> NamedTextColor.RED;
            case INCONCLUSIVE -> NamedTextColor.GOLD;
        };
        String label = switch (verdict) {
            case CLEAN -> "clean";
            case DETECTED -> "ExploitPreventer detected";
            case INCONCLUSIVE -> "inconclusive";
        };
        String line = "[AntiMod] Pack check on " + probe.playerName + ": " + label + " - " + why + ".";
        Player player = Bukkit.getPlayer(probe.player);
        FoliaScheduler.runGlobalTask(plugin, () -> {
            probe.requester.sendMessage(Component.text(line, color));
            if (!(probe.requester instanceof org.bukkit.command.ConsoleCommandSender)) {
                plugin.getLogger().info(line.substring("[AntiMod] ".length()));
            }
            if (verdict == Verdict.DETECTED && player != null && player.isOnline()
                    && plugin.getConfigManager().isPackCheckRunActions()) {
                try {
                    actionExecutor.execute(player, plugin.getConfigManager().getSpoofDetector(),
                            "ExploitPreventer resource-pack fingerprint");
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Failed to run the pack-check action on "
                            + probe.playerName, t);
                }
            }
        });
        // Take the probe pack off the client again.
        if (player != null && player.isOnline()) {
            FoliaScheduler.runNowOrSoon(plugin, player, () -> player.removeResourcePack(probe.packId));
        }
    }

    // ------------------------------------------------------------------

    /** A valid, empty resource pack: just pack.mcmeta. */
    private static byte[] buildPack() {
        String meta = "{\"pack\":{\"description\":\"AntiMod client check\",\"pack_format\":46,"
                + "\"supported_formats\":[1,999],\"min_format\":1,\"max_format\":999}}";
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("pack.mcmeta");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }
}
