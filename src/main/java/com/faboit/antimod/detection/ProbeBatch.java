package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One sweep of phantom-sign probes for a single player.
 *
 * A sweep fans out into several signs (one session per sign), and the client
 * answers each one separately. The batch is what ties those answers back
 * together so that a mod spread across several keys — and therefore several
 * signs — still counts as a single offense for the sweep.
 */
public final class ProbeBatch {

    private final UUID playerUuid;
    private final long createdAtMillis = System.currentTimeMillis();
    private final List<DetectionSession> sessions = new CopyOnWriteArrayList<>();

    /** Mods already reported during this sweep, so each one fires its action once. */
    private final Set<String> flaggedMods = ConcurrentHashMap.newKeySet();

    public ProbeBatch(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() { return playerUuid; }

    public List<DetectionSession> getSessions() { return sessions; }

    public void addSession(DetectionSession session) { sessions.add(session); }

    /**
     * Finds the session whose phantom sign sits at {@code pos} and removes it
     * from this batch, or returns null if no session is waiting there.
     */
    public DetectionSession matchAndRemove(Vector3i pos) {
        for (DetectionSession s : sessions) {
            Vector3i sp = s.getSignPosition();
            if (sp.getX() == pos.getX() && sp.getY() == pos.getY() && sp.getZ() == pos.getZ()) {
                // Only the caller that actually removes the session owns it, so a
                // duplicated UPDATE_SIGN cannot punish the same probe twice.
                if (sessions.remove(s)) return s;
                return null;
            }
        }
        return null;
    }

    /**
     * Marks a mod as detected during this sweep. Returns true only the first
     * time, so a mod with several probed keys is punished once per sweep
     * instead of once per key.
     */
    public boolean flagMod(String modId) {
        return flaggedMods.add(modId);
    }

    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - createdAtMillis >= timeoutMillis;
    }

    public boolean isComplete() {
        return sessions.isEmpty();
    }
}
