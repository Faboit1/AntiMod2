package com.faboit.antimod.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Scheduler abstraction that works on both Bukkit/Paper and Folia.
 *
 * Folia removes the global BukkitScheduler entirely — calling
 * Bukkit.getScheduler().runTask*() throws UnsupportedOperationException.
 * Instead, Folia provides:
 *   - Entity.getScheduler() for tasks tied to a specific entity
 *   - Server.getGlobalRegionScheduler() for global (non-entity) tasks
 *
 * All Folia methods are invoked via reflection to avoid a compile-time
 * dependency, so this plugin works on both Paper and Folia unchanged.
 */
public final class FoliaScheduler {

    private static final boolean FOLIA;

    // Entity.getScheduler()
    private static Method entityGetScheduler;
    // EntityScheduler.run(Plugin, Consumer<ScheduledTask>, Runnable retired)
    private static Method entitySchedulerRun;
    // EntityScheduler.runDelayed(Plugin, Consumer<ScheduledTask>, Runnable retired, long delayTicks)
    private static Method entitySchedulerRunDelayed;
    // Server.getGlobalRegionScheduler()
    private static Method serverGetGlobalScheduler;
    // GlobalRegionScheduler.run(Plugin, Consumer<ScheduledTask>)
    private static Method globalSchedulerRun;
    // GlobalRegionScheduler.runDelayed(Plugin, Consumer<ScheduledTask>, long delayTicks)
    private static Method globalSchedulerRunDelayed;
    // GlobalRegionScheduler.runAtFixedRate(Plugin, Consumer<ScheduledTask>, long initialDelayTicks, long periodTicks)
    private static Method globalSchedulerRunAtFixedRate;

    static {
        boolean folia = false;
        try {
            Class<?> entitySchedulerClass = Class.forName(
                    "io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            Class<?> globalSchedulerClass = Class.forName(
                    "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> scheduledTaskClass = Class.forName(
                    "io.papermc.paper.threadedregions.scheduler.ScheduledTask");

            entityGetScheduler = Entity.class.getMethod("getScheduler");

            entitySchedulerRun = entitySchedulerClass.getMethod(
                    "run", Plugin.class, Consumer.class, Runnable.class);
            entitySchedulerRunDelayed = entitySchedulerClass.getMethod(
                    "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);

            serverGetGlobalScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");

            globalSchedulerRun = globalSchedulerClass.getMethod(
                    "run", Plugin.class, Consumer.class);
            globalSchedulerRunDelayed = globalSchedulerClass.getMethod(
                    "runDelayed", Plugin.class, Consumer.class, long.class);
            globalSchedulerRunAtFixedRate = globalSchedulerClass.getMethod(
                    "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

            folia = true;
        } catch (Exception ignored) {
        }
        FOLIA = folia;
    }

    public static boolean isFoliaAvailable() {
        return FOLIA;
    }

    /**
     * Runs a task on the next tick, tied to the given entity's region.
     * Falls back to Bukkit.getScheduler().runTask() on non-Folia.
     */
    public static void runTask(Plugin plugin, Entity entity, Runnable task) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        try {
            Object scheduler = entityGetScheduler.invoke(entity);
            entitySchedulerRun.invoke(scheduler, plugin,
                    (Consumer<Object>) t -> task.run(), null);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[AntiMod] Folia entity scheduler failed: " + e.getMessage());
        }
    }

    /**
     * Runs a task after a delay, tied to the given entity's region.
     * Falls back to Bukkit.getScheduler().runTaskLater() on non-Folia.
     */
    public static void runTaskLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return;
        }
        if (delayTicks <= 0) {
            runTask(plugin, entity, task);
            return;
        }
        try {
            Object scheduler = entityGetScheduler.invoke(entity);
            entitySchedulerRunDelayed.invoke(scheduler, plugin,
                    (Consumer<Object>) t -> task.run(), null, delayTicks);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[AntiMod] Folia entity scheduler failed: " + e.getMessage());
        }
    }

    /**
     * Runs a task on the next tick on the global region (not tied to any entity).
     * Falls back to Bukkit.getScheduler().runTask() on non-Folia.
     */
    public static void runGlobalTask(Plugin plugin, Runnable task) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        try {
            Object scheduler = serverGetGlobalScheduler.invoke(Bukkit.getServer());
            globalSchedulerRun.invoke(scheduler, plugin,
                    (Consumer<Object>) t -> task.run());
        } catch (Exception e) {
            Bukkit.getLogger().warning("[AntiMod] Folia global scheduler failed: " + e.getMessage());
        }
    }

    /**
     * Runs a task after a delay on the global region.
     * Falls back to Bukkit.getScheduler().runTaskLater() on non-Folia.
     */
    public static void runGlobalTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return;
        }
        if (delayTicks <= 0) {
            runGlobalTask(plugin, task);
            return;
        }
        try {
            Object scheduler = serverGetGlobalScheduler.invoke(Bukkit.getServer());
            globalSchedulerRunDelayed.invoke(scheduler, plugin,
                    (Consumer<Object>) t -> task.run(), delayTicks);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[AntiMod] Folia global scheduler failed: " + e.getMessage());
        }
    }

    /**
     * Runs a repeating task on the global region.
     * Falls back to Bukkit.getScheduler().runTaskTimer() on non-Folia.
     */
    public static void runGlobalTaskTimer(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
            return;
        }
        try {
            Object scheduler = serverGetGlobalScheduler.invoke(Bukkit.getServer());
            long initDelay = Math.max(1, initialDelayTicks);
            long period = Math.max(1, periodTicks);
            globalSchedulerRunAtFixedRate.invoke(scheduler, plugin,
                    (Consumer<Object>) t -> task.run(), initDelay, period);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[AntiMod] Folia global scheduler failed: " + e.getMessage());
        }
    }

    private FoliaScheduler() {}
}
