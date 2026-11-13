package dev.aegis.ac;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Automatic anti-duplication layer.
 * Item serials are placed only on non-stackable items, so normal stack merging is not broken.
 * Inventory conservation audits run automatically on supported player/container/hopper
 * transfer events. Loaded-chunk scans continuously check signed item serials. Unknown-origin
 * duplicates are retained and reported rather than guessing which copy is legitimate.
 */
final class DupeGuard implements Listener {
    private final AegisAC plugin;
    private final NamespacedKey serialKey;
    private final NamespacedKey signatureKey;
    private final File ledgerFile;
    private final File quarantineFile;
    private final File incidentFile;
    private final File secretFile;
    private final File installMarkerFile;
    private final Map<String, SerialRecord> ledger = new HashMap<>();
    private final Map<String, Integer> duplicateRounds = new HashMap<>();
    private final Map<UUID, ArrayDeque<PickupSample>> pickupWindows = new HashMap<>();
    private byte[] secret;
    private boolean secretTrusted;
    private Iterator<Chunk> chunkIterator;
    private Map<String, List<Occurrence>> roundOccurrences;
    private Set<Inventory> roundInventories;
    private final Map<UUID, ArrayDeque<Long>> inventoryClickWindows = new HashMap<>();
    private final Set<UUID> pendingPlayerScans = new HashSet<>();
    private final Map<UUID, Set<Inventory>> pendingPlayerInventories = new HashMap<>();
    // One identity-based registry coordinates every rollback-eligible transaction type.
    // If two events touch the same player, item or inventory in the same server tick,
    // both snapshots become non-isolated and are not allowed to overwrite each other.
    private final Map<Integer, IdentityHashMap<Object, Integer>> transactionParticipants = new HashMap<>();
    private final Set<Integer> scheduledTransactionCleanup = new HashSet<>();
    private final Map<String, Integer> canonicalSingleRounds = new HashMap<>();
    private final Set<String> pendingChunkScans = new HashSet<>();
    private final Set<UUID> pendingLooseItemScans = new HashSet<>();
    private long nextRoundAt;
    private long scannedStacks;
    private int nextQuarantineId = 1;
    private int nextIncidentId = 1;

