package dev.aegis.ac;

import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.UUID;

public final class ModernLoginListener implements Listener {
    private final BanManager bans;

    public ModernLoginListener(BanManager bans) { this.bans = bans; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onValidate(PlayerConnectionValidateLoginEvent event) {
        Object profile = extractProfile(event.getConnection());
        if (profile == null) return;
        String name = invokeString(profile, "getName");
        UUID uuid = invokeUuid(profile, "getId");
        String denial = bans.checkLogin(name, uuid);
        if (denial != null) {
            // kickMessage() is the current Paper API for disallowing this connection.
            event.kickMessage(Component.text(org.bukkit.ChatColor.stripColor(denial)));
        }
    }

    private Object extractProfile(Object connection) {
        for (String methodName : new String[]{"getAuthenticatedProfile", "getUnsafeProfile", "getProfile"}) {
            try {
                Method method = findPublicMethod(connection.getClass(), methodName);
                if (method == null) continue;
                Object value = method.invoke(connection);
                if (value != null) return value;
            } catch (ReflectiveOperationException ignored) {
                // Connection is in a different login/configuration phase; try the next profile accessor.
            }
        }
        return null;
    }

    private Method findPublicMethod(Class<?> type, String name) {
        for (Class<?> iface : type.getInterfaces()) {
            try { return iface.getMethod(name); }
            catch (NoSuchMethodException ignored) {
                Method inherited = findPublicMethod(iface, name);
                if (inherited != null) return inherited;
            }
        }
        Class<?> parent = type.getSuperclass();
        if (parent != null) {
            try { return parent.getMethod(name); }
            catch (NoSuchMethodException ignored) {
                return findPublicMethod(parent, name);
            }
        }
        try { return type.getMethod(name); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    private String invokeString(Object profile, String methodName) {
        try {
            Method method = findPublicMethod(profile.getClass(), methodName);
            Object value = method == null ? null : method.invoke(profile);
            return value instanceof String s ? s : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private UUID invokeUuid(Object profile, String methodName) {
        try {
            Method method = findPublicMethod(profile.getClass(), methodName);
            Object value = method == null ? null : method.invoke(profile);
            return value instanceof UUID id ? id : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
