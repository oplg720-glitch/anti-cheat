package dev.aegis.ac;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

final class BaseManager implements Listener {
    private final AegisAC plugin;
    private final File file;
    private final Map<String, BaseRegion> regions = new LinkedHashMap<>();

    BaseManager(AegisAC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bases.yml");
        load();
    }

    record BaseRegion(String name, String world, UUID owner, String ownerName,
                      int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(Location location) {
            if (location == null || location.getWorld() == null || !world.equalsIgnoreCase(location.getWorld().getName())) return false;
            int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
        boolean owns(Player player) { return owner != null && owner.equals(player.getUniqueId()); }
    }

    BaseRegion createRadius(String name, String worldName, int x, int y, int z, int radius,
                            UUID owner, String ownerName) {
        if (radius < 0 || radius > plugin.integer("bases.maximum-radius", 128)) {
            throw new IllegalArgumentException("Radius must be from 0 to " + plugin.integer("bases.maximum-radius", 128) + ".");
        }
        try {
            return createCuboid(name, worldName,
                    Math.subtractExact(x, radius), Math.subtractExact(y, radius), Math.subtractExact(z, radius),
                    Math.addExact(x, radius), Math.addExact(y, radius), Math.addExact(z, radius),
                    owner, ownerName);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Base coordinates are outside the supported integer range.");
        }
    }

    BaseRegion createCuboid(String name, String worldName, int x1, int y1, int z1, int x2, int y2, int z2,
                            UUID owner, String ownerName) {
        validateName(name);
        World world = Bukkit.getWorld(worldName);
        if (world == null) throw new IllegalArgumentException("World is not loaded: " + worldName);
        int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
        if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) {
            throw new IllegalArgumentException("Y bounds must be inside world height " + world.getMinHeight() + ".." + (world.getMaxHeight()-1) + ".");
        }
        long spanX = (long) maxX - minX + 1L;
        long spanY = (long) maxY - minY + 1L;
        long spanZ = (long) maxZ - minZ + 1L;
        long maxVolume = plugin.getConfig().getLong("bases.maximum-volume", 2_000_000L);
        if (maxVolume < 1 || spanX > maxVolume || spanY > maxVolume || spanZ > maxVolume ||
                spanX > maxVolume / spanY || spanX * spanY > maxVolume / spanZ) {
            throw new IllegalArgumentException("Region is too large. Maximum volume: " + maxVolume + " blocks.");
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (regions.containsKey(key)) throw new IllegalArgumentException("A base with that name already exists.");
        BaseRegion region = new BaseRegion(name, world.getName(), owner, ownerName,
                minX, minY, minZ, maxX, maxY, maxZ);
        regions.put(key, region);
        save();
        return region;
    }

    boolean delete(String name) {
        BaseRegion removed = regions.remove(name.toLowerCase(Locale.ROOT));
        if (removed != null) save();
        return removed != null;
    }

    List<String> names() {
        return regions.values().stream().map(BaseRegion::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    List<String> describeAll() {
        return regions.values().stream().map(this::format).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    String describe(String name) {
        BaseRegion region = regions.get(name.toLowerCase(Locale.ROOT));
        return region == null ? null : format(region);
    }

    boolean isProtected(Location location) {
        if (location == null) return false;
        for (BaseRegion region : regions.values()) if (region.contains(location)) return true;
        return false;
    }

    private boolean canEdit(Player player, BaseRegion region) {
        return player.hasPermission("aegis.base.bypass") || player.hasPermission("aegis.admin") || region.owns(player);
    }

    private boolean deny(Player player, Location location, String action) {
        if (location == null) return false;
        for (BaseRegion region : regions.values()) {
            if (region.contains(location) && !canEdit(player, region)) {
                player.sendMessage(plugin.prefix() + plugin.color("&cThis area is protected: &f" + region.name() + " &7(" + action + ")"));
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation(), "break")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (deny(event.getPlayer(), event.getBlockPlaced().getLocation(), "place")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && deny(event.getPlayer(), event.getClickedBlock().getLocation(), "interact")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation(), "bucket placement") ||
                deny(event.getPlayer(), event.getBlockClicked().getLocation(), "bucket use")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation(), "bucket fill") ||
                deny(event.getPlayer(), event.getBlockClicked().getLocation(), "bucket use")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked().getLocation(), "entity interact")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) attacker = p;
        if (attacker != null && deny(attacker, event.getEntity().getLocation(), "combat")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isProtected(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if ((cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
                cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) &&
                isProtected(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isProtected(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (isProtected(event.getToBlock().getLocation()) && !isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonTouchesBase(event.getBlocks(), event.getDirection().getDirection())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonTouchesBase(event.getBlocks(), event.getDirection().getDirection())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player && deny(player, event.getEntity().getLocation(), "hanging entity")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && deny(player, event.getEntity().getLocation(), "hanging place")) {
            event.setCancelled(true);
        }
    }

    private boolean pistonTouchesBase(List<Block> blocks, org.bukkit.util.Vector direction) {
        for (Block block : blocks) {
            Location origin = block.getLocation();
            if (isProtected(origin) ||
                    isProtected(origin.clone().add(direction.getX(), direction.getY(), direction.getZ())) ||
                    isProtected(origin.clone().subtract(direction.getX(), direction.getY(), direction.getZ()))) return true;
        }
        return false;
    }

    private String format(BaseRegion r) {
        return r.name() + " | " + r.world() + " | " +
                r.minX()+","+r.minY()+","+r.minZ()+" -> "+r.maxX()+","+r.maxY()+","+r.maxZ() +
                " | owner=" + (r.ownerName() == null ? "console" : r.ownerName());
    }

    private void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Base name must be 1-32 letters, numbers, _ or -.");
        }
    }

    private void load() {
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("bases");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                UUID owner = null;
                String rawOwner = s.getString("owner-uuid", "");
                if (!rawOwner.isBlank()) owner = UUID.fromString(rawOwner);
                BaseRegion region = new BaseRegion(
                        s.getString("name", key), s.getString("world", "world"), owner,
                        s.getString("owner-name", "unknown"),
                        s.getInt("min-x"), s.getInt("min-y"), s.getInt("min-z"),
                        s.getInt("max-x"), s.getInt("max-y"), s.getInt("max-z"));
                regions.put(key.toLowerCase(Locale.ROOT), region);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid base entry '" + key + "': " + ex.getMessage());
            }
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, BaseRegion> entry : regions.entrySet()) {
            BaseRegion r = entry.getValue();
            String p = "bases." + entry.getKey();
            yaml.set(p + ".name", r.name());
            yaml.set(p + ".world", r.world());
            yaml.set(p + ".owner-uuid", r.owner() == null ? "" : r.owner().toString());
            yaml.set(p + ".owner-name", r.ownerName());
            yaml.set(p + ".min-x", r.minX()); yaml.set(p + ".min-y", r.minY()); yaml.set(p + ".min-z", r.minZ());
            yaml.set(p + ".max-x", r.maxX()); yaml.set(p + ".max-y", r.maxY()); yaml.set(p + ".max-z", r.maxZ());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save bases.yml: " + ex.getMessage());
        }
    }
}