    DupeGuard(AegisAC plugin) {
        this.plugin = plugin;
        this.serialKey = new NamespacedKey(plugin, "item_serial");
        this.signatureKey = new NamespacedKey(plugin, "item_signature");
        this.ledgerFile = new File(plugin.getDataFolder(), "dupe-ledger.yml");
        this.quarantineFile = new File(plugin.getDataFolder(), "dupe-quarantine.yml");
        this.incidentFile = new File(plugin.getDataFolder(), "dupe-incidents.yml");
        this.secretFile = new File(plugin.getDataFolder(), "dupe-secret.key");
        this.installMarkerFile = new File(plugin.getDataFolder(), "dupe-install.marker");
        loadSecret();
        loadLedger();
        nextQuarantineId = maxNumericKey(quarantineFile, "items") + 1;
        nextIncidentId = maxNumericKey(incidentFile, "incidents") + 1;
        auditPaperDupeSettings();
        Bukkit.getScheduler().runTaskTimer(plugin, this::scanTick, 20L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.enabled("dupe.enabled", true)) schedulePlayerScan(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pickupWindows.remove(id);
        inventoryClickWindows.remove(id);
        pendingPlayerScans.remove(id);
        pendingPlayerInventories.remove(id);
    }

    // Reject impossible overstack transactions before the server commits them.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardClick(InventoryClickEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (!(event.getWhoClicked() instanceof Player player) || player.hasPermission("aegis.bypass")) return;
        boolean badCurrent = isOversized(event.getCurrentItem());
        boolean badCursor = isOversized(event.getCursor());
        if (badCurrent || badCursor) {
            event.setCancelled(true);
            scheduleOverstackClickQuarantine(player, event.getClickedInventory(), event.getSlot(), badCursor);
            incident("OVERSTACK_TRANSACTION_BLOCKED", "player " + player.getName(),
                    "Inventory click carrying an item amount above its material max-stack-size was cancelled.", null);
            plugin.dupeAlert("Blocked oversized inventory click from " + player.getName() + "; invalid stack will be quarantined if it remains present.");
            return;
        }

        // Pure movement operations in storage containers must conserve every exact
        // item fingerprint across the player inventory, cursor and open container.
        // If the server ends the transaction with a net increase, roll back the whole
        // participating inventory snapshot instead of trusting the client update.
        if (plugin.enabled("dupe.auto-rollback-stackable-transfers", true) && isConservationClick(event)) {
            int tick = Bukkit.getCurrentTick();
            Inventory top = event.getView().getTopInventory();
            if (registerTransactionParticipants(tick, player, top)) {
                TransferSnapshot snapshot = capturePlayerTransfer(player, top, tick);
                if (snapshot != null) scheduleTransferAudit(event, snapshot, "inventory-click");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (event.getWhoClicked() instanceof Player player) {
            trackInventoryClick(player);
            schedulePlayerViewScan(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardDrag(InventoryDragEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (!(event.getWhoClicked() instanceof Player player) || player.hasPermission("aegis.bypass")) return;
        boolean oversized = isOversized(event.getOldCursor());
        if (!oversized) {
            for (ItemStack value : event.getNewItems().values()) {
                if (isOversized(value)) { oversized = true; break; }
            }
        }
        if (oversized) {
            event.setCancelled(true);
            schedulePlayerViewScan(player, event.getView().getTopInventory());
            incident("OVERSTACK_DRAG_BLOCKED", "player " + player.getName(),
                    "Inventory drag carrying an oversized stack was cancelled.", null);
            plugin.dupeAlert("Blocked oversized inventory drag from " + player.getName() + ".");
            return;
        }
        if (plugin.enabled("dupe.auto-rollback-stackable-transfers", true) && isConservationDrag(event)) {
            int tick = Bukkit.getCurrentTick();
            Inventory top = event.getView().getTopInventory();
            if (registerTransactionParticipants(tick, player, top)) {
                TransferSnapshot snapshot = capturePlayerTransfer(player, top, tick);
                if (snapshot != null) scheduleTransferAudit(event, snapshot, "inventory-drag");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (event.getWhoClicked() instanceof Player player)
            schedulePlayerViewScan(player, event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (event.getPlayer() instanceof Player player)
            schedulePlayerViewScan(player, event.getInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardPlayerPickup(EntityPickupItemEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (!(event.getEntity() instanceof Player player) || player.hasPermission("aegis.bypass")) return;
        if (isOversized(event.getItem().getItemStack())) {
            event.setCancelled(true);
            scheduleEntityQuarantine(event.getItem(), "oversized-stack-on-player-pickup");
            return;
        }
        if (plugin.enabled("dupe.auto-rollback-stackable-transfers", true)) {
            PickupTransferSnapshot snapshot = capturePickupTransfer(player, null, event.getItem());
            if (snapshot != null) schedulePickupAudit(event, snapshot, "player-item-pickup");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (!(event.getEntity() instanceof Player player) || player.hasPermission("aegis.bypass")) return;
        ItemStack stack = event.getItem().getItemStack();
        if (isOversized(stack)) {
            scheduleEntityQuarantine(event.getItem(), "oversized-stack-on-pickup");
            return;
        }
        trackPickup(player, stack.getAmount(), stack.getType());
        schedulePlayerViewScan(player, player.getOpenInventory().getTopInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardDrop(PlayerDropItemEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        Player player = event.getPlayer();
        if (player.hasPermission("aegis.bypass")) return;
        if (isOversized(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            scheduleEntityQuarantine(event.getItemDrop(), "oversized-stack-on-drop");
            return;
        }
        if (plugin.enabled("dupe.auto-rollback-stackable-transfers", true)) {
            PickupTransferSnapshot snapshot = capturePickupTransfer(player, null, event.getItemDrop());
            if (snapshot != null) schedulePickupAudit(event, snapshot, "player-item-drop");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        schedulePlayerScan(event.getPlayer());
        scheduleLooseItemScan(event.getItemDrop());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        // A local neighborhood scan sees the new entity, nearby containers and nearby
        // online inventories in one snapshot. This catches copied serials much sooner
        // than waiting for the next full loaded-world pass.
        scheduleLooseItemScan(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardInventoryMove(InventoryMoveItemEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (isOversized(event.getItem())) {
            event.setCancelled(true);
            scheduleInventoryPairScan(event.getSource(), event.getDestination(), "oversized hopper/container transfer");
            incident("OVERSTACK_MOVE_BLOCKED", "inventory transfer",
                    "InventoryMoveItemEvent with amount above max-stack-size was cancelled.", null);
            return;
        }
        if (plugin.enabled("dupe.auto-rollback-stackable-transfers", true)) {
            int tick = Bukkit.getCurrentTick();
            boolean isolated = registerTransactionParticipants(tick, event.getSource(), event.getDestination());
            if (isolated) {
                InventoryPairSnapshot snapshot = captureInventoryPair(event.getSource(), event.getDestination(), tick);
                if (snapshot != null) scheduleInventoryPairAudit(event, snapshot, "automated-inventory-transfer");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        scheduleInventoryPairScan(event.getSource(), event.getDestination(), "inventory transfer");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardInventoryPickup(InventoryPickupItemEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (isOversized(event.getItem().getItemStack())) {
            event.setCancelled(true);
            scheduleEntityQuarantine(event.getItem(), "oversized-stack-on-inventory-pickup");
            return;
        }
        if (plugin.enabled("dupe.auto-rollback-stackable-transfers", true)) {
            PickupTransferSnapshot snapshot = capturePickupTransfer(null, event.getInventory(), event.getItem());
            if (snapshot != null) schedulePickupAudit(event, snapshot, "inventory-item-pickup");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        scheduleSingleInventoryScan(event.getInventory(), "hopper/container pickup");
        scheduleLooseItemScan(event.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (event.getPlayer() instanceof Player player)
            schedulePlayerViewScan(player, event.getInventory());
        else
            scheduleSingleInventoryScan(event.getInventory(), "container open");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        scheduleChunkScan(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        scheduleChunkScan(event.getBlock().getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.enabled("dupe.enabled", true)) return;
        scheduleChunkScan(event.getBlockPlaced().getChunk());
    }

    void requestFullScan() {
        beginRound();
        plugin.getLogger().info("AegisAC dupe scan started across online inventories and loaded chunks.");
    }

    void scanPlayer(Player player) {
        Map<String, List<Occurrence>> snapshot = new HashMap<>();
        scanPlayerInto(player, snapshot);
        detectLocalDuplicates(snapshot, "manual/player inventory scan");
        saveLedger();
    }

    private void schedulePlayerScan(Player player) {
        schedulePlayerViewScan(player, null);
    }

    private void schedulePlayerViewScan(Player player, Inventory eventInventory) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        if (eventInventory != null) {
            Set<Inventory> extras = pendingPlayerInventories.computeIfAbsent(id,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
            extras.add(eventInventory);
        }
        if (!pendingPlayerScans.add(id)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingPlayerScans.remove(id);
            Set<Inventory> extras = pendingPlayerInventories.remove(id);
            if (!player.isOnline()) return;
            Map<String, List<Occurrence>> snapshot = new HashMap<>();
            scanPlayerInto(player, snapshot);
            Set<Inventory> scanned = Collections.newSetFromMap(new IdentityHashMap<>());
            scanned.add(player.getInventory());
            scanned.add(player.getEnderChest());
            Inventory currentTop = player.getOpenInventory().getTopInventory();
            if (currentTop != null && scanned.add(currentTop))
                scanInventory(currentTop, inventorySource(currentTop), "open inventory of " + player.getName(),
                        snapshot, (slot, item) -> currentTop.setItem(slot, item));
            if (extras != null) for (Inventory extra : extras) {
                if (extra != null && scanned.add(extra))
                    scanInventory(extra, inventorySource(extra), "transaction inventory of " + player.getName(),
                            snapshot, (slot, item) -> extra.setItem(slot, item));
            }
            detectLocalDuplicates(snapshot, "post-inventory-transaction");
            saveLedger();
        }, Math.max(1L, plugin.integer("dupe.transaction-scan-delay-ticks", 1)));
    }

    private void scheduleOverstackClickQuarantine(Player player, Inventory clicked, int slot, boolean cursor) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (cursor) {
                ItemStack stack = player.getItemOnCursor();
                if (isOversized(stack)) {
                    Occurrence occurrence = new Occurrence("cursor:" + player.getUniqueId(),
                            "cursor of " + player.getName(), stack,
                            player::getItemOnCursor, player::setItemOnCursor);
                    quarantineOccurrence(occurrence, "oversized stack blocked during inventory click");
                }
            }
            if (clicked != null && slot >= 0 && slot < clicked.getSize()) {
                ItemStack stack = clicked.getItem(slot);
                if (isOversized(stack)) {
                    Occurrence occurrence = new Occurrence(inventorySource(clicked) + ":slot:" + slot,
                            "inventory slot " + slot + " of " + clicked.getType(), stack,
                            () -> clicked.getItem(slot), value -> clicked.setItem(slot, value));
                    quarantineOccurrence(occurrence, "oversized stack blocked during inventory click");
                }
            }
        }, 1L);
    }

    private void scheduleEntityQuarantine(Item item, String reason) {
        if (item == null || !item.isValid()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (item.isValid() && isOversized(item.getItemStack())) quarantineEntity(item, reason);
        });
    }

    private boolean registerTransactionParticipants(int tick, Object... objects) {
        IdentityHashMap<Object, Integer> participants = transactionParticipants.computeIfAbsent(tick,
                ignored -> new IdentityHashMap<>());
        Set<Object> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean isolated = true;
        for (Object object : objects) {
            if (object == null || !unique.add(object)) continue;
            int count = participants.merge(object, 1, Integer::sum);
            if (count > 1) isolated = false;
        }
        if (scheduledTransactionCleanup.add(tick)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                transactionParticipants.remove(tick);
                scheduledTransactionCleanup.remove(tick);
            }, 2L);
        }
        return isolated;
    }

    private boolean isTransactionIsolated(int tick, Collection<?> objects) {
        IdentityHashMap<Object, Integer> participants = transactionParticipants.get(tick);
        if (participants == null) return true;
        Set<Object> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object object : objects) {
            if (object != null && unique.add(object) && participants.getOrDefault(object, 0) > 1)
                return false;
        }
        return true;
    }

    private boolean hasNearbyMatchingItem(Item item) {
        String target = fingerprint(item.getItemStack());
        for (Entity nearby : item.getNearbyEntities(2.0, 2.0, 2.0)) {
            if (nearby instanceof Item other && other.isValid() && other != item &&
                    fingerprint(other.getItemStack()).equals(target)) return true;
        }
        return false;
    }

    private PickupTransferSnapshot capturePickupTransfer(Player player, Inventory inventory, Item item) {
        if (item == null || !item.isValid()) return null;
        Object participant = player != null ? player : inventory;
        if (participant == null) return null;
        int tick = Bukkit.getCurrentTick();
        // Register even when a nearby item merge makes this specific transfer
        // unsuitable for rollback. It still marks the player/container as busy,
        // preventing a different same-tick snapshot from overwriting this change.
        if (!registerTransactionParticipants(tick, participant, item)) return null;
        if (hasNearbyMatchingItem(item)) return null;
        ItemStack ground = cloneItem(item.getItemStack());
        ItemStack[] storage = player == null ? null : cloneContents(player.getInventory().getStorageContents());
        ItemStack[] armor = player == null ? null : cloneContents(player.getInventory().getArmorContents());
        ItemStack[] extra = player == null ? null : cloneContents(player.getInventory().getExtraContents());
        ItemStack cursor = player == null ? null : cloneItem(player.getItemOnCursor());
        ItemStack[] inventoryContents = inventory == null ? null : cloneContents(inventory.getContents());
        Map<String, Long> before = new HashMap<>();
        if (player != null) {
            addCounts(before, storage);
            addCounts(before, armor);
            addCounts(before, extra);
            addCounts(before, new ItemStack[]{cursor});
        }
        if (inventoryContents != null) addCounts(before, inventoryContents);
        addCounts(before, new ItemStack[]{ground});
        return new PickupTransferSnapshot(tick, participant, player, inventory, item, ground, storage, armor, extra,
                cursor, inventoryContents, before);
    }

    private void schedulePickupAudit(Cancellable event, PickupTransferSnapshot snapshot, String source) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.isCancelled()) return;
            if (snapshot.player != null && !snapshot.player.isOnline()) return;
            if (!isTransactionIsolated(snapshot.tick, List.of(snapshot.participant, snapshot.item))) return;
            Map<String, Long> after = new HashMap<>();
            if (snapshot.player != null && snapshot.player.isOnline()) {
                addCounts(after, snapshot.player.getInventory().getStorageContents());
                addCounts(after, snapshot.player.getInventory().getArmorContents());
                addCounts(after, snapshot.player.getInventory().getExtraContents());
                addCounts(after, new ItemStack[]{snapshot.player.getItemOnCursor()});
            }
            if (snapshot.inventory != null) addCounts(after, snapshot.inventory.getContents());
            if (snapshot.item.isValid()) addCounts(after, new ItemStack[]{snapshot.item.getItemStack()});
            List<String> increases = findCountIncreases(snapshot.beforeCounts, after);
            if (increases.isEmpty()) return;
            snapshot.restore();
            incident("PICKUP_DUPLICATION_ROLLED_BACK", source,
                    "Pickup conservation check found a net item-count increase; inventory and item entity were restored. Fingerprints=" + increases, null);
            plugin.dupeAlert("AUTOMATIC DUPLICATION BLOCK: rolled back suspicious " + source + " (item count increased during pickup).");
        }, 1L);
    }

    private InventoryPairSnapshot captureInventoryPair(Inventory first, Inventory second, int tick) {
        if (first == null || second == null) return null;
        Set<Inventory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Inventory> inventories = new ArrayList<>();
        if (seen.add(first)) inventories.add(first);
        if (seen.add(second)) inventories.add(second);
        if (inventories.isEmpty()) return null;
        Map<Inventory, ItemStack[]> beforeContents = new IdentityHashMap<>();
        Map<String, Long> beforeCounts = new HashMap<>();
        for (Inventory inventory : inventories) {
            ItemStack[] contents = cloneContents(inventory.getContents());
            beforeContents.put(inventory, contents);
            addCounts(beforeCounts, contents);
        }
        return new InventoryPairSnapshot(tick, inventories, beforeContents, beforeCounts);
    }

    private void scheduleInventoryPairAudit(Cancellable event, InventoryPairSnapshot snapshot, String source) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.isCancelled() || !isTransactionIsolated(snapshot.tick, snapshot.inventories)) return;
            Map<String, Long> after = new HashMap<>();
            for (Inventory inventory : snapshot.inventories) addCounts(after, inventory.getContents());
            List<String> increases = findCountIncreases(snapshot.beforeCounts, after);
            if (increases.isEmpty()) return;
            snapshot.restore();
            incident("AUTOMATION_DUPLICATION_ROLLED_BACK", source,
                    "Inventory pair conservation check found a net item increase; source/destination inventories were restored. Fingerprints=" + increases, null);
            plugin.dupeAlert("AUTOMATIC DUPLICATION BLOCK: reverted suspicious " + source +
                    " (stack quantity increased across a transfer).");
        }, 1L);
    }

    private List<String> findCountIncreases(Map<String, Long> before, Map<String, Long> after) {
        List<String> increases = new ArrayList<>();
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            long oldAmount = before.getOrDefault(key, 0L);
            long newAmount = after.getOrDefault(key, 0L);
            if (newAmount > oldAmount) increases.add(shortId(key) + ":" + oldAmount + "->" + newAmount);
        }
        return increases;
    }

    private void scheduleSingleInventoryScan(Inventory inventory, String context) {
        if (inventory == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Map<String, List<Occurrence>> snapshot = new HashMap<>();
            scanInventory(inventory, inventorySource(inventory), context, snapshot,
                    (slot, item) -> inventory.setItem(slot, item));
            detectLocalDuplicates(snapshot, context);
            saveLedger();
        }, 1L);
    }

    private void scheduleInventoryPairScan(Inventory first, Inventory second, String context) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Map<String, List<Occurrence>> snapshot = new HashMap<>();
            Set<Inventory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            if (first != null && seen.add(first)) scanInventory(first, inventorySource(first), context + " source",
                    snapshot, (slot, item) -> first.setItem(slot, item));
            if (second != null && seen.add(second)) scanInventory(second, inventorySource(second), context + " destination",
                    snapshot, (slot, item) -> second.setItem(slot, item));
            detectLocalDuplicates(snapshot, context);
            saveLedger();
        }, 1L);
    }

    private String inventorySource(Inventory inventory) {
        // Keep transaction scans and loaded-chunk scans on the exact same source key;
        // otherwise a known canonical chest slot could look like a different location
        // merely because it was inspected while open rather than during a chunk scan.
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Entity entity) return "entity-inventory:" + entity.getUniqueId();
        org.bukkit.Location location = inventory.getLocation();
        if (location != null && location.getWorld() != null) {
            return "container:" + location.getWorld().getUID() + ":" + location.getBlockX() + "," +
                    location.getBlockY() + "," + location.getBlockZ();
        }
        return "inventory-view:" + Integer.toHexString(System.identityHashCode(inventory));
    }

    private boolean isOversized(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.getAmount() > stack.getMaxStackSize();
    }

    private boolean isConservationClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL &&
                player.getGameMode() != org.bukkit.GameMode.ADVENTURE) return false;
        if (event.getClickedInventory() == null) return false;
        String top = event.getView().getTopInventory().getType().name();
        if (!Set.of("CHEST", "ENDER_CHEST", "BARREL", "HOPPER", "DISPENSER", "DROPPER", "SHULKER_BOX").contains(top))
            return false;
        String action = event.getAction().name();
        return Set.of("PICKUP_ALL", "PICKUP_SOME", "PICKUP_HALF", "PICKUP_ONE",
                "PLACE_ALL", "PLACE_SOME", "PLACE_ONE", "SWAP_WITH_CURSOR",
                "MOVE_TO_OTHER_INVENTORY", "HOTBAR_SWAP", "HOTBAR_MOVE_AND_READD",
                "COLLECT_TO_CURSOR").contains(action);
    }

    private boolean isConservationDrag(InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL &&
                player.getGameMode() != org.bukkit.GameMode.ADVENTURE) return false;
        String top = event.getView().getTopInventory().getType().name();
        return Set.of("CHEST", "ENDER_CHEST", "BARREL", "HOPPER", "DISPENSER", "DROPPER", "SHULKER_BOX").contains(top);
    }

    private TransferSnapshot capturePlayerTransfer(Player player, Inventory top, int tick) {
        if (top == null) return null;
        Set<Inventory> inventories = Collections.newSetFromMap(new IdentityHashMap<>());
        if (top == player.getInventory() || top == player.getEnderChest()) return null;
        inventories.add(top);
        ItemStack[] storage = cloneContents(player.getInventory().getStorageContents());
        ItemStack[] armor = cloneContents(player.getInventory().getArmorContents());
        ItemStack[] extra = cloneContents(player.getInventory().getExtraContents());
        ItemStack cursor = cloneItem(player.getItemOnCursor());
        ItemStack[] topContents = cloneContents(top.getContents());
        Map<String, Long> before = new HashMap<>();
        addCounts(before, storage);
        addCounts(before, armor);
        addCounts(before, extra);
        addCounts(before, new ItemStack[]{cursor});
        addCounts(before, topContents);
        return new TransferSnapshot(tick, List.of(player, top), player, top, storage, armor, extra, cursor, topContents, before);
    }

    private void scheduleTransferAudit(Cancellable event, TransferSnapshot snapshot, String source) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = snapshot.player;
            if (event.isCancelled() || !player.isOnline() ||
                    !isTransactionIsolated(snapshot.tick, snapshot.participants)) return;
            Map<String, Long> after = new HashMap<>();
            addCounts(after, player.getInventory().getStorageContents());
            addCounts(after, player.getInventory().getArmorContents());
            addCounts(after, player.getInventory().getExtraContents());
            addCounts(after, new ItemStack[]{player.getItemOnCursor()});
            addCounts(after, snapshot.top.getContents());

            List<String> increases = findCountIncreases(snapshot.beforeCounts, after);
            if (increases.isEmpty()) return;

            snapshot.restore();
            incident("INVENTORY_DUPLICATION_ROLLED_BACK", "player " + player.getName(),
                    "Conservation audit found a net item-count increase during " + source +
                            "; restored the pre-transaction player/container snapshot. Fingerprints=" + increases, null);
            plugin.dupeAlert("AUTOMATIC DUPLICATION BLOCK: rolled back a suspicious " + source +
                    " for " + player.getName() + " (net item increase detected). The transaction was reverted.");
            schedulePlayerViewScan(player, snapshot.top);
        }, 1L);
    }

    private void addCounts(Map<String, Long> counts, ItemStack[] contents) {
        if (contents == null) return;
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            String fingerprint = fingerprint(stack);
            counts.merge(fingerprint, (long) stack.getAmount(), Long::sum);
        }
    }

    private String fingerprint(ItemStack stack) {
        try {
            ItemStack normalized = stack.clone();
            normalized.setAmount(1);
            ItemMeta meta = normalized.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.remove(serialKey);
            pdc.remove(signatureKey);
            normalized.setItemMeta(meta);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.serializeAsBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            // Fail closed for automatic rollback: a fingerprint error disables this
            // one comparison rather than guessing that two items are equivalent.
            try {
                ItemStack normalized = stack.clone();
                normalized.setAmount(1);
                return "fallback:" + normalized.serialize().toString();
            } catch (RuntimeException ignored) {
                return "fallback-material:" + stack.getType().name();
            }
        }
    }

    private ItemStack cloneItem(ItemStack stack) { return stack == null ? null : stack.clone(); }

    private void scheduleInventoryScan(Inventory inventory) {
        if (inventory == null) return;
        org.bukkit.Location location = inventory.getLocation();
        if (location != null && location.getWorld() != null) {
            scheduleChunkScan(location.getChunk());
        }
    }

    private void scheduleChunkScan(Chunk chunk) {
        String key = chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
        if (!pendingChunkScans.add(key)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingChunkScans.remove(key);
            if (chunk.isLoaded()) scanChunk(chunk);
        }, 2L);
    }

    private void scanTick() {
        if (!plugin.enabled("dupe.enabled", true)) return;
        if (chunkIterator == null) {
            if (System.currentTimeMillis() < nextRoundAt) return;
            beginRound();
        }
        int budget = Math.max(1, plugin.integer("dupe.chunk-scan-budget-per-tick", 6));
        while (budget-- > 0 && chunkIterator != null && chunkIterator.hasNext()) {
            Chunk chunk = chunkIterator.next();
            if (chunk.isLoaded()) scanChunkInto(chunk, roundOccurrences);
        }
        if (chunkIterator != null && !chunkIterator.hasNext()) finishRound();
    }

    private void beginRound() {
        List<Chunk> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) chunks.addAll(Arrays.asList(world.getLoadedChunks()));
        roundOccurrences = new HashMap<>();
        roundInventories = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player player : Bukkit.getOnlinePlayers()) scanPlayerInto(player, roundOccurrences);
        chunkIterator = chunks.iterator();
    }

    private void finishRound() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<Occurrence>> entry : roundOccurrences.entrySet()) {
            String serial = entry.getKey();
            List<Occurrence> occurrences = entry.getValue();
            if (occurrences.size() == 1) {
                SerialRecord record = ledger.get(serial);
                if (record != null) {
                    record.lastSeen = now;
                    if (record.canonicalTrusted) {
                        record.lastLocation = occurrences.get(0).source;
                    } else {
                        // Imported/unknown serials need two independent clean full scans
                        // before AegisAC may select a canonical copy for auto-quarantine.
                        int cleanRounds = canonicalSingleRounds.merge(serial, 1, Integer::sum);
                        if (cleanRounds >= 2) {
                            record.lastLocation = occurrences.get(0).source;
                            record.canonicalTrusted = true;
                            canonicalSingleRounds.remove(serial);
                        }
                    }
                }
                duplicateRounds.remove(serial);
            } else if (occurrences.size() > 1) {
                canonicalSingleRounds.remove(serial);
                int rounds = duplicateRounds.merge(serial, 1, Integer::sum);
                if (rounds >= Math.max(2, plugin.integer("dupe.duplicate-confirmation-rounds", 2)))
                    handleDuplicate(serial, occurrences);
            }
        }
        // Candidate serials that disappeared are no longer consecutive evidence.
        duplicateRounds.keySet().removeIf(serial -> !roundOccurrences.containsKey(serial) ||
                roundOccurrences.get(serial).size() < 2);
        canonicalSingleRounds.keySet().removeIf(serial -> !roundOccurrences.containsKey(serial) ||
                roundOccurrences.get(serial).size() != 1);
        saveLedger();
        roundOccurrences = null;
        roundInventories = null;
        chunkIterator = null;
        nextRoundAt = now + plugin.integer("dupe.full-scan-interval-seconds", 20) * 1000L;
    }

    private void scanPlayerInto(Player player, Map<String, List<Occurrence>> found) {
        PlayerInventory inventory = player.getInventory();
        String playerSource = "player:" + player.getUniqueId();
        // Storage, armor and extra slots are separate API sections; scan each exactly once.
        scanPlayerArray(playerSource + ":storage", "storage of " + player.getName(),
                inventory::getStorageContents, inventory::setStorageContents, found);
        scanPlayerArray(playerSource + ":armor", "armor of " + player.getName(),
                inventory::getArmorContents, inventory::setArmorContents, found);
        scanPlayerArray(playerSource + ":extra", "extra slots of " + player.getName(),
                inventory::getExtraContents, inventory::setExtraContents, found);
        scanStack(player.getItemOnCursor(), "cursor:" + player.getUniqueId(), "cursor of " + player.getName(),
                player::getItemOnCursor, player::setItemOnCursor, found);

        Inventory enderChest = player.getEnderChest();
        scanInventory(enderChest, "ender:" + player.getUniqueId(), "ender chest " + player.getName(),
                found, (slot, item) -> enderChest.setItem(slot, item));
    }

    private void scanPlayerArray(String source, String label, Supplier<ItemStack[]> getter,
                                 Consumer<ItemStack[]> setter, Map<String, List<Occurrence>> found) {
        ItemStack[] initial = getter.get();
        for (int slot = 0; slot < initial.length; slot++) {
            final int index = slot;
            scanStack(initial[index], source + ":slot:" + slot, label + " slot " + slot,
                    () -> getter.get()[index], value -> {
                        ItemStack[] updated = getter.get();
                        updated[index] = value;
                        setter.accept(updated);
                    }, found);
        }
    }

    private void scanChunk(Chunk chunk) {
        Map<String, List<Occurrence>> snapshot = new HashMap<>();
        scanChunkInto(chunk, snapshot);
        detectLocalDuplicates(snapshot, "chunk " + chunk.getX() + "," + chunk.getZ());
    }

    private void scanChunkInto(Chunk chunk, Map<String, List<Occurrence>> found) {
        Set<Inventory> seenInventories = found == roundOccurrences && roundInventories != null
                ? roundInventories : Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof InventoryHolder holder)) continue;
            Inventory inventory;
            try {
                // Scan each chest half's own 27 slots, not the shared 54-slot double-chest view.
                inventory = state instanceof Chest chest ? chest.getBlockInventory() : holder.getInventory();
            } catch (Throwable ignored) { continue; }
            // Also guard against inventory wrappers that return the same underlying view twice.
            if (!seenInventories.add(inventory)) continue;
            String source = "container:" + chunk.getWorld().getUID() + ":" +
                    state.getX() + "," + state.getY() + "," + state.getZ();
            scanInventory(inventory, source, "container " + chunk.getWorld().getName() + " " +
                    state.getX() + " " + state.getY() + " " + state.getZ(), found,
                    (slot, item) -> inventory.setItem(slot, item));
        }
        for (Entity entity : chunk.getEntities()) {
            if (entity.isDead() || !entity.isValid() || entity instanceof Player) continue;
            if (entity instanceof Item itemEntity) {
                ItemStack current = itemEntity.getItemStack();
                Occurrence occurrence = scanStack(current,
                        "item:" + itemEntity.getUniqueId(), "dropped item at " + format(itemEntity),
                        () -> itemEntity.isValid() ? itemEntity.getItemStack() : null,
                        value -> {
                            if (value == null) itemEntity.remove();
                            else itemEntity.setItemStack(value);
                        }, found);
                if (occurrence != null) scannedStacks++;
            }
            // Covers storage minecarts, chest boats, pack animals and other entity inventories.
            if (entity instanceof InventoryHolder holder) {
                try {
                    Inventory inventory = holder.getInventory();
                    if (seenInventories.add(inventory)) {
                        String source = "entity-inventory:" + entity.getUniqueId();
                        scanInventory(inventory, source, "entity inventory " + entity.getType() + " " + entity.getUniqueId(),
                                found, (slot, value) -> inventory.setItem(slot, value));
                    }
                } catch (Throwable ignored) { }
            }
            // Item frames are a separate storage surface and are not InventoryHolder instances.
            if (entity instanceof ItemFrame frame) {
                scanStack(frame.getItem(), "item-frame:" + frame.getUniqueId(),
                        "item frame at " + format(frame),
                        () -> frame.isValid() ? frame.getItem() : null,
                        frame::setItem, found);
            }
            // Equipment-bearing mobs/armor stands can otherwise hide a tagged unique item.
            if (entity instanceof LivingEntity living) {
                EntityEquipment equipment = living.getEquipment();
                if (equipment != null) {
                    String source = "equipment:" + entity.getUniqueId();
                    scanStack(equipment.getItemInMainHand(), source + ":main", "main hand of " + entity.getType(),
                            () -> equipment.getItemInMainHand(), equipment::setItemInMainHand, found);
                    scanStack(equipment.getItemInOffHand(), source + ":off", "off hand of " + entity.getType(),
                            () -> equipment.getItemInOffHand(), equipment::setItemInOffHand, found);
                    ItemStack[] armor = equipment.getArmorContents();
                    for (int slot = 0; slot < armor.length; slot++) {
                        final int index = slot;
                        scanStack(armor[slot], source + ":armor:" + slot, "armor slot " + slot + " of " + entity.getType(),
                                () -> equipment.getArmorContents()[index], value -> {
                                    ItemStack[] updated = equipment.getArmorContents();
                                    updated[index] = value;
                                    equipment.setArmorContents(updated);
                                }, found);
                    }
                }
            }
        }
    }

    private void scheduleLooseItemScan(Item item) {
        if (item == null || !item.isValid()) return;
        UUID id = item.getUniqueId();
        if (!pendingLooseItemScans.add(id)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingLooseItemScans.remove(id);
            if (item.isValid()) scanLooseItem(item);
        }, 1L);
    }

    private void scanLooseItem(Item item) {
        if (item == null || !item.isValid()) return;
        Map<String, List<Occurrence>> snapshot = new HashMap<>();
        scanChunkInto(item.getChunk(), snapshot);
        double radius = Math.max(8.0, plugin.number("dupe.neighborhood-scan-radius", 24.0));
        double radiusSquared = radius * radius;
        for (Player player : item.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(item.getLocation()) <= radiusSquared)
                scanPlayerInto(player, snapshot);
        }
        detectLocalDuplicates(snapshot, "item-spawn-neighborhood");
        saveLedger();
    }

    private void scanInventory(Inventory inventory, String source, String label,
                               Map<String, List<Occurrence>> found, InventorySetter setter) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final int index = slot;
            ItemStack item = inventory.getItem(slot);
            Occurrence occurrence = scanStack(item, source + ":slot:" + slot, label + " slot " + slot,
                    () -> inventory.getItem(index), value -> setter.set(index, value), found);
            if (occurrence != null) scannedStacks++;
        }
    }

    private Occurrence scanStack(ItemStack item, String source, String label,
                                 Supplier<ItemStack> getter, Consumer<ItemStack> setter,
                                 Map<String, List<Occurrence>> found) {
        if (item == null || item.getType().isAir()) return null;
        ItemStack snapshot = item.clone();
        Occurrence occurrence = new Occurrence(source, label, snapshot, getter, setter);
        int max = item.getMaxStackSize();
        if (item.getAmount() > max) {
            quarantineOccurrence(occurrence, "oversized stack " + item.getType() + " x" + item.getAmount());
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String serial = pdc.get(serialKey, PersistentDataType.STRING);
        String signature = pdc.get(signatureKey, PersistentDataType.STRING);
        boolean hasAegisTag = serial != null || signature != null;
        boolean shouldTrack = max == 1 || hasAegisTag;
        if (!shouldTrack) return occurrence;

        // If the signing key was lost/corrupted, never mass-quarantine legitimate items.
        // We can still report repeated serials, but automatic authenticity enforcement is paused.
        if (!secretTrusted) {
            if (serial != null) found.computeIfAbsent(serial, ignored -> new ArrayList<>()).add(occurrence);
            return occurrence;
        }

        if (!hasAegisTag) {
            serial = UUID.randomUUID().toString();
            signature = sign(serial);
            pdc.set(serialKey, PersistentDataType.STRING, serial);
            pdc.set(signatureKey, PersistentDataType.STRING, signature);
            item.setItemMeta(meta);
            setter.accept(item);
            occurrence.expected = item.clone();
            ledger.putIfAbsent(serial, new SerialRecord(item.getType().name(), System.currentTimeMillis(), source, true));
        } else if (serial == null || signature == null || !verify(serial, signature)) {
            quarantineOccurrence(occurrence, "invalid Aegis item signature");
            incident("INVALID_SIGNATURE", label, "An item has a missing or invalid Aegis serial signature.", serial);
            return null;
        }

        // A correctly signed item whose ledger entry is missing has unknown provenance.
        // Do not choose a winner among multiple such copies until a complete scan has
        // observed exactly one occurrence and established its canonical slot.
        SerialRecord record = ledger.get(serial);
        if (record == null) {
            record = new SerialRecord(item.getType().name(), System.currentTimeMillis(), source,
                    System.currentTimeMillis(), !hasAegisTag);
            ledger.put(serial, record);
        }
        record.lastSeen = System.currentTimeMillis();
        found.computeIfAbsent(serial, ignored -> new ArrayList<>()).add(occurrence);
        return occurrence;
    }

    private void detectLocalDuplicates(Map<String, List<Occurrence>> found, String context) {
        for (Map.Entry<String, List<Occurrence>> entry : found.entrySet()) {
            List<Occurrence> occurrences = entry.getValue();
            if (occurrences.size() <= 1) continue;
            String serial = entry.getKey();
            SerialRecord record = ledger.get(serial);
            List<Occurrence> canonical = new ArrayList<>();
            if (record != null && record.canonicalTrusted && record.lastLocation != null && !record.lastLocation.isBlank()) {
                for (Occurrence occurrence : occurrences)
                    if (record.lastLocation.equals(occurrence.source)) canonical.add(occurrence);
            }

            // Automatically isolate only when the previously recorded canonical slot
            // is present exactly once in the transaction snapshot. If it is not, we
            // log the incident and leave all copies untouched rather than guess.
            if (secretTrusted && plugin.enabled("dupe.auto-quarantine-confirmed-copies", true)
                    && canonical.size() == 1) {
                Occurrence keeper = canonical.get(0);
                int quarantined = 0;
                for (Occurrence occurrence : occurrences) {
                    if (occurrence == keeper) continue;
                    if (quarantineOccurrence(occurrence, "automatic duplicate serial; canonical=" + keeper.label))
                        quarantined++;
                }
                if (quarantined > 0) {
                    incident("DUPLICATE_SERIAL_AUTO_QUARANTINED", context,
                            "Automatic transaction scan kept the known canonical occurrence and quarantined " +
                                    quarantined + " copied item(s).", serial);
                    plugin.dupeAlert("AUTOMATIC duplicate protection: kept " + keeper.label + ", quarantined " +
                            quarantined + " copied item(s), serial=" + shortId(serial) + ".");
                    duplicateRounds.remove(serial);
                    continue;
                }
            }

            incident("DUPLICATE_SERIAL_UNRESOLVED", context,
                    "The same non-stackable serial appeared more than once, but the known original slot could not be proven. No item was removed.", serial);
            plugin.dupeAlert("Possible duplicate serial " + shortId(serial) + " detected in " + context +
                    " (copies=" + occurrences.size() + "). No automatic removal because the original is unproven.");
        }
    }

    private void handleDuplicate(String serial, List<Occurrence> occurrences) {
        if (!secretTrusted) {
            incident("DUPLICATE_SERIAL_UNVERIFIED", "loaded-world scan",
                    "Repeated serial found while the HMAC key is missing/corrupt. No item was removed.", serial);
            plugin.dupeAlert("Repeated item serial " + shortId(serial) + " found, but signing key is unavailable; no item was quarantined.");
            duplicateRounds.remove(serial);
            return;
        }
        SerialRecord record = ledger.get(serial);
        if (record == null || !record.canonicalTrusted) {
            incident("DUPLICATE_SERIAL_UNRESOLVED", "automatic loaded-world scan",
                    "Repeated signed serial found, but its canonical origin has not been established. No item was removed.", serial);
            plugin.dupeAlert("Possible duplicate " + shortId(serial) + " detected, but original provenance is unknown. Nothing was removed.");
            duplicateRounds.remove(serial);
            return;
        }
        String canonical = record.lastLocation;
        List<Occurrence> canonicalCopies = new ArrayList<>();
        if (canonical != null) {
            for (Occurrence occurrence : occurrences) if (canonical.equals(occurrence.source)) canonicalCopies.add(occurrence);
        }
        if (canonicalCopies.size() != 1) {
            incident("DUPLICATE_SERIAL_UNRESOLVED", "loaded-world scan",
                    "Repeated serial seen in consecutive scans, but exactly one previous canonical location was not present. No item was removed.", serial);
            plugin.dupeAlert("Possible duplicated item serial " + shortId(serial) + " seen in " + occurrences.size() +
                    " locations; the original could not be uniquely proven. Nothing was deleted.");
            duplicateRounds.remove(serial);
            return;
        }

        Occurrence keeper = canonicalCopies.get(0);
        int quarantined = 0;
        for (Occurrence occurrence : occurrences) {
            if (occurrence == keeper) continue;
            if (plugin.enabled("dupe.auto-quarantine-confirmed-copies", true)) {
                if (quarantineOccurrence(occurrence, "duplicate serial; canonical=" + keeper.label)) quarantined++;
            }
        }
        incident("DUPLICATE_SERIAL_CONFIRMED", keeper.label,
                "Serial repeated across consecutive full scans. Kept canonical location and quarantined " +
                        quarantined + " additional copy/copies.", serial);
        plugin.dupeAlert("CONFIRMED duplicate " + shortId(serial) + ": copies=" + occurrences.size() +
                ", quarantined=" + quarantined + ", original=" + keeper.label);
        if (record != null) record.lastLocation = keeper.source;
        duplicateRounds.remove(serial);
    }

    private boolean quarantineOccurrence(Occurrence occurrence, String reason) {
        ItemStack removed = occurrence.take();
        if (removed == null) return false;
        int id = nextQuarantineId++;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(quarantineFile);
        String path = "items." + id;
        yaml.set(path + ".item", removed);
        yaml.set(path + ".material", removed.getType().name());
        yaml.set(path + ".amount", removed.getAmount());
        yaml.set(path + ".reason", reason);
        yaml.set(path + ".source", occurrence.label);
        yaml.set(path + ".time", System.currentTimeMillis());
        try { saveYamlAtomic(yaml, quarantineFile); }
        catch (Exception ex) {
            // Fail safe: do not silently lose an item when quarantine persistence fails.
            occurrence.restore(removed);
            plugin.getLogger().severe("Could not persist dupe quarantine; item was restored: " + ex.getMessage());
            return false;
        }
        incident("ITEM_QUARANTINED", occurrence.label, reason + " (quarantine id " + id + ")", null);
        return true;
    }

    private void quarantineEntity(Item item, String reason) {
        ItemStack expected = item.getItemStack().clone();
        Occurrence occurrence = new Occurrence("item:" + item.getUniqueId(), "dropped item at " + format(item),
                expected, () -> item.isValid() ? item.getItemStack() : null,
                value -> { if (value == null) item.remove(); else item.setItemStack(value); });
        quarantineOccurrence(occurrence, reason);
    }

    private void trackInventoryClick(Player player) {
        if (player.hasPermission("aegis.bypass")) return;
        long now = System.currentTimeMillis();
        ArrayDeque<Long> samples = inventoryClickWindows.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        samples.addLast(now);
        while (!samples.isEmpty() && samples.peekFirst() < now - 1000L) samples.removeFirst();
        int threshold = plugin.integer("dupe.inventory-click-warning-per-second", 85);
        if (samples.size() == threshold) {
            plugin.dupeAlert("Rapid inventory transaction burst from " + player.getName() +
                    " (" + samples.size() + " clicks/s). Packet/dupe review recommended; no auto-ban.");
            incident("INVENTORY_TRANSACTION_BURST", "player " + player.getName(),
                    "Inventory click events exceeded " + threshold + " per second.", null);
        }
    }

    private void trackPickup(Player player, int amount, Material material) {
        long now = System.currentTimeMillis();
        ArrayDeque<PickupSample> samples = pickupWindows.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        samples.addLast(new PickupSample(now, amount));
        while (!samples.isEmpty() && samples.peekFirst().time < now - 2000L) samples.removeFirst();
        int total = samples.stream().mapToInt(PickupSample::amount).sum();
        int threshold = plugin.integer("dupe.pickup-burst-warning-threshold", 512);
        if (total >= threshold && total - amount < threshold) {
            plugin.dupeAlert("High item pickup burst: " + player.getName() + " collected " + total +
                    " items/2s (latest material=" + material.name() + "). Logged for review; no auto-ban.");
            incident("PICKUP_BURST", "player " + player.getName(), "Collected " + total + " items in a 2 second window.", null);
        }
    }

    boolean restoreQuarantine(int id, Player player) {
        if (id <= 0 || player == null || !player.isOnline()) return false;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(quarantineFile);
        String path = "items." + id;
        ItemStack stack = yaml.getItemStack(path + ".item");
        if (stack == null || stack.getType().isAir() || stack.getAmount() > stack.getMaxStackSize()) return false;

        // Snapshot storage slots so a failed disk write cannot duplicate the restored item.
        ItemStack[] before = cloneContents(player.getInventory().getStorageContents());
        ItemStack restored = stack.clone();
        // Admin restore is an explicit re-authorization: rotate any old token so this
        // restored record does not immediately match the duplicate token that caused quarantine.
        ItemMeta restoredMeta = restored.getItemMeta();
        PersistentDataContainer restoredPdc = restoredMeta.getPersistentDataContainer();
        restoredPdc.remove(serialKey);
        restoredPdc.remove(signatureKey);
        if (secretTrusted && restored.getMaxStackSize() == 1) {
            String freshSerial = UUID.randomUUID().toString();
            restoredPdc.set(serialKey, PersistentDataType.STRING, freshSerial);
            restoredPdc.set(signatureKey, PersistentDataType.STRING, sign(freshSerial));
            ledger.put(freshSerial, new SerialRecord(restored.getType().name(), System.currentTimeMillis(),
                    "restored-to-player:" + player.getUniqueId(), false));
        }
        restored.setItemMeta(restoredMeta);

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(restored);
        if (!leftovers.isEmpty()) {
            player.getInventory().setStorageContents(before);
            return false;
        }

        yaml.set(path, null);
        try {
            saveYamlAtomic(yaml, quarantineFile);
            saveLedger();
            return true;
        } catch (Exception ex) {
            player.getInventory().setStorageContents(before);
            plugin.getLogger().severe("Could not commit quarantine restore; player inventory was rolled back: " + ex.getMessage());
            return false;
        }
    }

    private ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }

    boolean deleteQuarantine(int id) {
        if (id <= 0) return false;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(quarantineFile);
        String path = "items." + id;
        if (!yaml.contains(path)) return false;
        yaml.set(path, null);
        try { saveYamlAtomic(yaml, quarantineFile); return true; }
        catch (Exception ex) { plugin.getLogger().severe("Could not save quarantine file: " + ex.getMessage()); return false; }
    }

    List<String> quarantineList() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(quarantineFile);
        ConfigurationSection root = yaml.getConfigurationSection("items");
        if (root == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            String material = yaml.getString("items." + id + ".material", "unknown");
            int amount = yaml.getInt("items." + id + ".amount", 1);
            String reason = yaml.getString("items." + id + ".reason", "unspecified");
            out.add("#" + id + " " + material + " x" + amount + " | " + reason);
        }
        out.sort(Comparator.comparingInt(s -> {
            try { return Integer.parseInt(s.substring(1, s.indexOf(' '))); }
            catch (RuntimeException ignored) { return Integer.MAX_VALUE; }
        }));
        return out;
    }

    List<String> incidents() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(incidentFile);
        ConfigurationSection root = yaml.getConfigurationSection("incidents");
        if (root == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            String type = yaml.getString("incidents." + id + ".type", "UNKNOWN");
            String context = yaml.getString("incidents." + id + ".context", "unknown");
            String detail = yaml.getString("incidents." + id + ".detail", "");
            out.add("#" + id + " " + type + " | " + context + " | " + detail);
        }
        Collections.reverse(out);
        return out;
    }

    String status() {
        YamlConfiguration q = YamlConfiguration.loadConfiguration(quarantineFile);
        YamlConfiguration i = YamlConfiguration.loadConfiguration(incidentFile);
        int qCount = q.getConfigurationSection("items") == null ? 0 : q.getConfigurationSection("items").getKeys(false).size();
        int iCount = i.getConfigurationSection("incidents") == null ? 0 : i.getConfigurationSection("incidents").getKeys(false).size();
        return "automatic monitoring=" + plugin.enabled("dupe.enabled", true) +
                ", transaction rollback=" + plugin.enabled("dupe.auto-rollback-stackable-transfers", true) +
                ", tracked serials=" + ledger.size() +
                ", serial-signing=" + (secretTrusted ? "trusted" : "PAUSED (key missing/corrupt)") +
                ", quarantined=" + qCount + ", incidents=" + iCount +
                ", scanned stacks=" + scannedStacks + ", full scan=" + (chunkIterator == null ? "idle" : "running");
    }

    private void incident(String type, String context, String detail, String serial) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(incidentFile);
        String path = "incidents." + nextIncidentId++;
        yaml.set(path + ".type", type);
        yaml.set(path + ".context", context);
        yaml.set(path + ".detail", detail);
        yaml.set(path + ".serial", serial == null ? "" : serial);
        yaml.set(path + ".time", System.currentTimeMillis());
        try { saveYamlAtomic(yaml, incidentFile); }
        catch (Exception ex) { plugin.getLogger().severe("Could not write dupe incident: " + ex.getMessage()); }
    }

    private void auditPaperDupeSettings() {
        File serverRoot = plugin.getDataFolder().getParentFile() == null ? null :
                plugin.getDataFolder().getParentFile().getParentFile();
        if (serverRoot == null) return;
        File globalConfig = new File(serverRoot, "config/paper-global.yml");
        if (!globalConfig.isFile()) {
            plugin.getLogger().warning("Paper global config was not found at config/paper-global.yml; automatic Paper dupe-hardening audit was skipped.");
            return;
        }
        YamlConfiguration paper = YamlConfiguration.loadConfiguration(globalConfig);
        String[] dangerous = {
                "unsupported-settings.allow-headless-pistons",
                "unsupported-settings.allow-permanent-block-break-exploits",
                "unsupported-settings.allow-piston-duplication",
                "unsupported-settings.allow-unsafe-end-portal-teleportation",
                "unsupported-settings.skip-tripwire-hook-placement-validation"
        };
        List<String> enabled = new ArrayList<>();
        for (String path : dangerous) if (paper.getBoolean(path, false)) enabled.add(path);
        List<String> unsanitized = paper.getStringList("unsupported-settings.oversized-item-component-sanitizer.dont-sanitize");
        if (!unsanitized.isEmpty()) enabled.add("oversized-item-component-sanitizer.dont-sanitize=" + unsanitized);
        if (!enabled.isEmpty()) {
            plugin.getLogger().severe("Potential Paper duplication/exploit settings are enabled: " + String.join(", ", enabled));
            plugin.getLogger().severe("Set these Paper global config values to false / empty, then restart the server. AegisAC will not rewrite Paper's config while the server is running.");
            plugin.dupeAlert("Paper dupe-hardening warning: " + String.join(", ", enabled));
        } else {
            plugin.getLogger().info("Paper global dupe-hardening audit passed: known unsafe duplication toggles are disabled.");
        }
    }

    private void loadSecret() {
        try {
            plugin.getDataFolder().mkdirs();
            if (secretFile.exists()) {
                String encoded = Files.readString(secretFile.toPath(), StandardCharsets.UTF_8).trim();
                secret = Base64.getDecoder().decode(encoded);
                if (secret.length < 32) throw new IllegalArgumentException("Secret is too short.");
                secretTrusted = true;
            } else {
                boolean previousInstall = installMarkerFile.exists() || ledgerFile.exists() ||
                        quarantineFile.exists() || incidentFile.exists();
                if (previousInstall) {
                    secretTrusted = false;
                    secret = null;
                    plugin.getLogger().severe("dupe-secret.key is missing from an existing AegisAC data folder. " +
                            "Serial authenticity checks are paused to avoid mass false quarantine. Restore the original key from backup.");
                } else {
                    secret = new byte[32];
                    new SecureRandom().nextBytes(secret);
                    Files.writeString(secretFile.toPath(), Base64.getEncoder().encodeToString(secret), StandardCharsets.UTF_8);
                    secretTrusted = true;
                }
            }
            if (!installMarkerFile.exists()) Files.writeString(installMarkerFile.toPath(), "AegisAC dupe ledger initialized", StandardCharsets.UTF_8);
        } catch (Exception ex) {
            // Never replace a missing/corrupt existing key automatically: that could quarantine every tagged item.
            secret = null;
            secretTrusted = false;
            plugin.getLogger().severe("Dupe item-signature verification is paused because the signing key is unavailable: " + ex.getMessage());
        }
    }

    private String sign(String serial) {
        if (secret == null || !secretTrusted) throw new IllegalStateException("Dupe signing key is not trusted.");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(serial.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("HMAC is unavailable.", ex); }
    }

    private boolean verify(String serial, String signature) {
        try {
            byte[] expected = HexFormat.of().parseHex(sign(serial));
            byte[] supplied = HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, supplied);
        } catch (RuntimeException ex) { return false; }
    }

    private void saveYamlAtomic(YamlConfiguration yaml, File target) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(parent, target.getName() + ".tmp");
        Files.writeString(temporary.toPath(), yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void loadLedger() {
        if (!ledgerFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ledgerFile);
        ConfigurationSection root = yaml.getConfigurationSection("serials");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(id);
            if (node == null) continue;
            String lastLocation = node.getString("last-location", "");
            boolean canonicalTrusted = node.getBoolean("canonical-trusted", true) && isUniqueSource(lastLocation);
            ledger.put(id, new SerialRecord(node.getString("material", "UNKNOWN"),
                    node.getLong("first-seen", System.currentTimeMillis()), lastLocation,
                    node.getLong("last-seen", 0L), canonicalTrusted));
        }
    }

    private boolean isUniqueSource(String source) {
        if (source == null || source.isBlank()) return false;
        return source.contains(":slot:") || source.startsWith("item:") || source.startsWith("item-frame:") ||
                source.startsWith("cursor:") || source.endsWith(":main") || source.endsWith(":off") ||
                source.contains(":armor:");
    }

    private void saveLedger() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, SerialRecord> entry : ledger.entrySet()) {
            String path = "serials." + entry.getKey();
            SerialRecord record = entry.getValue();
            yaml.set(path + ".material", record.material);
            yaml.set(path + ".first-seen", record.firstSeen);
            yaml.set(path + ".last-location", record.lastLocation);
            yaml.set(path + ".last-seen", record.lastSeen);
            yaml.set(path + ".canonical-trusted", record.canonicalTrusted);
        }
        try { saveYamlAtomic(yaml, ledgerFile); }
        catch (Exception ex) { plugin.getLogger().severe("Could not save dupe ledger: " + ex.getMessage()); }
    }

    void shutdown() {
        saveLedger();
        inventoryClickWindows.clear();
        pickupWindows.clear();
        pendingPlayerScans.clear();
        pendingPlayerInventories.clear();
        transactionParticipants.clear();
        scheduledTransactionCleanup.clear();
        canonicalSingleRounds.clear();
        pendingChunkScans.clear();
        pendingLooseItemScans.clear();
    }

    private int maxNumericKey(File file, String rootPath) {
        if (!file.exists()) return 0;
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection(rootPath);
        if (root == null) return 0;
        int max = 0;
        for (String key : root.getKeys(false)) {
            try { max = Math.max(max, Integer.parseInt(key)); }
            catch (NumberFormatException ignored) { }
        }
        return max;
    }

    private String format(Entity entity) {
        return entity.getWorld().getName() + " " + entity.getLocation().getBlockX() + " " +
                entity.getLocation().getBlockY() + " " + entity.getLocation().getBlockZ();
    }

    private String shortId(String serial) { return serial == null ? "none" : serial.substring(0, Math.min(8, serial.length())); }

    private interface InventorySetter { void set(int slot, ItemStack item); }

    private static final class SerialRecord {
        final String material;
        final long firstSeen;
        String lastLocation;
        long lastSeen;
        boolean canonicalTrusted;
        SerialRecord(String material, long firstSeen, String lastLocation) {
            this(material, firstSeen, lastLocation, firstSeen, true);
        }
        SerialRecord(String material, long firstSeen, String lastLocation, boolean canonicalTrusted) {
            this(material, firstSeen, lastLocation, firstSeen, canonicalTrusted);
        }
        SerialRecord(String material, long firstSeen, String lastLocation, long lastSeen) {
            this(material, firstSeen, lastLocation, lastSeen, true);
        }
        SerialRecord(String material, long firstSeen, String lastLocation, long lastSeen, boolean canonicalTrusted) {
            this.material = material;
            this.firstSeen = firstSeen;
            this.lastLocation = lastLocation;
            this.lastSeen = lastSeen;
            this.canonicalTrusted = canonicalTrusted;
        }
    }

    private static final class PickupTransferSnapshot {
        final int tick;
        final Object participant;
        final Player player;
        final Inventory inventory;
        final Item item;
        final ItemStack ground;
        final ItemStack[] storage;
        final ItemStack[] armor;
        final ItemStack[] extra;
        final ItemStack cursor;
        final ItemStack[] inventoryContents;
        final Map<String, Long> beforeCounts;

        PickupTransferSnapshot(int tick, Object participant, Player player, Inventory inventory,
                               Item item, ItemStack ground, ItemStack[] storage, ItemStack[] armor,
                               ItemStack[] extra, ItemStack cursor, ItemStack[] inventoryContents,
                               Map<String, Long> beforeCounts) {
            this.tick = tick;
            this.participant = participant;
            this.player = player;
            this.inventory = inventory;
            this.item = item;
            this.ground = ground;
            this.storage = storage;
            this.armor = armor;
            this.extra = extra;
            this.cursor = cursor;
            this.inventoryContents = inventoryContents;
            this.beforeCounts = beforeCounts;
        }

        void restore() {
            if (player != null && player.isOnline()) {
                player.getInventory().setStorageContents(cloneArray(storage));
                player.getInventory().setArmorContents(cloneArray(armor));
                player.getInventory().setExtraContents(cloneArray(extra));
                player.setItemOnCursor(cursor == null ? null : cursor.clone());
            }
            if (inventory != null && inventoryContents != null) inventory.setContents(cloneArray(inventoryContents));
            if (item.isValid()) {
                item.setItemStack(ground.clone());
            } else {
                item.getWorld().dropItem(item.getLocation(), ground.clone());
            }
        }

        private static ItemStack[] cloneArray(ItemStack[] source) {
            if (source == null) return new ItemStack[0];
            ItemStack[] copy = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
            return copy;
        }
    }

    private static final class InventoryPairSnapshot {
        final int tick;
        final List<Inventory> inventories;
        final Map<Inventory, ItemStack[]> beforeContents;
        final Map<String, Long> beforeCounts;

        InventoryPairSnapshot(int tick, List<Inventory> inventories, Map<Inventory, ItemStack[]> beforeContents,
                              Map<String, Long> beforeCounts) {
            this.tick = tick;
            this.inventories = inventories;
            this.beforeContents = beforeContents;
            this.beforeCounts = beforeCounts;
        }

        void restore() {
            for (Inventory inventory : inventories) {
                ItemStack[] contents = beforeContents.get(inventory);
                if (contents != null) inventory.setContents(cloneArray(contents));
            }
        }

        private static ItemStack[] cloneArray(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
            return copy;
        }
    }

    private static final class TransferSnapshot {
        final int tick;
        final List<Object> participants;
        final Player player;
        final Inventory top;
        final ItemStack[] storage;
        final ItemStack[] armor;
        final ItemStack[] extra;
        final ItemStack cursor;
        final ItemStack[] topContents;
        final Map<String, Long> beforeCounts;

        TransferSnapshot(int tick, List<Object> participants, Player player, Inventory top,
                         ItemStack[] storage, ItemStack[] armor, ItemStack[] extra,
                         ItemStack cursor, ItemStack[] topContents, Map<String, Long> beforeCounts) {
            this.tick = tick;
            this.participants = participants;
            this.player = player;
            this.top = top;
            this.storage = storage;
            this.armor = armor;
            this.extra = extra;
            this.cursor = cursor;
            this.topContents = topContents;
            this.beforeCounts = beforeCounts;
        }

        void restore() {
            player.getInventory().setStorageContents(cloneArray(storage));
            player.getInventory().setArmorContents(cloneArray(armor));
            player.getInventory().setExtraContents(cloneArray(extra));
            player.setItemOnCursor(cursor == null ? null : cursor.clone());
            if (top != player.getInventory() && top != player.getEnderChest())
                top.setContents(cloneArray(topContents));
        }

        private static ItemStack[] cloneArray(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
            return copy;
        }
    }

    private record PickupSample(long time, int amount) { }

    private static final class Occurrence {
        final String source;
        final String label;
        ItemStack expected;
        final Supplier<ItemStack> getter;
        final Consumer<ItemStack> setter;
        Occurrence(String source, String label, ItemStack expected, Supplier<ItemStack> getter, Consumer<ItemStack> setter) {
            this.source = source; this.label = label; this.expected = expected.clone(); this.getter = getter; this.setter = setter;
        }
        ItemStack take() {
            ItemStack current = getter.get();
            if (current == null || current.getAmount() != expected.getAmount() || !current.isSimilar(expected)) return null;
            setter.accept(null);
            return current.clone();
        }
        void restore(ItemStack stack) { setter.accept(stack); }
    }
}
