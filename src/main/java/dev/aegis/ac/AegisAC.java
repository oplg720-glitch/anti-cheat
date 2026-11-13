package dev.aegis.ac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AegisAC extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private CheckManager checks;
    private MeteorChecks meteorChecks;
    private BanManager bans;
    private BaseManager bases;
    private DupeGuard dupeGuard;
    private PacketGuard packetGuard;
    private final Set<UUID> mutedAlerts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> adminFlight = ConcurrentHashMap.newKeySet();

    @Override public void onEnable() {
        saveDefaultConfig();

        bans = new BanManager(this);
        bases = new BaseManager(this);
        checks = new CheckManager(this);
        meteorChecks = new MeteorChecks(this);
        dupeGuard = new DupeGuard(this);
        if (getServer().getPluginManager().isPluginEnabled("packetevents")) {
            try { packetGuard = new PacketGuard(this); }
            catch (NoClassDefFoundError ex) {
                getLogger().warning("PacketEvents classes are unavailable; packet checks are disabled.");
                packetGuard = null;
            }
        } else {
            getLogger().warning("PacketEvents 2.14.0+ is not installed; packet checks are disabled. Bukkit checks remain active.");
        }

        getServer().getPluginManager().registerEvents(bases, this);
        getServer().getPluginManager().registerEvents(checks, this);
        getServer().getPluginManager().registerEvents(meteorChecks, this);
        getServer().getPluginManager().registerEvents(dupeGuard, this);
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("aegis");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        getLogger().info("AegisAC 1.7.2 enabled. Admin Survival flight toggle is available through /aegis fly.");
        getLogger().warning("Client-only render modules such as ESP/Fullbright cannot be proven by server observation; behavior checks remain heuristic.");
    }

    @Override public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) revokeAdminFlight(player);
        adminFlight.clear();
        if (checks != null) checks.shutdown();
        if (dupeGuard != null) dupeGuard.shutdown();
        if (packetGuard != null) packetGuard.shutdown();
        if (bans != null) bans.save();
        if (bases != null) bases.save();
        mutedAlerts.clear();
    }

    boolean enabled(String path, boolean fallback) { return getConfig().getBoolean(path, fallback); }
    double number(String path, double fallback) { return getConfig().getDouble(path, fallback); }
    int integer(String path, int fallback) { return getConfig().getInt(path, fallback); }

    String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
    String prefix() { return color(getConfig().getString("messages.prefix", "&8[&bAegisAC&8] ")); }

    void alert(String player, String check, double vl, String detail) {
        if (!enabled("general.alerts", true)) return;
        String message = getConfig().getString("messages.alert", "&c%player% &7failed %check% &8[&f%detail%&8]")
                .replace("%player%", player)
                .replace("%check%", check)
                .replace("%vl%", String.format(Locale.US, "%.1f", vl))
                .replace("%detail%", detail);
        sendStaff(prefix() + color(message));
    }

    void packetAlert(Player player, String check, String detail) {
        if (player == null) return;
        String rendered = prefix() + color("&6" + player.getName() + " &7packet anomaly: &e" + check + " &8[&f" + detail + "&8]");
        sendStaff(rendered);
    }

    void dupeAlert(String detail) {
        String rendered = prefix() + color("&cDupeGuard &7" + detail);
        sendStaff(rendered);
    }

    void baritoneAlert(String player, String check, double score, String detail) {
        if (!enabled("general.alerts", true)) return;
        String message = getConfig().getString("messages.suspicion-alert",
                        "&6%player% &7suspicious behavior: &e%check% &8[&f%detail%&8]")
                .replace("%player%", player)
                .replace("%check%", check)
                .replace("%vl%", String.format(Locale.US, "%.1f", score))
                .replace("%detail%", detail);
        sendStaff(prefix() + color(message));
    }

    private void sendStaff(String rendered) {
        Runnable task = () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("aegis.alerts") && !mutedAlerts.contains(online.getUniqueId())) {
                    online.sendMessage(rendered);
                }
            }
            if (enabled("general.log-to-console", true)) getLogger().warning(ChatColor.stripColor(rendered));
        };
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(this, task);
    }

    void banPlayer(Player player, String reason) {
        Duration duration = Duration.ofDays(7);
        String raw = getConfig().getString("general.auto-ban-duration", "7d");
        try {
            Duration configured = BanManager.parseDuration(raw);
            if (configured != null) duration = configured;
            else getLogger().warning("Permanent auto-ban duration is not accepted here; using the safe 7d fallback.");
        } catch (IllegalArgumentException ignored) {
            getLogger().warning("Invalid general.auto-ban-duration; using the safe 7d fallback.");
        }
        bans.ban(player.getName(), player.getUniqueId(), duration, reason, "AegisAC auto-ban");
    }

    boolean hasAdminFlight(Player player) { return adminFlight.contains(player.getUniqueId()); }

    BanManager banManager() { return bans; }
    BaseManager baseManager() { return bases; }
    CheckManager checks() { return checks; }
    DupeGuard dupeGuard() { return dupeGuard; }
    PacketGuard packetGuard() { return packetGuard; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        if (!sender.hasPermission("aegis.admin")) {
            sender.sendMessage(prefix() + color("&cYou do not have permission."));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "fly" -> toggleAdminFlight(sender);
            case "reload" -> {
                reloadConfig();
                checks.reload();
                sender.sendMessage(prefix() + color("&aAegisAC configuration reloaded."));
            }
            case "alerts" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(prefix() + color("&cOnly players can toggle personal alerts."));
                    return true;
                }
                if (mutedAlerts.remove(player.getUniqueId())) player.sendMessage(prefix() + color("&aAlerts enabled."));
                else {
                    mutedAlerts.add(player.getUniqueId());
                    player.sendMessage(prefix() + color("&eAlerts muted for you."));
                }
            }
            case "info", "debug", "reset" -> handleOnlineTarget(sender, args);
            case "ban", "tempban" -> handleBan(sender, args);
            case "kick" -> handleKick(sender, args);
            case "pardon", "unban" -> handlePardon(sender, args);
            case "banlist" -> {
                List<String> lines = bans.list();
                sender.sendMessage(prefix() + color("&bActive Aegis bans: &f" + lines.size()));
                for (String line : lines) sender.sendMessage(color("&7- &f" + line));
            }
            case "base" -> handleBase(sender, args);
            case "dupe" -> handleDupe(sender, args);
            default -> help(sender);
        }
        return true;
    }

    /**
     * Grants temporary Survival flight to the invoking administrator only.
     * The grant is intentionally session-scoped and is revoked on quit, plugin
     * shutdown, or leaving Survival. It does not change the player's game mode.
     */
    private void toggleAdminFlight(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefix() + color("&cThis command can only be used in-game."));
            return;
        }
        if (!player.hasPermission("aegis.admin")) {
            player.sendMessage(prefix() + color("&cOnly server administrators can use this command."));
            return;
        }
        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage(prefix() + color("&c/aegis fly only works in Survival mode."));
            return;
        }

        UUID id = player.getUniqueId();
        if (adminFlight.remove(id)) {
            player.setFlying(false);
            player.setAllowFlight(false);
            player.sendMessage(prefix() + color("&eSurvival flight disabled."));
            return;
        }

        // Do not take over flight permissions granted by another plugin.
        if (player.getAllowFlight()) {
            player.sendMessage(prefix() + color("&eFlight is already allowed by another permission or plugin; AegisAC did not change it."));
            return;
        }

        adminFlight.add(id);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.sendMessage(prefix() + color("&aSurvival flight enabled. &7Use &f/aegis fly &7again to disable it."));
    }

    private void revokeAdminFlight(Player player) {
        if (!adminFlight.remove(player.getUniqueId())) return;
        // Never interfere with the flight controls owned by Creative/Spectator.
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @EventHandler
    public void onAdminFlightQuit(PlayerQuitEvent event) {
        revokeAdminFlight(event.getPlayer());
    }

    @EventHandler
    public void onAdminFlightGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.SURVIVAL) return;
        Player player = event.getPlayer();
        // Run after the mode transition so Creative/Spectator flight is left intact.
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && player.getGameMode() != GameMode.SURVIVAL) {
                revokeAdminFlight(player);
            }
        });
    }

    @EventHandler
    public void onAdminFlightRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!adminFlight.contains(id)) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && adminFlight.contains(id) && player.getGameMode() == GameMode.SURVIVAL) {
                player.setAllowFlight(true);
                player.setFlying(false);
            }
        });
    }

    private void handleBan(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(prefix() + color("&cUsage: /aegis ban <player> <duration|perm> <reason>"));
            sender.sendMessage(color("&7Duration: 30m, 12h, 7d, 2w, 1d12h. Omit duration for permanent ban."));
            return;
        }

        String target = args[1];
        Duration duration = null;
        int reasonStart = 2;
        boolean hasDuration = args.length >= 4 && BanManager.isDurationToken(args[2]);
        boolean permanentToken = args.length >= 3 &&
                Set.of("perm", "permanent", "forever").contains(args[2].toLowerCase(Locale.ROOT));
        if (args[0].equalsIgnoreCase("tempban") && (!hasDuration || permanentToken)) {
            sender.sendMessage(prefix() + color("&cUsage: /aegis tempban <player> <duration> <reason>"));
            sender.sendMessage(color("&7Temporary duration examples: 30m, 2h, 7d, 1d12h."));
            return;
        }
        if (hasDuration) {
            duration = BanManager.parseDuration(args[2]); // null means permanent
            reasonStart = 3;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length)).trim();
        if (reason.isBlank()) {
            sender.sendMessage(prefix() + color("&cPlease provide a ban reason."));
            return;
        }

        BanManager.BanResult result = bans.ban(target, null, duration, reason, sender.getName());
        if (!result.success()) {
            sender.sendMessage(prefix() + color("&c" + result.message()));
            return;
        }
        sender.sendMessage(prefix() + color("&aBanned &f" + target + " &afor &f" +
                (duration == null ? "permanently" : BanManager.formatDuration(duration)) +
                " &afor: &f" + reason));
    }

    private void handleKick(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(prefix() + color("&cUsage: /aegis kick <player> <reason>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(prefix() + color("&cPlayer is not online."));
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        target.kickPlayer(color("&cKicked\n&7Reason: &f" + reason));
        sender.sendMessage(prefix() + color("&aKicked &f" + target.getName() + " &afor: &f" + reason));
    }

    private void handlePardon(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(prefix() + color("&cUsage: /aegis pardon <player>"));
            return;
        }
        boolean removed = bans.pardon(args[1]);
        sender.sendMessage(prefix() + color(removed ? "&aRemoved Aegis ban for &f" + args[1] :
                "&eNo active Aegis ban found for &f" + args[1]));
    }

    private void handleBase(CommandSender sender, String[] args) {
        if (args.length < 2) {
            baseHelp(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length == 7 || args.length == 8) {
                    String name = args[2], worldName = args[3];
                    try {
                        int x = Integer.parseInt(args[4]), y = Integer.parseInt(args[5]), z = Integer.parseInt(args[6]);
                        int radius = args.length == 8 ? Integer.parseInt(args[7]) : integer("bases.default-radius", 16);
                        UUID owner = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
                        String ownerName = sender instanceof Player ? ((Player) sender).getName() : sender.getName();
                        BaseManager.BaseRegion region = bases.createRadius(name, worldName, x, y, z, radius, owner, ownerName);
                        sender.sendMessage(prefix() + color("&aCreated base &f" + region.name() + " &aat " +
                                worldName + " " + x + " " + y + " " + z + " &7(radius " + radius + ")"));
                    } catch (IllegalArgumentException ex) {
                        sender.sendMessage(prefix() + color("&c" + ex.getMessage()));
                    }
                } else if (args.length == 10) {
                    String name = args[2], worldName = args[3];
                    try {
                        int x1 = Integer.parseInt(args[4]), y1 = Integer.parseInt(args[5]), z1 = Integer.parseInt(args[6]);
                        int x2 = Integer.parseInt(args[7]), y2 = Integer.parseInt(args[8]), z2 = Integer.parseInt(args[9]);
                        UUID owner = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
                        String ownerName = sender instanceof Player ? ((Player) sender).getName() : sender.getName();
                        BaseManager.BaseRegion region = bases.createCuboid(name, worldName, x1, y1, z1, x2, y2, z2, owner, ownerName);
                        sender.sendMessage(prefix() + color("&aCreated base &f" + region.name() + " &ain " + worldName));
                    } catch (IllegalArgumentException ex) {
                        sender.sendMessage(prefix() + color("&c" + ex.getMessage()));
                    }
                } else {
                    baseHelp(sender);
                }
            }
            case "delete", "remove" -> {
                if (args.length < 3) { sender.sendMessage(color("&cUsage: /aegis base delete <name>")); return; }
                sender.sendMessage(prefix() + color(bases.delete(args[2]) ? "&aDeleted base &f" + args[2] :
                        "&cBase not found: &f" + args[2]));
            }
            case "list" -> {
                List<String> all = bases.describeAll();
                sender.sendMessage(prefix() + color("&bBases: &f" + all.size()));
                for (String line : all) sender.sendMessage(color("&7- &f" + line));
            }
            case "info" -> {
                if (args.length < 3) { sender.sendMessage(color("&cUsage: /aegis base info <name>")); return; }
                String info = bases.describe(args[2]);
                sender.sendMessage(prefix() + color(info == null ? "&cBase not found." : "&f" + info));
            }
            default -> baseHelp(sender);
        }
    }

    private void handleDupe(CommandSender sender, String[] args) {
        if (args.length < 2) {
            dupeHelp(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> sender.sendMessage(prefix() + color("&bDupeGuard: &f" + dupeGuard.status() +
                    " &7| PacketEvents=" + (packetGuard != null && packetGuard.isActive() ? "enabled" : "disabled")));
            case "scan" -> {
                if (args.length >= 3) {
                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) { sender.sendMessage(prefix() + color("&cPlayer is not online.")); return; }
                    dupeGuard.scanPlayer(target);
                    sender.sendMessage(prefix() + color("&aScanned inventory of &f" + target.getName()));
                } else {
                    dupeGuard.requestFullScan();
                    sender.sendMessage(prefix() + color("&aFull dupe scan queued across loaded chunks."));
                }
            }
            case "incidents", "cases" -> {
                List<String> incidents = dupeGuard.incidents();
                sender.sendMessage(prefix() + color("&bDupe incidents: &f" + incidents.size()));
                for (String line : incidents.stream().limit(15).toList()) sender.sendMessage(color("&7- &f" + line));
            }
            case "quarantine", "q" -> {
                if (args.length < 3 || args[2].equalsIgnoreCase("list")) {
                    List<String> entries = dupeGuard.quarantineList();
                    sender.sendMessage(prefix() + color("&bQuarantined items: &f" + entries.size()));
                    for (String line : entries.stream().limit(20).toList()) sender.sendMessage(color("&7- &f" + line));
                    return;
                }
                if (args[2].equalsIgnoreCase("restore")) {
                    if (args.length < 5) { sender.sendMessage(prefix() + color("&cUsage: /aegis dupe quarantine restore <id> <online-player>")); return; }
                    try {
                        int id = Integer.parseInt(args[3]);
                        Player target = Bukkit.getPlayerExact(args[4]);
                        if (target == null) { sender.sendMessage(prefix() + color("&cPlayer is not online.")); return; }
                        sender.sendMessage(prefix() + color(dupeGuard.restoreQuarantine(id, target) ?
                                "&aRestored quarantine item #" + id + " to " + target.getName() :
                                "&cRestore failed. Check item ID and target inventory space."));
                    } catch (NumberFormatException ex) { sender.sendMessage(prefix() + color("&cInvalid quarantine ID.")); }
                    return;
                }
                if (args[2].equalsIgnoreCase("delete")) {
                    if (args.length < 4) { sender.sendMessage(prefix() + color("&cUsage: /aegis dupe quarantine delete <id>")); return; }
                    try {
                        int id = Integer.parseInt(args[3]);
                        sender.sendMessage(prefix() + color(dupeGuard.deleteQuarantine(id) ?
                                "&aDeleted quarantine record #" + id : "&cQuarantine record not found."));
                    } catch (NumberFormatException ex) { sender.sendMessage(prefix() + color("&cInvalid quarantine ID.")); }
                    return;
                }
                dupeHelp(sender);
            }
            default -> dupeHelp(sender);
        }
    }

    private void dupeHelp(CommandSender sender) {
        sender.sendMessage(prefix() + color("&bDupeGuard is automatic: it monitors transactions and loaded chunks continuously."));
        sender.sendMessage(color("&f/aegis dupe status &7- show automatic protection state"));
        sender.sendMessage(color("&f/aegis dupe incidents &7- view automatic detections and rollbacks"));
        sender.sendMessage(color("&f/aegis dupe scan [player] &7- optional manual diagnostic; not required for protection"));
        sender.sendMessage(color("&f/aegis dupe quarantine list"));
        sender.sendMessage(color("&f/aegis dupe quarantine restore <id> <player>"));
        sender.sendMessage(color("&f/aegis dupe quarantine delete <id>"));
    }

    private void handleOnlineTarget(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(prefix() + color("&cUsage: /aegis " + args[0] + " <online-player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(prefix() + color("&cPlayer is not online."));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> {
                sender.sendMessage(prefix() + color("&b" + target.getName() + " &7total VL: &f" +
                        String.format(Locale.US, "%.1f", checks.totalVl(target))));
                sender.sendMessage(color("&7Checks: &f" + checks.snapshot(target)));
            }
            case "debug" -> sender.sendMessage(prefix() + color("&b" + target.getName() + " &7" + checks.debug(target)));
            case "reset" -> {
                checks.reset(target);
                sender.sendMessage(prefix() + color("&aReset violations for &f" + target.getName()));
            }
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(prefix() + color("&bAegisAC 1.7.2 commands"));
        sender.sendMessage(color("&f/aegis info <player> &7- show violation scores"));
        sender.sendMessage(color("&f/aegis debug <player> &7- diagnostic snapshot"));
        sender.sendMessage(color("&f/aegis reset <player> &7- clear in-memory scores"));
        sender.sendMessage(color("&f/aegis ban <player> <duration|perm> <reason>"));
        sender.sendMessage(color("&f/aegis tempban <player> <duration> <reason>"));
        sender.sendMessage(color("&f/aegis ban <player> <reason> &7- permanent ban"));
        sender.sendMessage(color("&f/aegis pardon <player> &7- remove Aegis ban"));
        sender.sendMessage(color("&f/aegis banlist &7- list active Aegis bans"));
        sender.sendMessage(color("&f/aegis kick <player> <reason>"));
        sender.sendMessage(color("&f/aegis base create <name> <world> <x> <y> <z> [radius]"));
        sender.sendMessage(color("&f/aegis base create <name> <world> <x1> <y1> <z1> <x2> <y2> <z2>"));
        sender.sendMessage(color("&f/aegis base delete <name> &7- remove protected base"));
        sender.sendMessage(color("&f/aegis base list|info <name>"));
        sender.sendMessage(color("&f/aegis fly &7- toggle admin-only flight in Survival"));
        sender.sendMessage(color("&f/aegis alerts &7- toggle staff alerts"));
        sender.sendMessage(color("&f/aegis dupe status|incidents|quarantine &7- automatic anti-dupe protection"));
        sender.sendMessage(color("&f/aegis reload &7- reload configuration"));
    }

    private void baseHelp(CommandSender sender) {
        sender.sendMessage(prefix() + color("&bBase protection commands"));
        sender.sendMessage(color("&f/aegis base create <name> <world> <x> <y> <z> [radius]"));
        sender.sendMessage(color("&f/aegis base create <name> <world> <x1> <y1> <z1> <x2> <y2> <z2>"));
        sender.sendMessage(color("&f/aegis base delete <name>"));
        sender.sendMessage(color("&f/aegis base list"));
        sender.sendMessage(color("&f/aegis base info <name>"));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("help", "reload", "info", "debug", "reset", "ban", "tempban",
                "kick", "pardon", "unban", "banlist", "base", "alerts", "dupe", "fly");
        if (args.length == 2 && args[0].equalsIgnoreCase("base")) return List.of("create", "delete", "remove", "list", "info");
        if (args.length == 2 && args[0].equalsIgnoreCase("dupe")) return List.of("status", "scan", "incidents", "quarantine");
        if (args.length == 3 && args[0].equalsIgnoreCase("dupe") && args[1].equalsIgnoreCase("quarantine")) return List.of("list", "restore", "delete");
        if (args.length == 4 && args[0].equalsIgnoreCase("dupe") && args[1].equalsIgnoreCase("quarantine") && args[2].equalsIgnoreCase("restore")) return List.of("1");
        if (args.length == 3 && args[0].equalsIgnoreCase("base") &&
                List.of("delete", "remove", "info").contains(args[1].toLowerCase(Locale.ROOT))) return bases.names();
        if (args.length == 2 && List.of("info", "debug", "reset", "ban", "tempban", "kick", "pardon", "unban").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("tempban")) {
            return List.of("30m", "1h", "1d", "7d", "30d");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ban")) {
            return List.of("30m", "1h", "1d", "7d", "30d", "perm");
        }
        return Collections.emptyList();
    }
}
