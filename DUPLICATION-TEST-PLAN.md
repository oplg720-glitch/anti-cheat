# AegisAC 1.7.1 — automatic dupe prevention test plan

Use a disposable Paper staging server and a complete world + `plugins/AegisAC/` backup. Do not test exploit designs on a live economy server.

## Expected automatic behavior
1. Start the server. DupeGuard automatically starts its continuous scan and event listeners; no `/aegis dupe scan` command is required.
2. Open a chest and move items normally between player inventory and container using left-click, right-click, shift-click, hotbar swap, and drag. The item counts must be conserved and there must be no rollback incident.
3. Use hoppers to transfer items between containers. Counts before/after must remain constant and no false rollback must occur.
4. Pick up normal dropped stacks with a player and with a hopper. The sum of inventory + dropped item amount must remain constant.
5. On a disposable test build, inject a synthetic item-count increase during an otherwise pure chest transfer. AegisAC should restore the pre-transaction player/container snapshot and log `INVENTORY_DUPLICATION_ROLLED_BACK`.
6. Inject a synthetic count increase during an `InventoryMoveItemEvent` source/destination transfer. AegisAC should restore both inventory snapshots and log `AUTOMATION_DUPLICATION_ROLLED_BACK`.
7. Inject a synthetic count increase during player/hopper pickup. AegisAC should restore the previous inventory and item entity and log `PICKUP_DUPLICATION_ROLLED_BACK`.
8. Test oversized stacks in click, drag, drop, pickup, hopper transfer, player inventory, container and dropped-item paths. The invalid operation should be blocked and the stack quarantined only after quarantine persistence succeeds.
9. Place a signed non-stackable item in its known canonical slot, then in a disposable test duplicate it while preserving Aegis PDC. A nearby transaction/item-spawn scan should quarantine only the duplicate if it can prove the original canonical location.
10. Open a chest containing a signed item, let both the inventory-view scan and loaded-chunk scan observe it, then duplicate it in a disposable test. Both scan paths must resolve to the same canonical container-slot key.
11. Duplicate a signed item whose origin is unknown. Expected behavior: alert only; no deletion/ban based on a guess.
12. Test old ledger migration from 1.6.0: broad player/container locations must become untrusted and should not be auto-quarantined until a complete scan finds one unique occurrence.
13. Temporarily set each known unsafe Paper global dupe flag to `true` on staging. Startup should report the exact enabled key; restore every flag to `false` and restart.
14. Test full-scan load/performance with many chunks, hoppers, double chests, farms, armor stands, item frames, item entities and legal mass-pickup events.
15. Stop/start the server. Confirm `dupe-secret.key`, serial ledger, quarantine and incident records persist.

## Dependencies / compatibility
- Target API: Paper 1.21.11; plugin descriptor does not claim support for 1.21.0–1.21.10.
- JDK 21 and Gradle 8.7+ for build.
- PacketEvents 2.14.0+ is a separate optional runtime dependency for packet-level anti-cheat checks; DupeGuard event checks do not require it.
- Paper 26.1+ uses Java 25 for the server runtime and requires a separately tested compatibility build; do not assume the 1.21.11 compile target has been runtime-tested on 26.x.

## Limits
- Transaction rollback only covers Paper/Bukkit transactions observed by the plugin: pure storage click/drag, inventory move, and pickup. It intentionally excludes crafting/result inventories, anvils, trades, smithing, custom plugin APIs, arbitrary world rollback and crash recovery.
- Stackable items do not receive unique PDC serials, since that would interfere with stack merging. They are conservation-checked on supported transaction paths, not mathematically tracked across all game mechanics.
- Scans cover online player inventories and currently loaded chunks only; they do not force-load chunks or read offline playerdata.
- No generic Paper plugin can guarantee prevention of every current or future dupe exploit. Keep Paper updated and use the documented safe global flags.
