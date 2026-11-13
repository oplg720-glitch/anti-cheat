package dev.aegis.ac;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional PacketEvents integration. The Netty callback only increments atomics;
 * Bukkit API and staff notifications are used from the primary server thread.
 */
final class PacketGuard implements PacketListener {
    private final AegisAC plugin;
    private final Map<UUID, Counters> counters = new ConcurrentHashMap<>();
    private volatile boolean active;

    PacketGuard(AegisAC plugin) {
        this.plugin = plugin;
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            plugin.getLogger().warning("PacketEvents not found: packet-level checks are disabled. Install PacketEvents 2.14.0+.");
            return;
        }
        try {
            PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
            active = true;
            Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 20L, 20L);
            plugin.getLogger().info("Packet-level monitoring enabled through PacketEvents.");
        } catch (Throwable ex) {
            active = false;
            plugin.getLogger().warning("Could not initialize PacketEvents integration: " + ex.getClass().getSimpleName());
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!active || event.getUser() == null || event.getUser().getUUID() == null) return;
        UUID id = event.getUser().getUUID();
        String type = String.valueOf(event.getPacketType()).toUpperCase(Locale.ROOT);
        Counters c = counters.computeIfAbsent(id, ignored -> new Counters());
        c.total.incrementAndGet();

        if (type.contains("PLAYER_POSITION") || type.contains("PLAYER_ROTATION") ||
                type.contains("PLAYER_FLYING") || type.equals("FLYING")) {
            c.movement.incrementAndGet();
        }
        if (type.contains("INTERACT_ENTITY")) {
            c.entityInteract.incrementAndGet();
            // Through 1.21.11, right-click and attack share one packet. Read its
            // action so AttackPackets measures attacks rather than every interaction.
            try {
                String action = String.valueOf(new WrapperPlayClientInteractEntity(event).getAction());
                if ("ATTACK".equalsIgnoreCase(action)) c.attacks.incrementAndGet();
            } catch (Throwable ignored) {
                // Keep the generic entity-interaction count even if a protocol wrapper changes.
            }
        }
        // Minecraft 26.1+ has a dedicated ATTACK packet; older versions use INTERACT_ENTITY.
        if (type.endsWith(".ATTACK") || type.equals("ATTACK")) c.attacks.incrementAndGet();
        if (type.contains("CONTAINER_CLICK") || type.contains("CLICK_WINDOW") || type.contains("WINDOW_CLICK")) {
            c.inventory.incrementAndGet();
        }
        if (type.contains("PLAYER_DIGGING") || type.contains("PLAYER_ACTION")) {
            c.digging.incrementAndGet();
        }
        if (type.contains("USE_ITEM") || type.contains("BLOCK_PLACEMENT") || type.contains("USE_ITEM_ON")) {
            c.use.incrementAndGet();
        }
    }

    private void flush() {
        if (!active) return;
        for (Map.Entry<UUID, Counters> entry : counters.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Counters c = entry.getValue();
            int total = c.total.getAndSet(0);
            int movement = c.movement.getAndSet(0);
            int interactions = c.entityInteract.getAndSet(0);
            int attacks = c.attacks.getAndSet(0);
            int inventory = c.inventory.getAndSet(0);
            int digging = c.digging.getAndSet(0);
            int use = c.use.getAndSet(0);
            if (player == null) {
                counters.remove(entry.getKey());
                continue;
            }
            if (player.hasPermission("aegis.bypass")) continue;

            check(player, c, "MovementPackets", movement,
                    plugin.integer("packets.movement-per-second", 72), "movement packets/s");
            check(player, c, "EntityInteractionPackets", interactions,
                    plugin.integer("packets.entity-interactions-per-second", 48), "entity interaction packets/s");
            check(player, c, "AttackPackets", attacks,
                    plugin.integer("packets.attacks-per-second", 24), "attack packets/s");
            check(player, c, "InventoryPackets", inventory,
                    plugin.integer("packets.inventory-per-second", 90), "inventory packets/s");
            check(player, c, "DiggingPackets", digging,
                    plugin.integer("packets.digging-per-second", 72), "digging packets/s");
            check(player, c, "UsePackets", use,
                    plugin.integer("packets.use-per-second", 100), "use packets/s");
            check(player, c, "TotalPackets", total,
                    plugin.integer("packets.total-per-second", 320), "client packets/s");
        }
    }

    private void check(Player player, Counters counters, String check, int count, int threshold, String label) {
        if (count >= threshold) counters.overLimit.merge(check, 1, Integer::sum);
        else counters.overLimit.put(check, 0);
        int consecutive = counters.overLimit.getOrDefault(check, 0);
        int required = plugin.integer("packets.consecutive-windows", 2);
        long now = System.currentTimeMillis();
        long previous = counters.lastAlert.getOrDefault(check, 0L);
        long cooldown = plugin.integer("packets.alert-cooldown-seconds", 15) * 1000L;
        if (consecutive >= required && now - previous >= cooldown) {
            counters.lastAlert.put(check, now);
            counters.overLimit.put(check, 0);
            plugin.packetAlert(player, check, label + "=" + count + " (limit=" + threshold + ")");
        }
    }

    boolean isActive() { return active; }

    void shutdown() {
        if (active) {
            try { PacketEvents.getAPI().getEventManager().unregisterListener(this); }
            catch (Throwable ignored) { }
        }
        active = false;
        counters.clear();
    }

    private static final class Counters {
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger movement = new AtomicInteger();
        final AtomicInteger entityInteract = new AtomicInteger();
        final AtomicInteger attacks = new AtomicInteger();
        final AtomicInteger inventory = new AtomicInteger();
        final AtomicInteger digging = new AtomicInteger();
        final AtomicInteger use = new AtomicInteger();
        final Map<String, Integer> overLimit = new ConcurrentHashMap<>();
        final Map<String, Long> lastAlert = new ConcurrentHashMap<>();
    }
}
