package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // ---- spoof check ----
    /** Spoof-check mod id -> the registered channel that proved it is installed. */
    private final Map<String, String> spoofCheckChannels = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> spoofKeysProbed = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> spoofKeysAnswered = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> spoofKeysResolved = new ConcurrentHashMap<>();
    /** Keys the client echoed back verbatim even though a fallback was supplied. */
    private final Set<String> rawEchoKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean spoofEvaluated = new AtomicBoolean();

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

    // ------------------------------------------------------------------
    //   Spoof check
    // ------------------------------------------------------------------

    /** Registers a spoof-check mod whose channel was seen, with the keys probed for it. */
    public void expectSpoofCheck(String modId, String channel, List<String> keys) {
        spoofCheckChannels.put(modId, channel);
        spoofKeysProbed.computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet()).addAll(keys);
    }

    public boolean hasSpoofChecks() {
        return !spoofCheckChannels.isEmpty();
    }

    /**
     * Records the client's answer for one spoof-check key.
     *
     * @param resolved the client sent back a real translation
     * @param rawEcho  the client sent back the key itself despite the fallback
     */
    public void recordSpoofAnswer(String modId, String key, boolean resolved, boolean rawEcho) {
        spoofKeysAnswered.computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet()).add(key);
        if (resolved) {
            spoofKeysResolved.computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
        if (rawEcho) rawEchoKeys.add(key);
    }

    /** Records a banned-mod key the client echoed back verbatim. */
    public void recordRawEcho(String key) {
        rawEchoKeys.add(key);
    }

    /**
     * Spoof-check mods whose channel was registered but whose keys all came
     * back untranslated. A mod with any key still unanswered (its sign never
     * came back) is inconclusive and left out.
     */
    public List<String> getHiddenSpoofMods() {
        List<String> hidden = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : spoofKeysProbed.entrySet()) {
            Set<String> answered = spoofKeysAnswered.getOrDefault(e.getKey(), Set.of());
            if (!answered.containsAll(e.getValue())) continue;
            if (spoofKeysResolved.getOrDefault(e.getKey(), Set.of()).isEmpty()) {
                hidden.add(e.getKey());
            }
        }
        return hidden;
    }

    /** Spoof-check mods with at least one key that resolved: proven installed and answering. */
    public List<String> getVerifiedSpoofMods() {
        List<String> verified = new ArrayList<>();
        for (String modId : spoofKeysProbed.keySet()) {
            if (!spoofKeysResolved.getOrDefault(modId, Set.of()).isEmpty()) verified.add(modId);
        }
        return verified;
    }

    public String getSpoofCheckChannel(String modId) {
        return spoofCheckChannels.get(modId);
    }

    public Set<String> getRawEchoKeys() {
        return rawEchoKeys;
    }

    /** True exactly once per sweep, so the spoof verdict is never reached twice. */
    public boolean claimSpoofEvaluation() {
        return spoofEvaluated.compareAndSet(false, true);
    }
}
