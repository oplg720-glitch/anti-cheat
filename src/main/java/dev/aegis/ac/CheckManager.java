package dev.aegis.ac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-observable anti-cheat checks. No client mod name or client memory is inspected.
 * Baritone checks intentionally report behavioral signatures, not proof of a specific mod.
 */
public final class CheckManager implements Listener {
    private final AegisAC plugin;
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportExemptUntil = new ConcurrentHashMap<>();

    CheckManager(AegisAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void reload() {
        data.clear();
        teleportExemptUntil.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = get(p);
            d.lastSafe = p.getLocation().clone();
        }
    }

    void shutdown() {
        data.clear();
        teleportExemptUntil.clear();
    }

    private PlayerData get(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), PlayerData::new);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        PlayerData d = get(event.getPlayer());
        d.lastSafe = event.getPlayer().getLocation().clone();
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        data.remove(event.getPlayer().getUniqueId());
        teleportExemptUntil.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        teleportExemptUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 1200L);
        PlayerData d = get(event.getPlayer());
        d.horizontalDistanceThisTick = 0;
        d.positionMovesThisTick = 0;
        d.airTicks = 0;
        // Do not combine building/mining behavior from two distant locations.
        d.largeBuildSamples.clear();
        d.places.clear();
        d.scaffoldPoints.clear();
        d.breaks.clear();
        d.diggingStarts.clear();
        if (event.getTo() != null) d.lastSafe = event.getTo().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        Player p = event.getPlayer();
        if (p.hasPermission("aegis.bypass")) return;
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 0.0001 && Math.abs(dy) < 0.0001) return;

        PlayerData d = get(p);
        d.positionMovesThisTick++;
        d.horizontalDistanceThisTick += horizontal;

        if (plugin.enabled("checks.impossible-move.enabled", true) &&
                System.currentTimeMillis() > teleportExemptUntil.getOrDefault(p.getUniqueId(), 0L) &&
                !isExempt(p) && Math.abs(dy) > plugin.number("checks.impossible-move.max-vertical-step", 2.4)) {
            flag(p, "ImpossibleMove", 4.0,
                    String.format(Locale.US, "vertical-step=%.2f", dy), true, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p) || !(event.getEntity() instanceof LivingEntity target)) return;
        if (p.hasPermission("aegis.bypass")) return;
        PlayerData d = get(p);
        long now = System.nanoTime();
        d.attacks.addLast(now);
        trimNanos(d.attacks, now - 2_000_000_000L);
        d.totalAttacks++;

        if (plugin.enabled("checks.reach.enabled", true)) {
            double distance = distanceToBox(p.getEyeLocation(), target.getBoundingBox());
            // Do not use getPing() to alter anti-cheat thresholds: Paper documents that
            // value as unsuitable for anti-cheat decisions. Keep the reach threshold fixed.
            double maxReach = plugin.number("checks.reach.max-reach", 3.20);
            if (distance > maxReach) {
                flag(p, "Reach", Math.min(5.0, (distance - maxReach) * 4.0),
                        String.format(Locale.US, "distance=%.2f max=%.2f", distance, maxReach),
                        false, true);
            }
        }

        if (plugin.enabled("checks.autoclicker.enabled", true)) {
            long cutoff = now - 1_000_000_000L;
            List<Long> recent = new ArrayList<>();
            for (long t : d.attacks) if (t >= cutoff) recent.add(t);
            int threshold = plugin.integer("checks.autoclicker.high-cps-threshold", 18);
            int minSamples = plugin.integer("checks.autoclicker.min-attack-samples", 10);
            if (recent.size() >= threshold && recent.size() >= minSamples) {
                double cv = coefficientOfVariation(intervals(recent));
                if (cv <= plugin.number("checks.autoclicker.regularity-cv-threshold", .075)) {
                    flag(p, "AutoClicker", 1.2,
                            String.format(Locale.US, "attacks/s=%d interval-CV=%.3f", recent.size(), cv), false, false);
                }
            }
        }

        if (plugin.enabled("checks.noswing.enabled", true) && d.totalAttacks >= 20) {
            double ratio = d.totalAttacks == 0 ? 1.0 : (double) d.totalSwings / d.totalAttacks;
            if (ratio < plugin.number("checks.noswing.minimum-swing-ratio", .25) &&
                    d.totalAttacks >= plugin.integer("checks.noswing.min-attacks", 10)) {
                flag(p, "NoSwing", 1.2, String.format(Locale.US, "swing-ratio=%.2f", ratio), false, false);
            }
            d.totalAttacks = 0;
            d.totalSwings = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("aegis.bypass")) return;
        PlayerData d = get(p);
        d.totalSwings++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("aegis.bypass") || p.getGameMode() == GameMode.CREATIVE ||
                p.getGameMode() == GameMode.SPECTATOR || event.getInstaBreak()) return;
        if (!plugin.enabled("checks.fastbreak.enabled", true)) return;

        PlayerData d = get(p);
        long nowNanos = System.nanoTime();
        d.diggingStarts.entrySet().removeIf(entry -> nowNanos - entry.getValue().startedNanos() > 30_000_000_000L);
        if (d.diggingStarts.size() >= 24) {
            String oldest = d.diggingStarts.entrySet().stream()
                    .min(Map.Entry.comparingByValue((a, b) -> Long.compare(a.startedNanos(), b.startedNanos())))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest != null) d.diggingStarts.remove(oldest);
        }
        Block block = event.getBlock();
        double progress = block.getBreakSpeed(p);
        if (!Double.isFinite(progress) || progress <= 0.0 || progress >= 1.0) return;
        d.diggingStarts.put(blockKey(block), new PlayerData.DigStart(nowNanos, progress));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        PlayerData d = data.get(event.getPlayer().getUniqueId());
        if (d != null) d.diggingStarts.remove(blockKey(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("aegis.bypass") || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        PlayerData d = get(p);
        long now = System.currentTimeMillis();
        d.harvestedCrops.entrySet().removeIf(entry -> now - entry.getValue() > 8000L);
        Block b = event.getBlock();
        detectFastBreak(p, d, b);
        d.breaks.addLast(new PlayerData.TimedPoint(now, b.getX(), b.getY(), b.getZ()));
        trimMillis(d.breaks, now - (long)(plugin.number("checks.baritone-mining.max-window-seconds", 18) * 1000));

        if (plugin.enabled("checks.baritone-mining.enabled", true)) detectBaritoneMining(p, d, now);

        // Record a mature crop harvest; a rapid same-block replant sequence is a stronger farm signature.
        if (isCrop(b.getType()) && b.getBlockData() instanceof Ageable age && age.getAge() >= age.getMaximumAge()) {
            d.harvestedCrops.put(key(b.getX(), b.getY(), b.getZ()), now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("aegis.bypass") || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        PlayerData d = get(p);
        long now = System.currentTimeMillis();
        d.harvestedCrops.entrySet().removeIf(entry -> now - entry.getValue() > 8000L);
        Block b = event.getBlockPlaced();
        d.places.addLast(new PlayerData.TimedPoint(now, b.getX(), b.getY(), b.getZ()));
        d.largeBuildSamples.addLast(new PlayerData.TimedBlockPoint(now, b.getX(), b.getY(), b.getZ(), b.getType().name()));
        trimLargeBuild(d, now - (long)(plugin.number("checks.baritone-large-schematic.window-seconds", 30) * 1000));
        trimMillis(d.places, now - (long)(plugin.number("checks.baritone-builder.max-window-seconds", 8) * 1000));

        if (plugin.enabled("checks.fastplace.enabled", true)) {
            long window = (long)(plugin.number("checks.fastplace.window-seconds", 5) * 1000);
            int count = countSince(d.places, now - window);
            if (count >= plugin.integer("checks.fastplace.minimum-placements", 12)) {
                List<Long> times = timesSince(d.places, now - window);
                double cv = coefficientOfVariation(intervals(times));
                if (cv < .20) baritoneFlag(p, "FastPlace/Builder", 1.0,
                        String.format(Locale.US, "places=%d interval-CV=%.3f", count, cv));
            }
        }

        if (plugin.enabled("checks.baritone-builder.enabled", true)) detectBaritoneBuilder(p, d, now);
        if (plugin.enabled("checks.baritone-large-schematic.enabled", true)) detectLargeSchematic(p, d, now);
        if (plugin.enabled("checks.scaffold.enabled", true)) detectScaffold(p, d, b, now);
        if (plugin.enabled("checks.baritone-farm.enabled", true)) detectFarmCycle(p, d, b, now);
    }

    private void detectBaritoneMining(Player p, PlayerData d, long now) {
        int min = plugin.integer("checks.baritone-mining.min-break-samples", 10);
        if (d.breaks.size() < min) return;
        List<PlayerData.TimedPoint> points = new ArrayList<>(d.breaks);
        points = points.subList(Math.max(0, points.size() - min), points.size());
        long span = points.get(points.size() - 1).time() - points.get(0).time();
        if (span <= 0 || span > plugin.number("checks.baritone-mining.max-window-seconds", 18) * 1000) return;
        double cv = coefficientOfVariation(intervalsPoints(points));
        double linearity = linearity(points);
        if (cv <= plugin.number("checks.baritone-mining.timing-cv-threshold", .24) &&
                linearity >= plugin.number("checks.baritone-mining.linearity-threshold", .82)) {
            if (now - d.lastAlertMillis > 8000) {
                baritoneFlag(p, "Baritone-Mine", 2.0,
                        String.format(Locale.US, "breaks=%d timing-CV=%.3f linearity=%.2f", points.size(), cv, linearity));
                d.lastAlertMillis = now;
            }
        }
    }

    private void detectBaritoneBuilder(Player p, PlayerData d, long now) {
        int min = plugin.integer("checks.baritone-builder.min-place-samples", 12);
        if (d.places.size() < min) return;
        List<PlayerData.TimedPoint> points = new ArrayList<>(d.places);
        points = points.subList(Math.max(0, points.size() - min), points.size());
        long span = points.get(points.size() - 1).time() - points.get(0).time();
        if (span <= 0 || span > plugin.number("checks.baritone-builder.max-window-seconds", 8) * 1000) return;
        double cv = coefficientOfVariation(intervalsPoints(points));
        double linearity = linearity(points);
        if (cv <= plugin.number("checks.baritone-builder.timing-cv-threshold", .22) && linearity >= .65) {
            if (now - d.lastAlertMillis > 8000) {
                baritoneFlag(p, "Baritone-Builder/Schematic", 1.8,
                        String.format(Locale.US, "placements=%d timing-CV=%.3f linearity=%.2f", points.size(), cv, linearity));
                d.lastAlertMillis = now;
            }
        }
    }

    private void detectLargeSchematic(Player p, PlayerData d, long now) {
        int minBlocks = plugin.integer("checks.baritone-large-schematic.minimum-block-placements", 48);
        if (d.largeBuildSamples.size() < minBlocks) return;

        List<PlayerData.TimedBlockPoint> points = new ArrayList<>(d.largeBuildSamples);
        long first = points.get(0).time();
        long last = points.get(points.size() - 1).time();
        long spanMs = last - first;
        if (spanMs <= 0 || spanMs > plugin.number("checks.baritone-large-schematic.window-seconds", 30) * 1000) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Set<String> occupiedCells = new HashSet<>();
        Set<String> uniqueBlocks = new HashSet<>();
        List<Long> times = new ArrayList<>();
        for (PlayerData.TimedBlockPoint point : points) {
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y()); maxY = Math.max(maxY, point.y());
            minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
            occupiedCells.add(Math.floorDiv(point.x(), 4) + ":" + Math.floorDiv(point.y(), 4) + ":" + Math.floorDiv(point.z(), 4));
            uniqueBlocks.add(point.x() + ":" + point.y() + ":" + point.z());
            times.add(point.time());
        }

        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        int minimumSpan = plugin.integer("checks.baritone-large-schematic.minimum-axis-span", 5);
        int expandedAxes = (sx >= minimumSpan ? 1 : 0) + (sy >= minimumSpan ? 1 : 0) + (sz >= minimumSpan ? 1 : 0);
        int longest = Math.max(sx, Math.max(sy, sz));
        int minLongest = plugin.integer("checks.baritone-large-schematic.minimum-longest-span", 12);
        int minCells = plugin.integer("checks.baritone-large-schematic.minimum-spatial-cells", 5);
        if (expandedAxes < 2 && longest < minLongest) return;
        if (occupiedCells.size() < minCells || uniqueBlocks.size() < minBlocks) return;

        double seconds = spanMs / 1000.0;
        double placementsPerSecond = (points.size() - 1) / seconds;
        if (placementsPerSecond < plugin.number("checks.baritone-large-schematic.minimum-placements-per-second", 1.8)) return;
        double cv = coefficientOfVariation(intervals(times));
        if (cv > plugin.number("checks.baritone-large-schematic.maximum-timing-cv", .55)) return;

        long cooldown = (long)(plugin.number("checks.baritone-large-schematic.alert-cooldown-seconds", 20) * 1000);
        if (now - d.lastLargeSchematicAlertMillis < cooldown) return;
        String detail = String.format(Locale.US,
                "blocks=%d span=%dx%dx%d cells=%d rate=%.1f/s timing-CV=%.2f",
                uniqueBlocks.size(), sx, sy, sz, occupiedCells.size(), placementsPerSecond, cv);
        baritoneFlag(p, "Large-Schematic/Builder", plugin.number("checks.baritone-large-schematic.suspicion-points", 3.0), detail);
        d.lastLargeSchematicAlertMillis = now;
    }

    private void detectScaffold(Player p, PlayerData d, Block placed, long now) {
        Location loc = p.getLocation();
        int footY = loc.getBlockY() - 1;
        boolean nearFoot = Math.abs(placed.getY() - footY) <= 1 &&
                Math.abs(placed.getX() - loc.getBlockX()) <= 1 &&
                Math.abs(placed.getZ() - loc.getBlockZ()) <= 1;
        if (!nearFoot || p.isOnGround() || p.isGliding() || p.isFlying() || p.isInsideVehicle()) return;

        d.scaffoldPoints.addLast(new PlayerData.TimedPoint(now,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        trimMillis(d.scaffoldPoints, now - (long)(plugin.number("checks.scaffold.window-seconds", 4) * 1000));
        int minPlacements = plugin.integer("checks.scaffold.minimum-underfoot-placements", 5);
        if (d.scaffoldPoints.size() < minPlacements) return;

        List<PlayerData.TimedPoint> recent = new ArrayList<>(d.scaffoldPoints);
        PlayerData.TimedPoint first = recent.get(0);
        PlayerData.TimedPoint last = recent.get(recent.size() - 1);
        double travel = Math.hypot(last.x() - first.x(), last.z() - first.z());
        double pathLinearity = linearity(recent);
        double minTravel = plugin.number("checks.scaffold.minimum-horizontal-travel", 3.5);
        if (travel >= minTravel && pathLinearity >= plugin.number("checks.scaffold.minimum-path-linearity", .55)) {
            baritoneFlag(p, "Scaffold/Bridge", 2.0,
                    String.format(Locale.US, "underfoot=%d travel=%.2f linearity=%.2f", recent.size(), travel, pathLinearity));
            d.scaffoldPoints.clear();
        }
    }

    private void detectFastBreak(Player p, PlayerData d, Block block) {
        if (!plugin.enabled("checks.fastbreak.enabled", true)) return;
        PlayerData.DigStart start = d.diggingStarts.remove(blockKey(block));
        if (start == null) return;
        long elapsedNanos = System.nanoTime() - start.startedNanos();
        double expectedTicks = Math.ceil(1.0 / start.progressPerTick());
        double observedTicks = elapsedNanos / 50_000_000.0;
        double minTicks = plugin.number("checks.fastbreak.minimum-expected-ticks", 4.0);
        double ratio = plugin.number("checks.fastbreak.minimum-observed-to-expected-ratio", .62);
        if (expectedTicks < minTicks || observedTicks >= expectedTicks * ratio) return;

        long now = System.currentTimeMillis();
        d.fastBreakSamples.addLast(now);
        trimLongs(d.fastBreakSamples, now - (long)(plugin.number("checks.fastbreak.sample-window-seconds", 12) * 1000));
        int required = plugin.integer("checks.fastbreak.minimum-suspect-breaks", 3);
        if (d.fastBreakSamples.size() >= required) {
            flag(p, "FastBreak", 2.0, String.format(Locale.US,
                    "suspect-breaks=%d expected=%.1f ticks observed=%.2f ticks", d.fastBreakSamples.size(), expectedTicks, observedTicks), false, false);
            d.fastBreakSamples.clear();
        }
    }

    private void trimLargeBuild(PlayerData d, long cutoff) {
        while (!d.largeBuildSamples.isEmpty() && d.largeBuildSamples.peekFirst().time() < cutoff) {
            d.largeBuildSamples.removeFirst();
        }
        // Keep memory bounded even if a player has a long uninterrupted build session.
        while (d.largeBuildSamples.size() > 1200) d.largeBuildSamples.removeFirst();
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + key(block.getX(), block.getY(), block.getZ());
    }

    private void detectFarmCycle(Player p, PlayerData d, Block placed, long now) {
        if (!(placed.getBlockData() instanceof Ageable)) return;
        if (!isCrop(placed.getType())) return;
        String key = key(placed.getX(), placed.getY(), placed.getZ());
        Long harvestedAt = d.harvestedCrops.remove(key);
        if (harvestedAt == null || now - harvestedAt > 8000) return;
        d.farmCycles.addLast(now);
        trimLongs(d.farmCycles, now - (long)(plugin.number("checks.baritone-farm.window-seconds", 35) * 1000));
        int minCycles = plugin.integer("checks.baritone-farm.min-harvest-replant-cycles", 6);
        if (d.farmCycles.size() >= minCycles) {
            baritoneFlag(p, "Baritone-Farm", 2.0, "harvest-replant-cycles=" + d.farmCycles.size());
            d.farmCycles.clear();
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = get(p);
            if (p.hasPermission("aegis.bypass")) {
                d.horizontalDistanceThisTick = 0;
                d.positionMovesThisTick = 0;
                continue;
            }

            boolean exempt = isExempt(p) || now < teleportExemptUntil.getOrDefault(p.getUniqueId(), 0L);
            boolean excessiveMovement = false;
            if (!exempt && plugin.enabled("checks.movement.enabled", true)) {
                double limit = plugin.number("checks.movement.max-horizontal-distance-per-tick", 1.28);
                if (p.hasPotionEffect(PotionEffectType.SPEED)) {
                    int amp = p.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
                    limit += amp * plugin.number("checks.movement.speed-potion-extra-per-level", .16);
                }
                if (d.horizontalDistanceThisTick > limit) {
                    excessiveMovement = true;
                    flag(p, "Speed", 2.0,
                            String.format(Locale.US, "horizontal/tick=%.3f limit=%.3f", d.horizontalDistanceThisTick, limit),
                            true, true);
                }
            }

            if (!exempt && plugin.enabled("checks.timer.enabled", true)) {
                if (d.positionMovesThisTick > plugin.integer("checks.timer.max-position-moves-per-tick", 28)) {
                    d.suspiciousTimerTicks++;
                    if (d.suspiciousTimerTicks >= plugin.integer("checks.timer.suspicious-ticks-required", 5)) {
                        flag(p, "Timer", 2.0, "position-moves/tick=" + d.positionMovesThisTick, true, true);
                        d.suspiciousTimerTicks = 0;
                    }
                } else {
                    d.suspiciousTimerTicks = Math.max(0, d.suspiciousTimerTicks - 1);
                }
            }

            if (!exempt && plugin.enabled("checks.fly.enabled", true)) {
                if (!p.isOnGround()) d.airTicks++; else d.airTicks = 0;
                double vy = p.getVelocity().getY();
                boolean slowFall = p.hasPotionEffect(PotionEffectType.SLOW_FALLING) ||
                        p.hasPotionEffect(PotionEffectType.LEVITATION);
                if (!slowFall && !p.isOnGround() && d.airTicks > plugin.integer("checks.fly.max-air-ticks", 38) &&
                        Math.abs(vy) < plugin.number("checks.fly.hover-velocity-epsilon", .012)) {
                    flag(p, "Fly", 2.5, String.format(Locale.US, "air-ticks=%d vertical-velocity=%.4f", d.airTicks, vy),
                            true, true);
                }
            } else {
                d.airTicks = 0;
            }

            if (p.isOnGround() && !exempt && !excessiveMovement) {
                d.lastSafe = p.getLocation().clone();
            }

            d.horizontalDistanceThisTick = 0;
            d.positionMovesThisTick = 0;
            decay(d, now);
        }
    }

    private void decay(PlayerData d, long now) {
        long elapsed = now - d.lastDecayMillis;
        if (elapsed < 1000) return;
        double seconds = elapsed / 1000.0;
        double amount = seconds * plugin.number("general.violation-decay-per-second", .20);
        d.violations.replaceAll((k, v) -> Math.max(0, v - amount));
        d.baritoneSuspicion = Math.max(0, d.baritoneSuspicion - seconds * plugin.number("baritone.suspicion-decay-per-second", .10));
        d.lastDecayMillis = now;
    }

    private boolean isExempt(Player p) {
        return plugin.hasAdminFlight(p) || p.getGameMode() == GameMode.CREATIVE ||
                p.getGameMode() == GameMode.SPECTATOR || p.isFlying() || p.isGliding() || p.isInsideVehicle() ||
                p.isSwimming() || p.isClimbing() || p.isInWater();
    }

    private double distanceToBox(Location eye, BoundingBox box) {
        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = eye.getX() - x, dy = eye.getY() - y, dz = eye.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    private boolean isCrop(Material m) {
        return switch (m) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, SWEET_BERRY_BUSH -> true;
            default -> false;
        };
    }

    private String key(int x, int y, int z) { return x + ":" + y + ":" + z; }

    private void flag(Player p, String check, double amount, String detail, boolean setback, boolean banEligible) {
        PlayerData d = get(p);
        d.violations.merge(check, amount, Double::sum);
        double vl = d.violations.get(check);
        plugin.alert(p.getName(), check, vl, detail);
        if (setback && plugin.enabled("general.setback", true) && vl >= 7 && d.lastSafe != null) {
            Location back = d.lastSafe.clone();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) p.teleport(back);
            });
        }
        if (banEligible && plugin.enabled("general.auto-ban", true) &&
                d.highConfidenceVl() >= plugin.number("general.max-high-confidence-vl-before-ban", 22.0)) {
            plugin.banPlayer(p, "AegisAC high-confidence violations: " + check + " / " + detail);
            reset(p);
        }
    }

    private void baritoneFlag(Player p, String check, double amount, String detail) {
        PlayerData d = get(p);
        d.baritoneSuspicion += amount;
        if (plugin.enabled("baritone.alert-on-suspicion", true)) plugin.baritoneAlert(p.getName(), check, d.baritoneSuspicion, detail);
        if (plugin.enabled("baritone.auto-ban", false) &&
                d.baritoneSuspicion >= plugin.number("baritone.auto-ban-suspicion-score", 20.0)) {
            plugin.banPlayer(p, "Suspected prohibited automation: " + check + " / " + detail);
            reset(p);
        }
    }

    private List<Long> intervals(List<Long> times) {
        List<Long> values = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) values.add(times.get(i) - times.get(i - 1));
        return values;
    }

    private List<Long> intervalsPoints(List<PlayerData.TimedPoint> points) {
        List<Long> values = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) values.add(points.get(i).time() - points.get(i - 1).time());
        return values;
    }

    private double coefficientOfVariation(List<Long> values) {
        if (values.size() < 2) return 1.0;
        double mean = values.stream().mapToDouble(Long::doubleValue).average().orElse(0);
        if (mean <= 0) return 1.0;
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(variance) / mean;
    }

    private double linearity(List<PlayerData.TimedPoint> points) {
        if (points.size() < 3) return 0;
        PlayerData.TimedPoint first = points.get(0), last = points.get(points.size() - 1);
        double net = Math.abs(last.x() - first.x()) + Math.abs(last.y() - first.y()) + Math.abs(last.z() - first.z());
        double path = 0;
        for (int i = 1; i < points.size(); i++) {
            PlayerData.TimedPoint a = points.get(i - 1), b = points.get(i);
            path += Math.abs(b.x() - a.x()) + Math.abs(b.y() - a.y()) + Math.abs(b.z() - a.z());
        }
        return path <= 0 ? 0 : Math.min(1.0, net / path);
    }

    private int countSince(Deque<PlayerData.TimedPoint> points, long since) {
        int count = 0;
        for (PlayerData.TimedPoint point : points) if (point.time() >= since) count++;
        return count;
    }

    private List<Long> timesSince(Deque<PlayerData.TimedPoint> points, long since) {
        List<Long> values = new ArrayList<>();
        for (PlayerData.TimedPoint point : points) if (point.time() >= since) values.add(point.time());
        return values;
    }

    private void trimNanos(Deque<Long> values, long cutoff) { while (!values.isEmpty() && values.peekFirst() < cutoff) values.removeFirst(); }
    private void trimLongs(Deque<Long> values, long cutoff) { while (!values.isEmpty() && values.peekFirst() < cutoff) values.removeFirst(); }
    private void trimMillis(Deque<PlayerData.TimedPoint> values, long cutoff) { while (!values.isEmpty() && values.peekFirst().time() < cutoff) values.removeFirst(); }

    double totalVl(Player p) { return get(p).totalVl(); }
    double baritoneSuspicion(Player p) { return get(p).baritoneSuspicion; }
    void reset(Player p) { data.put(p.getUniqueId(), new PlayerData(p.getUniqueId())); }

    String snapshot(Player p) {
        PlayerData d = get(p);
        String v = d.violations.entrySet().stream()
                .filter(e -> e.getValue() > .01)
                .map(e -> e.getKey() + "=" + String.format(Locale.US, "%.1f", e.getValue()))
                .sorted().reduce((a, b) -> a + ", " + b).orElse("none");
        return v + " | Baritone-suspicion=" + String.format(Locale.US, "%.1f", d.baritoneSuspicion);
    }

    String debug(Player p) {
        PlayerData d = get(p);
        return "ground=" + p.isOnGround() + ", airTicks=" + d.airTicks +
                ", breakSamples=" + d.breaks.size() + ", placeSamples=" + d.places.size() +
                ", schematicSamples=" + d.largeBuildSamples.size() + ", fastBreakSamples=" + d.fastBreakSamples.size() +
                ", baritoneSuspicion=" + String.format(Locale.US, "%.1f", d.baritoneSuspicion) +
                ", highConfidenceVL=" + String.format(Locale.US, "%.1f", d.highConfidenceVl());
    }
}
