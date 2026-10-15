package com.easyplayerreport;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.permissions.Permissible;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class PermissionConfig {
    private final EasyPlayerReport plugin;

    private static final List<String> DEFAULT_REASONS = List.of(
            "⚔ Hacks / Cheats",
            "💬 Chat / Spam / Toxicidad",
            "🏰 Griefing / Robo",
            "🐛 Bug / Duplicacion");

    /**
     * Iconos que se anteponen a un motivo escrito sin emoji. Se pueden cambiar o
     * ampliar con la seccion reason-icons de config.yml.
     */
    private static final Map<String, String> FALLBACK_ICONS = Map.ofEntries(
            Map.entry("hack", "⚔"), Map.entry("cheat", "⚔"), Map.entry("trampa", "⚔"),
            Map.entry("chat", "💬"), Map.entry("spam", "💬"), Map.entry("toxic", "💬"), Map.entry("acoso", "💬"),
            Map.entry("grief", "🏰"), Map.entry("robo", "🏰"), Map.entry("ladron", "🏰"),
            Map.entry("bug", "🐛"), Map.entry("duplica", "🐛"), Map.entry("crash", "🐛"));

    public PermissionConfig(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    public List<String> reasons(Permissible sender) {
        List<String> global = plugin.getConfig().getStringList("reasons").stream()
                .filter(value -> value != null && !value.isBlank())
                .map(Menus::normalizeUnicode)
                .map(this::withIcon)
                .toList();
        if (global.isEmpty()) global = DEFAULT_REASONS;
        if (!enabled()) return global;
        List<String> allowed = new ArrayList<>();
        for (String reason : global) if (canUse(sender, "reason", reason)) allowed.add(reason);
        return allowed;
    }

    /**
     * Antepone el icono que corresponda al motivo, salvo que el motivo ya
     * empiece por un simbolo (los emojis que pone el admin en reasons).
     */
    private String withIcon(String reason) {
        if (reason.isEmpty() || !Character.isLetterOrDigit(reason.charAt(0))) return reason;
        Map<String, String> icons = new LinkedHashMap<>(FALLBACK_ICONS);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("reason-icons");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String icon = section.getString(key, "");
                if (icon != null && !icon.isEmpty()) icons.put(key.toLowerCase(Locale.ROOT), icon);
            }
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : icons.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue() + " " + reason;
        }
        return reason;
    }

    public boolean canUseReason(Permissible sender, String reason) {
        return canUse(sender, "reason", Menus.normalizeUnicode(reason));
    }

    public int autoCloseSeconds(Permissible sender) {
        int fallback = plugin.getAutoCloseSeconds();
        if (!enabled() || sender == null) return fallback;
        return matchingRule("auto-close-seconds", sender)
                .map(rule -> Math.max(0, intValue(rule.get("value"), fallback))).orElse(fallback);
    }

    public Limits limits(Permissible sender) {
        int maxActive = plugin.limit("max-active-per-player", 3);
        int cooldown = plugin.seconds("cooldown-seconds", 60);
        if (!enabled() || sender == null) return new Limits(maxActive, cooldown);
        return matchingRule("limits", sender).map(rule -> new Limits(
                Math.max(1, intValue(rule.get("max-active-per-player"), maxActive)),
                Math.max(0, intValue(rule.get("cooldown-seconds"), cooldown))))
                .orElse(new Limits(maxActive, cooldown));
    }

    public boolean playStaffSound(Permissible sender) {
        if (!plugin.getConfig().getBoolean("sounds.enabled", true)) return false;
        if (!enabled() || sender == null) return true;
        return matchingRule("sounds", sender).map(rule -> boolValue(rule.get("enabled"), true)).orElse(true);
    }

    private boolean canUse(Permissible sender, String type, String value) {
        if (!enabled() || sender == null) return true;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("permission-config." + type);
        if (section == null) return true;
        ConfigurationSection rule = null;
        for (String key : clavesDeBusqueda(value)) {
            rule = section.getConfigurationSection(key);
            if (rule != null) break;
        }
        if (rule == null || !rule.getBoolean("use", true)) return rule != null;
        List<String> permissions = rule.getStringList("permissions");
        return permissions.isEmpty() || permissions.stream().anyMatch(sender::hasPermission);
    }

    /**
     * Un motivo llega aqui con el icono ya antepuesto ("⚔ Hacks / Cheats"),
     * pero en config.yml se escribe sin el ("Hacks / Cheats"). Si solo se
     * buscara el texto tal cual, ninguna regla casaria y, con la seccion vacia
     * de permisos, todos los motivos se filtrarian fuera: el jugador se
     * quedaria sin opciones de reporte. Por eso se prueba tambien la clave sin
     * el icono inicial.
     */
    private List<String> clavesDeBusqueda(String value) {
        List<String> keys = new ArrayList<>(2);
        if (value == null) return keys;
        keys.add(value);
        int inicio = 0;
        while (inicio < value.length() && !Character.isLetterOrDigit(value.charAt(inicio))) inicio++;
        String sinIcono = value.substring(inicio).stripLeading();
        if (inicio > 0 && !sinIcono.isEmpty()) keys.add(sinIcono);
        return keys;
    }

    private Optional<Map<?, ?>> matchingRule(String path, Permissible sender) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("permission-config." + path);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection rule = section.getConfigurationSection(key);
                if (rule != null && hasAnyPermission(rule.getStringList("permissions"), sender)) {
                    return Optional.of(rule.getValues(false));
                }
            }
        }
        for (Map<?, ?> rule : plugin.getConfig().getMapList("permission-config." + path)) {
            Object rawPermissions = rule.get("permissions");
            List<String> permissions = new ArrayList<>();
            if (rawPermissions instanceof Iterable<?> iterable) {
                for (Object permission : iterable) permissions.add(String.valueOf(permission));
            } else if (rawPermissions != null) {
                permissions.add(String.valueOf(rawPermissions));
            }
            if (hasAnyPermission(permissions, sender)) return Optional.of(rule);
        }
        return Optional.empty();
    }

    private boolean hasAnyPermission(List<String> permissions, Permissible sender) {
        return permissions.isEmpty() || permissions.stream().anyMatch(sender::hasPermission);
    }

    private int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private boolean boolValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("permission-config.enabled", false);
    }

    public record Limits(int maxActive, int cooldownSeconds) {
    }
}
