package com.faboit.antimod.detection;

import com.github.retrooper.packetevents.util.Vector3i;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
    /**
     * Lines probing a spoof-check mod rather than a banned one. For these the
     * mod id is a spoof-mods.yml id, and a translated answer is the expected,
     * innocent outcome.
     */
    private final Set<Integer> spoofCheckLines = new HashSet<>();

    private boolean responded = false;

    public DetectionSession(UUID playerUuid, String fallbackMarker, Vector3i signPosition) {
        this.playerUuid = playerUuid;
        this.createdAtMillis = System.currentTimeMillis();
        this.fallbackMarker = fallbackMarker;
        this.signPosition = signPosition;
    }

    public void addLine(int lineIndex, String modId, String key, boolean spoofCheck) {
        lineToModId.put(lineIndex, modId);
        lineToKey.put(lineIndex, key);
        if (spoofCheck) spoofCheckLines.add(lineIndex);
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public String getFallbackMarker() { return fallbackMarker; }
    public Vector3i getSignPosition() { return signPosition; }
    public Map<Integer, String> getLineToModId() { return lineToModId; }
    public Map<Integer, String> getLineToKey() { return lineToKey; }
    public boolean isResponded() { return responded; }
    public void markResponded() { this.responded = true; }

    public String getModAt(int lineIndex) { return lineToModId.get(lineIndex); }
    public String getKeyAt(int lineIndex) { return lineToKey.get(lineIndex); }

    public boolean hasLine(int lineIndex) { return lineToModId.containsKey(lineIndex); }

    public boolean isSpoofCheckLine(int lineIndex) { return spoofCheckLines.contains(lineIndex); }
}
