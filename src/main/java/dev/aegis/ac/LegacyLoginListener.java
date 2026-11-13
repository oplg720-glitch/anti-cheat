package dev.aegis.ac;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

final class LegacyLoginListener implements Listener {
    private final BanManager bans;

    LegacyLoginListener(BanManager bans) { this.bans = bans; }

    @EventHandler(priority = EventPriority.HIGHEST)
    @SuppressWarnings("deprecation")
    public void onLogin(PlayerLoginEvent event) {
        String denial = bans.checkLogin(event.getPlayer().getName(), event.getPlayer().getUniqueId());
        if (denial != null) event.disallow(PlayerLoginEvent.Result.KICK_BANNED, denial);
    }
}
