package com.faboit.antimod.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;

/**
 * Utility for scheduling tasks that works on both Bukkit and Folia.
 * Automatically uses Folia's region scheduler when available, falls back to Bukkit.
 */
public final class FoliaScheduler {

    private static final boolean FOLIA_AVAILABLE;
    private static final Method SCHEDULE_DELAYED_TASK_METHOD;
    private static final Method GET_REGION_SCHEDULER_METHOD;

    static {
        boolean foliaAvailable = false;
        Method scheduleMethod = null;
        Method getRegionMethod = null;

        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            foliaAvailable = true;

            Class<?> serverClass = Class.forName("org.bukkit.Server");
            getRegionMethod = serverClass.getMethod("getRegionScheduler");

            Class<?> regionSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            scheduleMethod = regionSchedulerClass.getMethod("execute", Plugin.class, java.lang.Runnable.class, int.class, int.class, long.class);
        } catch (Exception ignored) {
            foliaAvailable = false;
        }

        FOLIA_AVAILABLE = foliaAvailable;
        SCHEDULE_DELAYED_TASK_METHOD = scheduleMethod;
        GET_REGION_SCHEDULER_METHOD = getRegionMethod;
    }

    public static boolean isFoliaAvailable() {
        return FOLIA_AVAILABLE;
    }

    /**
     * Schedules a task to run after a delay (in ticks).
     * On Folia, uses the region scheduler for the given player's region.
     * On Bukkit, uses the standard scheduler.
     */
    public static void runTaskLater(Plugin plugin, Player player, Runnable task, long delayTicks) {
        if (!FOLIA_AVAILABLE || player == null) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return;
        }

        try {
            Object regionScheduler = GET_REGION_SCHEDULER_METHOD.invoke(Bukkit.getServer());
            SCHEDULE_DELAYED_TASK_METHOD.invoke(
                    regionScheduler,
                    plugin,
                    task,
                    player.getChunk().getX(),
                    player.getChunk().getZ(),
                    delayTicks
            );
        } catch (Exception e) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    /**
     * Schedules a repeating task.
     * On Folia, uses the region scheduler for the given player's region.
     * On Bukkit, uses the standard scheduler.
     */
    public static void runTaskTimer(Plugin plugin, Player player, Runnable task, long initialDelayTicks, long repeatTicks) {
        if (!FOLIA_AVAILABLE || player == null) {
            Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, repeatTicks);
            return;
        }

        try {
            Object regionScheduler = GET_REGION_SCHEDULER_METHOD.invoke(Bukkit.getServer());
            Method repeatMethod = regionScheduler.getClass().getMethod("scheduleAtFixedRate", Plugin.class, java.util.function.Consumer.class, int.class, int.class, long.class, long.class);
            repeatMethod.invoke(
                    regionScheduler,
                    plugin,
                    (java.util.function.Consumer<Object>) ctx -> task.run(),
                    player.getChunk().getX(),
                    player.getChunk().getZ(),
                    initialDelayTicks,
                    repeatTicks
            );
        } catch (Exception e) {
            Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, repeatTicks);
        }
    }

    /**
     * Schedules a global task (not tied to a specific player/region).
     * On Folia, uses the global region scheduler.
     * On Bukkit, uses the standard scheduler.
     */
    public static void runTaskLaterGlobal(Plugin plugin, Runnable task, long delayTicks) {
        if (!FOLIA_AVAILABLE) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return;
        }

        try {
            Object regionScheduler = GET_REGION_SCHEDULER_METHOD.invoke(Bukkit.getServer());
            SCHEDULE_DELAYED_TASK_METHOD.invoke(
                    regionScheduler,
                    plugin,
                    task,
                    0,
                    0,
                    delayTicks
            );
        } catch (Exception e) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    private FoliaScheduler() {}
}
