package dev.aegis.ac;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

final class MeteorChecks implements Listener {
    private final AegisAC plugin;
    private final Map<UUID, Track> data = new ConcurrentHashMap<>();

    MeteorChecks(AegisAC plugin) { this.plugin = plugin; }

    private Track track(Player p) { return data.computeIfAbsent(p.getUniqueId(), id -> new Track()); }

    @EventHandler public void onJoin(PlayerJoinEvent e) { data.put(e.getPlayer().getUniqueId(), new Track()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { data.remove(e.getPlayer().getUniqueId()); }

    @EventHandler public void onTeleport(PlayerTeleportEvent e) {
        Track t = data.get(e.getPlayer().getUniqueId());
        if (t != null) t.clearMovement();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if (exempt(p) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING) ||
                p.hasPotionEffect(PotionEffectType.LEVITATION)) {
            Track existing = data.get(p.getUniqueId());
            if (existing != null) existing.fallStartY = Double.NaN;
            return;
        }
        double dx = e.getTo().getX() - e.getFrom().getX();
        double dy = e.getTo().getY() - e.getFrom().getY();
        double dz = e.getTo().getZ() - e.getFrom().getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 0.02 && Math.abs(dy) < 0.02) return;

        Track t = track(p);
        long now = System.currentTimeMillis();

        // Track legitimate fall height so a landing without expected fall damage can be reviewed.
        if (!p.isOnGround() && !p.isGliding() && !p.isFlying()) {
            if (Double.isNaN(t.fallStartY)) t.fallStartY = e.getFrom().getY();
            t.fallStartY = Math.max(t.fallStartY, e.getTo().getY());
        } else if (p.isOnGround() && !Double.isNaN(t.fallStartY)) {
            double fallDistance = t.fallStartY - e.getTo().getY();
            t.fallStartY = Double.NaN;
            if (plugin.enabled("meteor.nofall.enabled", true) && fallDistance >=
                    plugin.number("meteor.nofall.minimum-fall-distance", 8.0) && !safeLanding(e.getTo())) {
                long landedAt = now;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!p.isOnline()) return;
                    Track current = track(p);
                    if (current.lastFallDamageMillis < landedAt - 1500L) {
                        suspicion(p, current, "NoFall", "fall=" + String.format(Locale.US, "%.1f", fallDistance) +
                                " blocks without server fall damage", 2.0);
                    }
                }, 2L);
            }
        }

        // NoSlow: sustained ground movement while the server confirms active item use.
        if (plugin.enabled("meteor.noslow.enabled", true) && p.hasActiveItem() && p.isOnGround() &&
                horizontal > plugin.number("meteor.noslow.horizontal-move-threshold", 0.31)) {
            t.noSlowTicks++;
            if (t.noSlowTicks >= plugin.integer("meteor.noslow.required-samples", 5)) {
                suspicion(p, t, "NoSlow", "active-item movement=" + String.format(Locale.US, "%.2f", horizontal), 2.0);
                t.noSlowTicks = 0;
            }
        } else t.noSlowTicks = Math.max(0, t.noSlowTicks - 1);

        // Jesus: repeated grounded movement over a liquid surface with no boat/flight.
        Block below = e.getTo().clone().subtract(0, 0.08, 0).getBlock();
        if (plugin.enabled("meteor.jesus.enabled", true) && below.isLiquid() &&
                p.isOnGround() && !p.isInWater() && !p.isInsideVehicle() && horizontal > 0.12) {
            t.jesusTicks++;
            if (t.jesusTicks >= plugin.integer("meteor.jesus.required-samples", 4)) {
                suspicion(p, t, "Jesus/WaterWalk", "grounded movement over liquid", 2.0);
                t.jesusTicks = 0;
            }
        } else t.jesusTicks = Math.max(0, t.jesusTicks - 1);

        // Spider: repeated upward movement while pressed against a solid wall and not on climbable blocks.
        if (plugin.enabled("meteor.spider.enabled", true) && dy > 0.20 && dy < 0.75 &&
                !p.isClimbing() && !p.isOnGround() && horizontal < 0.28 && touchingSolidWall(e.getTo())) {
            t.spiderTicks++;
            if (t.spiderTicks >= plugin.integer("meteor.spider.required-samples", 4)) {
                suspicion(p, t, "Spider", "repeated vertical wall movement", 2.2);
                t.spiderTicks = 0;
            }
        } else t.spiderTicks = Math.max(0, t.spiderTicks - 1);

        // Elytra flight: only extreme movement is flagged; ordinary rocket boosts are allowed.
        if (plugin.enabled("meteor.elytra.enabled", true) && p.isGliding()) {
            double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (distance > plugin.number("meteor.elytra.max-distance-per-move-event", 6.5)) {
                t.elytraTicks++;
                if (t.elytraTicks >= 2) {
                    suspicion(p, t, "ElytraFlight", "single movement delta=" + String.format(Locale.US, "%.2f", distance), 2.0);
                    t.elytraTicks = 0;
                }
            } else t.elytraTicks = Math.max(0, t.elytraTicks - 1);
        }

        t.lastMoveMillis = now;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity target) ||
                p.hasPermission("aegis.bypass")) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;

        Track t = track(p);
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();
        t.attacks.addLast(new AttackSample(nano, target.getUniqueId()));
        trimAttacks(t.attacks, nano - 1_500_000_000L);

        // 360 aura heuristic: repeated damage events to targets far outside the server-side look direction.
        Vector toTarget = target.getLocation().add(0, Math.max(0.2, target.getHeight() * 0.5), 0).toVector()
                .subtract(p.getEyeLocation().toVector());
        double distance = toTarget.length();
        if (distance > 0.001 && distance < 5.2) {
            double angle = Math.toDegrees(p.getEyeLocation().getDirection().angle(toTarget.normalize()));
            if (angle > plugin.number("meteor.killaura.max-look-angle-degrees", 95.0)) {
                t.offAngleAttacks.addLast(now);
            }
        }
        trimLong(t.offAngleAttacks, now - 8000);
        if (plugin.enabled("meteor.killaura.enabled", true) &&
                t.offAngleAttacks.size() >= plugin.integer("meteor.killaura.required-off-angle-attacks", 4)) {
            suspicion(p, t, "KillAura/Rotation", "off-angle hits=" + t.offAngleAttacks.size(), 2.5);
            t.offAngleAttacks.clear();
        }

        // Multi-target attack bursts are suspicious only when many distinct entities are hit in a short packet window.
        Set<UUID> targets = new HashSet<>();
        for (AttackSample sample : t.attacks) targets.add(sample.target());
        if (plugin.enabled("meteor.killaura.enabled", true) &&
                targets.size() >= plugin.integer("meteor.killaura.max-distinct-targets-in-window", 5)) {
            suspicion(p, t, "KillAura/MultiTarget", "distinct targets in 1.5s=" + targets.size(), 2.0);
            t.attacks.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            track(p).lastFallDamageMillis = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("aegis.bypass") || p.getGameMode() == GameMode.CREATIVE ||
                p.getGameMode() == GameMode.SPECTATOR) return;
        Track t = track(p);
        long now = System.currentTimeMillis();

        // AirPlace / Freecam interaction distance.
        double distance = distanceToBox(p.getEyeLocation(), e.getBlockAgainst().getBoundingBox());
        if (plugin.enabled("meteor.airplace.enabled", true) &&
                distance > plugin.number("meteor.airplace.max-interaction-distance", 5.25)) {
            suspicion(p, t, "AirPlace/Freecam", "place-against-distance=" + String.format(Locale.US, "%.2f", distance), 2.2);
        }

        t.placements.addLast(now);
        trimLong(t.placements, now - 2000);
        if (plugin.enabled("meteor.fastplace.enabled", true) &&
                t.placements.size() >= plugin.integer("meteor.fastplace.placements-per-2s", 28)) {
            suspicion(p, t, "FastPlace", "placements/2s=" + t.placements.size(), 2.0);
            t.placements.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("aegis.bypass") || e.getItem() == null) return;
        Material material = e.getItem().getType();
        boolean explosiveCombatItem = material == Material.END_CRYSTAL || material == Material.RESPAWN_ANCHOR ||
                material.name().endsWith("_BED");
        if (!explosiveCombatItem) return;
        Track t = track(p);
        long now = System.currentTimeMillis();
        t.explosiveInteractions.addLast(now);
        trimLong(t.explosiveInteractions, now - 2500);
        if (plugin.enabled("meteor.explosive-aura.enabled", true) &&
                t.explosiveInteractions.size() >= plugin.integer("meteor.explosive-aura.interactions-per-window", 12)) {
            suspicion(p, t, "Crystal/Anchor/BedAura", "explosive-item interactions/2.5s=" + t.explosiveInteractions.size(), 2.0);
            t.explosiveInteractions.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p) || p.hasPermission("aegis.bypass")) return;
        if (!(e.getEntity() instanceof Arrow || e.getEntity() instanceof Trident)) return;
        Track t = track(p);
        long now = System.currentTimeMillis();
        t.projectiles.addLast(now);
        trimLong(t.projectiles, now - 3000);
        if (plugin.enabled("meteor.bow-spam.enabled", true) &&
                t.projectiles.size() >= plugin.integer("meteor.bow-spam.projectiles-per-3s", 8)) {
            suspicion(p, t, "BowSpam/BowAimbot", "projectiles/3s=" + t.projectiles.size(), 1.8);
            t.projectiles.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || e.getPlayer().hasPermission("aegis.bypass")) return;
        Player p = e.getPlayer();
        Track t = track(p);
        long now = System.currentTimeMillis();
        t.fishCatches.addLast(now);
        trimLong(t.fishCatches, now - 180_000);
        if (plugin.enabled("meteor.autofish.enabled", true) &&
                t.fishCatches.size() >= plugin.integer("meteor.autofish.minimum-catches", 7)) {
            List<Long> times = new ArrayList<>(t.fishCatches);
            double cv = coefficientOfVariation(intervals(times));
            if (cv <= plugin.number("meteor.autofish.interval-cv", 0.08)) {
                suspicion(p, t, "AutoFish", "catches=" + times.size() + " interval-CV=" + String.format(Locale.US, "%.3f", cv), 2.0);
                t.fishCatches.clear();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("aegis.bypass") || p.getGameMode() == GameMode.CREATIVE ||
                p.getGameMode() == GameMode.SPECTATOR) return;
        Track t = track(p);
        long now = System.currentTimeMillis();
        Block block = e.getBlock();

        // Freecam / extended interaction range. Only very large distances are considered.
        double distance = distanceToBox(p.getEyeLocation(), block.getBoundingBox());
        if (plugin.enabled("meteor.reach-break.enabled", true) &&
                distance > plugin.number("meteor.reach-break.max-distance", 6.2)) {
            suspicion(p, t, "Freecam/ExtendedBreak", "block-distance=" + String.format(Locale.US, "%.2f", distance), 2.2);
        }

        t.breaks.addLast(new BreakSample(now, block.getX(), block.getY(), block.getZ(), block.getType()));
        trimBreaks(t.breaks, now - 5000);
        if (plugin.enabled("meteor.nuker.enabled", true) &&
                t.breaks.size() >= plugin.integer("meteor.nuker.breaks-per-5s", 45)) {
            long unique = t.breaks.stream().map(b -> b.x()+":"+b.y()+":"+b.z()).distinct().count();
            if (unique >= plugin.integer("meteor.nuker.unique-blocks-per-5s", 35)) {
                suspicion(p, t, "Nuker/PacketMine", "blocks/5s=" + t.breaks.size() + " unique=" + unique, 2.5);
                t.breaks.clear();
            }
        }

        boolean ore = isOre(block.getType());
        if (ore) t.oreBreaks++;
        t.totalBreaks++;
        if (now - t.oreWindowStart >= 60_000) {
            if (plugin.enabled("meteor.xray.enabled", true) &&
                    t.totalBreaks >= plugin.integer("meteor.xray.minimum-blocks", 30) &&
                    t.oreBreaks >= plugin.integer("meteor.xray.minimum-ores", 12)) {
                double ratio = (double)t.oreBreaks / Math.max(1, t.totalBreaks);
                if (ratio >= plugin.number("meteor.xray.ore-ratio-threshold", 0.35)) {
                    suspicion(p, t, "XrayMiningPattern", "ore ratio=" + String.format(Locale.US, "%.2f", ratio) +
                            " (" + t.oreBreaks + "/" + t.totalBreaks + ")", 1.5);
                }
            }
            t.oreWindowStart = now;
            t.totalBreaks = 0;
            t.oreBreaks = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || p.hasPermission("aegis.bypass")) return;
        Track t = track(p);
        long now = System.currentTimeMillis();
        t.inventoryClicks.addLast(now);
        trimLong(t.inventoryClicks, now - 2000);
        if (plugin.enabled("meteor.inventory-automation.enabled", true) &&
                t.inventoryClicks.size() >= plugin.integer("meteor.inventory-automation.clicks-per-2s", 55)) {
            suspicion(p, t, "InventoryAutomation/AutoArmor/AutoTotem", "inventory clicks/2s=" + t.inventoryClicks.size(), 1.5);
            t.inventoryClicks.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("aegis.bypass")) return;
        Track t = track(p);
        long now = System.currentTimeMillis();
        t.handSwaps.addLast(now);
        trimLong(t.handSwaps, now - 2500);
        if (plugin.enabled("meteor.inventory-automation.enabled", true) &&
                t.handSwaps.size() >= plugin.integer("meteor.inventory-automation.swaps-per-2-5s", 12)) {
            suspicion(p, t, "AutoTotem/Offhand", "hand swaps/2.5s=" + t.handSwaps.size(), 1.5);
            t.handSwaps.clear();
        }
    }

    private void suspicion(Player p, Track t, String check, String detail, double amount) {
        long now = System.currentTimeMillis();
        long cooldown = plugin.integer("meteor.alert-cooldown-seconds", 12) * 1000L;
        long last = t.lastAlertByCheck.getOrDefault(check, 0L);
        if (now - last < cooldown) return;
        t.lastAlertByCheck.put(check, now);
        t.suspicionScores.merge(check, amount, Double::sum);
        plugin.baritoneAlert(p.getName(), check, t.suspicionScores.get(check), detail);
    }

    private boolean exempt(Player p) {
        return p.hasPermission("aegis.bypass") || plugin.hasAdminFlight(p) || p.getGameMode() == GameMode.CREATIVE ||
                p.getGameMode() == GameMode.SPECTATOR || p.isFlying() || p.isInsideVehicle() ||
                p.isGliding() || p.isSwimming() || p.isClimbing() || p.isInWater() || p.isRiptiding();
    }

    private boolean safeLanding(Location location) {
        Block feet = location.getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);
        Material a = feet.getType();
        Material b = below.getType();
        if (a.isLiquid() || b.isLiquid()) return true;
        Set<Material> safe = EnumSet.of(Material.SLIME_BLOCK, Material.HONEY_BLOCK, Material.HAY_BLOCK,
                Material.COBWEB, Material.POWDER_SNOW, Material.SCAFFOLDING, Material.LADDER,
                Material.VINE, Material.WEEPING_VINES, Material.TWISTING_VINES,
                Material.SWEET_BERRY_BUSH);
        return safe.contains(a) || safe.contains(b);
    }

    private boolean touchingSolidWall(Location location) {
        Block block = location.getBlock();
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            if (block.getRelative(face).getType().isSolid() ||
                    block.getRelative(face).getRelative(BlockFace.UP).getType().isSolid()) return true;
        }
        return false;
    }

    private double distanceToBox(Location point, BoundingBox box) {
        double x = clamp(point.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(point.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(point.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = point.getX()-x, dy = point.getY()-y, dz = point.getZ()-z;
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }
    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    private boolean isOre(Material m) {
        return switch (m) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                    ANCIENT_DEBRIS, GOLD_ORE, DEEPSLATE_GOLD_ORE, NETHER_GOLD_ORE,
                    REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE, LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> true;
            default -> false;
        };
    }

    private void trimLong(Deque<Long> q, long cutoff) { while (!q.isEmpty() && q.peekFirst() < cutoff) q.removeFirst(); }
    private void trimAttacks(Deque<AttackSample> q, long cutoff) { while (!q.isEmpty() && q.peekFirst().timeNanos() < cutoff) q.removeFirst(); }
    private void trimBreaks(Deque<BreakSample> q, long cutoff) { while (!q.isEmpty() && q.peekFirst().time() < cutoff) q.removeFirst(); }
    private List<Long> intervals(List<Long> times) {
        List<Long> values = new ArrayList<>();
        for (int i=1; i<times.size(); i++) values.add(times.get(i)-times.get(i-1));
        return values;
    }
    private double coefficientOfVariation(List<Long> values) {
        if (values.size() < 2) return 1.0;
        double mean = values.stream().mapToDouble(Long::doubleValue).average().orElse(0);
        if (mean <= 0) return 1.0;
        double variance = values.stream().mapToDouble(v -> (v-mean)*(v-mean)).average().orElse(0);
        return Math.sqrt(variance) / mean;
    }

    private static final class Track {
        final Deque<AttackSample> attacks = new ConcurrentLinkedDeque<>();
        final Deque<Long> offAngleAttacks = new ConcurrentLinkedDeque<>();
        final Deque<Long> placements = new ConcurrentLinkedDeque<>();
        final Deque<Long> explosiveInteractions = new ConcurrentLinkedDeque<>();
        final Deque<Long> projectiles = new ConcurrentLinkedDeque<>();
        final Deque<Long> fishCatches = new ConcurrentLinkedDeque<>();
        final Deque<Long> inventoryClicks = new ConcurrentLinkedDeque<>();
        final Deque<Long> handSwaps = new ConcurrentLinkedDeque<>();
        final Deque<BreakSample> breaks = new ConcurrentLinkedDeque<>();
        final Map<String, Long> lastAlertByCheck = new ConcurrentHashMap<>();
        final Map<String, Double> suspicionScores = new ConcurrentHashMap<>();
        int noSlowTicks, jesusTicks, spiderTicks, elytraTicks;
        long lastMoveMillis;
        long lastFallDamageMillis;
        double fallStartY = Double.NaN;
        long oreWindowStart = System.currentTimeMillis();
        int totalBreaks, oreBreaks;
        void clearMovement() { noSlowTicks = jesusTicks = spiderTicks = elytraTicks = 0; fallStartY = Double.NaN; }
    }
    private record AttackSample(long timeNanos, UUID target) {}
    private record BreakSample(long time, int x, int y, int z, Material material) {}
}
