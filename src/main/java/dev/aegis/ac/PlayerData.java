package dev.aegis.ac;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class PlayerData {
    final UUID uuid;
    final Map<String, Double> violations = new HashMap<>();
    final Deque<TimedPoint> breaks = new ArrayDeque<>();
    final Deque<Long> attacks = new ArrayDeque<>();
    final Deque<TimedPoint> places = new ArrayDeque<>();
    final Deque<TimedPoint> scaffoldPoints = new ArrayDeque<>();
    final Deque<TimedBlockPoint> largeBuildSamples = new ArrayDeque<>();
    final Deque<Long> fastBreakSamples = new ArrayDeque<>();
    final Map<String, DigStart> diggingStarts = new HashMap<>();
    final Deque<Long> farmCycles = new ArrayDeque<>();
    final Map<String, Long> harvestedCrops = new HashMap<>();

    Location lastSafe;
    double horizontalDistanceThisTick;
    int positionMovesThisTick;
    int airTicks;
    int suspiciousTimerTicks;
    long lastAlertMillis;
    long lastLargeSchematicAlertMillis;
    double baritoneSuspicion;
    int totalAttacks;
    int totalSwings;
    long lastDecayMillis = System.currentTimeMillis();

    PlayerData(UUID uuid) { this.uuid = uuid; }

    double totalVl() { return violations.values().stream().mapToDouble(Double::doubleValue).sum(); }
    double highConfidenceVl() {
        return violations.entrySet().stream()
                .filter(e -> switch (e.getKey()) {
                    case "Speed", "Fly", "Reach", "ImpossibleMove", "Timer" -> true;
                    default -> false;
                })
                .mapToDouble(Map.Entry::getValue).sum();
    }

    record TimedPoint(long time, int x, int y, int z) {}
    record TimedBlockPoint(long time, int x, int y, int z, String material) {}
    record DigStart(long startedNanos, double progressPerTick) {}
}
