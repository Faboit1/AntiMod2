package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One outstanding detection attempt.
 *
 * A single sign has up to 4 lines, and each line carries one translation
 * key from one mod. We keep the mapping so the client's UPDATE_SIGN
 * response tells us which mod's key it was and whether it was resolved.
 */
public final class DetectionSession {

    private final UUID playerUuid;
    private final long createdAtMillis;
    private final String fallbackMarker;
    private final Vector3i signPosition;

    /** line index (0..3) -> mod id */
    private final Map<Integer, String> lineToModId = new LinkedHashMap<>();
    /** line index (0..3) -> translation key that was probed */
    private final Map<Integer, String> lineToKey = new LinkedHashMap<>();
    /** line index (0..3) -> what the probe is for; see {@link ProbeKind}. */
    private final Map<Integer, ProbeKind> lineToKind = new HashMap<>();

    /** What a probed line is for, which decides what its answer means. */
    public enum ProbeKind {
        /** A banned mod from config.yml: a translated answer runs its actions. */
        BANNED,
        /** A spoof-mods.yml mod whose channel was seen: a translated answer is the innocent outcome. */
        SPOOF_CHECK,
        /** A mod-catalog.yml mod probed by /antimod mods: only reported, never acted on. */
        CATALOG
    }

    private boolean responded = false;
    /** Set once the sign has been sent; a scan sends its signs in waves. */
    private volatile boolean sent = false;

    public DetectionSession(UUID playerUuid, String fallbackMarker, Vector3i signPosition) {
        this.playerUuid = playerUuid;
        this.createdAtMillis = System.currentTimeMillis();
        this.fallbackMarker = fallbackMarker;
        this.signPosition = signPosition;
    }

    public void addLine(int lineIndex, String modId, String key, ProbeKind kind) {
        lineToModId.put(lineIndex, modId);
        lineToKey.put(lineIndex, key);
        lineToKind.put(lineIndex, kind);
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public String getFallbackMarker() { return fallbackMarker; }
    public Vector3i getSignPosition() { return signPosition; }
    public Map<Integer, String> getLineToModId() { return lineToModId; }
    public Map<Integer, String> getLineToKey() { return lineToKey; }
    public boolean isResponded() { return responded; }
    public void markResponded() { this.responded = true; }
    public boolean isSent() { return sent; }
    public void markSent() { this.sent = true; }

    public String getModAt(int lineIndex) { return lineToModId.get(lineIndex); }
    public String getKeyAt(int lineIndex) { return lineToKey.get(lineIndex); }

    public boolean hasLine(int lineIndex) { return lineToModId.containsKey(lineIndex); }

    public ProbeKind getKindAt(int lineIndex) { return lineToKind.getOrDefault(lineIndex, ProbeKind.BANNED); }
}
