package dev.aegis.ac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

final class BanManager {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);
    private final AegisAC plugin;
    private final File file;
    // Login validation can run away from the primary server thread.
    private final Map<String, BanRecord> byName = new ConcurrentHashMap<>();
    private final Map<UUID, BanRecord> byUuid = new ConcurrentHashMap<>();

    BanManager(AegisAC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bans.yml");
        load();
        registerLoginListener();
        Bukkit.getScheduler().runTaskTimer(plugin, this::purgeExpired, 1200L, 1200L);
    }

    record BanRecord(String name, UUID uuid, String reason, String source, long createdAt, long expiresAt) {
        boolean permanent() { return expiresAt <= 0; }
        boolean expired(long now) { return !permanent() && now >= expiresAt; }
    }
    record BanResult(boolean success, String message) {}

    static boolean isDurationToken(String raw) {
        if (raw == null) return false;
        String token = raw.toLowerCase(Locale.ROOT);
        if (Set.of("perm", "permanent", "forever").contains(token)) return true;
        try { parseDuration(token); return true; } catch (RuntimeException ex) { return false; }
    }

    static Duration parseDuration(String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing duration.");
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (Set.of("perm", "permanent", "forever").contains(s)) return null;
        if (s.isEmpty()) throw new IllegalArgumentException("Empty duration.");
        Matcher matcher = DURATION_PART.matcher(s);
        int cursor = 0;
        Duration out = Duration.ZERO;
        try {
            while (matcher.find()) {
                if (matcher.start() != cursor) throw new IllegalArgumentException("Invalid duration: " + raw);
                long amount = Long.parseLong(matcher.group(1));
                if (amount <= 0) throw new IllegalArgumentException("Duration must be positive.");
                Duration part = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    case "d" -> Duration.ofDays(amount);
                    case "w" -> Duration.ofDays(Math.multiplyExact(amount, 7));
                    default -> throw new IllegalArgumentException("Invalid duration unit.");
                };
                out = out.plus(part);
                cursor = matcher.end();
            }
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("Duration is too large.");
        }
        if (cursor != s.length() || out.isZero() || out.isNegative()) throw new IllegalArgumentException("Invalid duration: " + raw);
        if (out.compareTo(Duration.ofDays(3650)) > 0) throw new IllegalArgumentException("Maximum ban duration is 10 years.");
        return out;
    }

    static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (seconds > 0 || parts.isEmpty()) parts.add(seconds + "s");
        return String.join(" ", parts);
    }

    synchronized BanResult ban(String playerName, UUID suppliedUuid, Duration duration, String reason, String source) {
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}")) {
            return new BanResult(false, "Invalid Minecraft player name.");
        }
        String requestedName = playerName;
        String key = requestedName.toLowerCase(Locale.ROOT);
        Player online = Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(requestedName))
                .findFirst().orElse(null);
        UUID uuid = suppliedUuid;
        if (online != null) {
            playerName = online.getName();
            uuid = online.getUniqueId();
        }
        // Offline bans are keyed by normalized name. Avoid a blocking profile lookup on the server thread.
        long now = System.currentTimeMillis();
        long expiresAt = duration == null ? 0L : Math.addExact(now, duration.toMillis());
        BanRecord record = new BanRecord(playerName, uuid, clean(reason), source == null ? "Console" : source, now, expiresAt);
        BanRecord previous = byName.put(key, record);
        if (previous != null && previous.uuid() != null) byUuid.remove(previous.uuid());
        if (uuid != null) byUuid.put(uuid, record);
        save();

        if (online != null) {
            online.kickPlayer(plugin.color("&cYou are banned from this server.\n&7Reason: &f" + record.reason() +
                    (duration == null ? "\n&7Duration: &fPermanent" : "\n&7Expires: &f" + Instant.ofEpochMilli(expiresAt))));
        }
        String durationText = duration == null ? "permanently" : "for " + formatDuration(duration);
        Bukkit.broadcastMessage(plugin.prefix() + plugin.color("&c" + playerName + " &7was banned " + durationText +
                ". &8Reason: &f" + record.reason()));
        return new BanResult(true, "Ban stored.");
    }

    synchronized boolean pardon(String playerName) {
        if (playerName == null) return false;
        String key = playerName.toLowerCase(Locale.ROOT);
        BanRecord removed = byName.remove(key);
        Player online = Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(playerName))
                .findFirst().orElse(null);
        if (online != null) {
            BanRecord byId = byUuid.get(online.getUniqueId());
            if (removed == null) removed = byId;
        }
        if (removed != null) {
            removeRecord(removed);
            save();
        }
        return removed != null;
    }

    String checkLogin(String playerName, UUID uuid) {
        String key = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
        BanRecord record = uuid == null ? null : byUuid.get(uuid);
        if (record == null) record = byName.get(key);
        if (record == null) return null;
        if (record.expired(System.currentTimeMillis())) {
            // Do not perform YAML file I/O on the login/connection thread.
            Bukkit.getScheduler().runTask(plugin, () -> {
                BanRecord current = byName.get(key);
                if (current == record) {
                    removeRecord(record);
                    save();
                }
            });
            return null;
        }
        String expires = record.permanent() ? "Permanent" : Instant.ofEpochMilli(record.expiresAt()).toString();
        return plugin.color("&cYou are banned from this server.\n&7Reason: &f" + record.reason() +
                "\n&7Expires: &f" + expires);
    }

    private void registerLoginListener() {
        try {
            Class.forName("io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent");
            Class<?> handlerClass = Class.forName("dev.aegis.ac.ModernLoginListener");
            Listener listener = (Listener) handlerClass.getConstructor(BanManager.class).newInstance(this);
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            plugin.getLogger().info("Using Paper connection validation login event for Aegis bans.");
        } catch (ReflectiveOperationException | LinkageError modernUnavailable) {
            Bukkit.getPluginManager().registerEvents(new LegacyLoginListener(this), plugin);
            plugin.getLogger().info("Using legacy login event fallback for Aegis bans.");
        }
    }

    List<String> list() {
        purgeExpired();
        List<String> out = new ArrayList<>();
        for (BanRecord b : byName.values()) {
            out.add(b.name() + " | " + (b.permanent() ? "Permanent" : Instant.ofEpochMilli(b.expiresAt())) + " | " + b.reason());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void removeRecord(BanRecord record) {
        byName.remove(record.name().toLowerCase(Locale.ROOT));
        if (record.uuid() != null) byUuid.remove(record.uuid());
    }

    private synchronized void purgeExpired() {
        long now = System.currentTimeMillis();
        List<BanRecord> expired = byName.values().stream().filter(r -> r.expired(now)).toList();
        if (expired.isEmpty()) return;
        for (BanRecord record : expired) removeRecord(record);
        save();
    }

    private void load() {
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("bans");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(key);
            if (node == null) continue;
            UUID uuid = null;
            String rawUuid = node.getString("uuid", "");
            try { if (!rawUuid.isBlank()) uuid = UUID.fromString(rawUuid); } catch (IllegalArgumentException ignored) {}
            BanRecord record = new BanRecord(node.getString("name", key),
                    uuid, node.getString("reason", "AegisAC ban"),
                    node.getString("source", "Unknown"), node.getLong("created-at"),
                    node.getLong("expires-at"));
            if (!record.expired(System.currentTimeMillis())) {
                byName.put(key.toLowerCase(Locale.ROOT), record);
                if (uuid != null) byUuid.put(uuid, record);
            }
        }
        purgeExpired();
    }

    synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, BanRecord> entry : byName.entrySet()) {
            BanRecord record = entry.getValue();
            String path = "bans." + entry.getKey();
            yaml.set(path + ".name", record.name());
            yaml.set(path + ".uuid", record.uuid() == null ? "" : record.uuid().toString());
            yaml.set(path + ".reason", record.reason());
            yaml.set(path + ".source", record.source());
            yaml.set(path + ".created-at", record.createdAt());
            yaml.set(path + ".expires-at", record.expiresAt());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save bans.yml: " + ex.getMessage());
        }
    }

    private String clean(String reason) {
        if (reason == null || reason.isBlank()) return "No reason provided";
        return ChatColor.stripColor(reason).replace('\n', ' ').replace('\r', ' ').trim();
    }
}
