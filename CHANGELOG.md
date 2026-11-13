# Changelog

## 1.7.1
- Make automatic DupeGuard transaction monitoring safer across overlapping inventory operations.
- Use one per-tick identity registry for player clicks/drags, hopper moves, player pickups, inventory pickups and drops; conflicting snapshots are skipped instead of rolling back shared inventories.
- Require two consecutive clean full scans before trusting canonical origins for legacy/unknown signed serials.
- Immediately scan chunks when they load.
- Clarify that manual scan is diagnostic only; the background protection is automatic.

## 1.7.0
- DupeGuard now runs automatically: event-driven transaction audits plus continuous loaded-chunk scans. Manual `/aegis dupe scan` is explicitly optional diagnostics only.
- Added per-fingerprint item-count conservation snapshots for survival/adventure container click/drag operations; suspicious net increases roll back the player/container snapshot.
- Added conservation audits for hopper/container transfers and player/hopper pickup transactions; suspicious net increases restore the participating inventories/item entity.
- Added neighborhood scans after item spawns/drops to catch copied non-stackable serials earlier.
- Added per-slot canonical location tracking and provenance trust; old broad location records are treated as untrusted until a clean full scan establishes one unique occurrence.
- Added startup audit of Paper global duplication toggles and oversized-component sanitizer exceptions.
- Added automatic monitoring, transaction rollback, neighborhood scan radius, and duplicate confirmation settings.
- Improved admin help/status to clarify manual scans are not required.


## 1.6.0
- Tag newly spawned item entities on the next server tick, rather than waiting for the next full loaded-chunk scan; cloned signed serials remain detectable.
- Debounced pending loose-item scans by entity UUID to avoid scheduling duplicate scan tasks for the same drop.
- Corrected legacy packet attack counting: through Minecraft 1.21.11, parse `INTERACT_ENTITY` and count only `InteractAction.ATTACK`; Minecraft 26.1+ continues to count the dedicated `ATTACK` packet.
- Expanded the operational test checklist for signed-item duplicates, stackable limitations, quarantine recovery and Paper's built-in duplication hardening.

## 1.5.0
- Added optional PacketEvents 2.14.0+ packet monitoring for movement, entity interaction, modern dedicated ATTACK packet, inventory, digging, use-item and aggregate packet rates.
- Lowered sustained movement-packet anomaly threshold and requires three consecutive windows before alert; packet alerts do not auto-ban.
- Extended DupeGuard scanning to player Ender Chests, entity inventories, item frames, living-entity equipment and dropped item entities in loaded chunks.
- Uses per-item `ItemStack.getMaxStackSize()` to respect custom max-stack-size components.
- Added missing/corrupt HMAC-key fail-safe: serial verification pauses rather than generating a replacement key and mass-quarantining legitimate items.
- Added an install marker to detect an existing data folder whose dupe key has disappeared.
- Added atomic YAML replacement for ledger, incidents and quarantine writes.
- Made quarantine restoration transactional with player-storage rollback if persistence fails; restored unique items receive a fresh signed serial.
- Kept unresolved duplicate serials as alerts only; no automatic deletion if the original location cannot be proven.
- Added instructions for Paper Anti-Xray engine mode 2 and documented scan coverage/limitations.

## 1.4.0
- Added PacketEvents packet-rate monitoring (optional dependency).
- Added HMAC-signed unique serials for non-stackable items, full loaded-world scan, dupe incidents and quarantine commands.

## 1.3.3
- Made timed-ban maps safe for login validation that may run off the main thread.
- Deferred expired-ban persistence cleanup to the main server thread.
- Kept `/aegis tempban` strictly time-limited and case-insensitive for online targets.

## 1.3.2
- Cleared NoFall tracking across teleports and exempt movement states.
- Excluded Slow Falling and Levitation from NoFall heuristics.
- Removed moss carpet as an incorrect safe-landing exemption.

## 1.3.1
- Hardened ban duration parsing and canonical online name handling.
- Hardened region arithmetic against integer overflow.
- Added bucket protection and explosion-damage protection for claimed bases.
- Made hanging-entity placement null-safe and piston checks cover both movement directions.

## 1.3.0
- Added Meteor Client behavior heuristics and audit of the uploaded client JAR.
- Added persistent timed/permanent plugin bans and cuboid base protection.
- Retained Baritone mining, building, farming, and large-schematic behavior heuristics.


## 1.7.2
- Added `/aegis fly`: admin-only Survival flight toggle.
- Does not change game mode and does not override flight granted by another plugin.
- Revokes AegisAC flight on disconnect, plugin disable, or leaving Survival; preserves the grant through respawn while active.
