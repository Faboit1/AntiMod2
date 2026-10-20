package com.faboit.antimod.listener;

import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Forwards Paper's "client has loaded the world" event (Paper 1.21.4+) to the
 * join listener. Kept in its own class so servers without the event can still
 * load {@link PlayerJoinListener}.
 */
public final class WorldLoadedListener implements Listener {

    private final PlayerJoinListener joinListener;

    public WorldLoadedListener(PlayerJoinListener joinListener) {
        this.joinListener = joinListener;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClientLoadedWorld(PlayerClientLoadedWorldEvent event) {
        joinListener.onClientLoadedWorld(event.getPlayer());
    }

    /** True when the running server has the event. */
    public static boolean isSupported() {
        try {
            Class.forName("io.papermc.paper.event.player.PlayerClientLoadedWorldEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
