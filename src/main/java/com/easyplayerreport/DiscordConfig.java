package com.easyplayerreport;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;

/**
 * Configuracion de los avisos a Discord, cargada de discord/discord.yml.
 *
 * La idea es que baste con poner el token del bot y el ID del canal. Todo lo
 * demas tiene un valor por defecto utilizable, y si falta lo esencial el
 * envio se queda apagado con un aviso claro en consola en vez de reventar.
 *
 * Nunca escribe el token en el log.
 */
public class DiscordConfig {
    private final EasyPlayerReport plugin;
    private File file;
    private YamlConfiguration cfg;

    public DiscordConfig(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    /** Crea discord/discord.yml si no existe y lo lee. */
    public void load() {
        File dir = new File(plugin.getDataFolder(), "discord");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta discord/. Los avisos a Discord quedan desactivados.");
        }
        file = new File(dir, "discord.yml");
        if (!file.exists()) {
            plugin.saveResource("discord/discord.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        warnAboutProblems();
    }

    private void warnAboutProblems() {
        if (!enabled()) return;
        if (token().isEmpty()) {
            plugin.getLogger().warning(
                    "Los avisos a Discord estan activados pero falta 'token' en discord/discord.yml. No se envio nada.");
        }
        if (channelId().isEmpty()) {
            plugin.getLogger().warning(
                    "Los avisos a Discord estan activados pero falta 'channel-id' en discord/discord.yml. No se envio nada.");
        }
    }

    /**
     * Si el envio puede funcionar: activado, con token y con canal.
     * Comprueba tambien que el ID del canal es solo numeros, porque
     * Discord devuelve un 404 dificil de entender si lleva texto.
     */
    public boolean isReady() {
        if (!enabled() || token().isEmpty() || channelId().isEmpty()) return false;
        return channelId().matches("\\d{15,25}");
    }

    public boolean enabled() { return cfg == null || cfg.getBoolean("enabled", false); }
    public String token() { return cfg == null ? "" : cfg.getString("token", "").trim(); }
    public String channelId() { return cfg == null ? "" : cfg.getString("channel-id", "").trim(); }
    public String guildId() { return cfg == null ? "" : cfg.getString("guild-id", "").trim(); }
    public String username() { return cfg == null ? "EasyPlayerReport" : cfg.getString("username", "EasyPlayerReport"); }
    public String avatarUrl() { return cfg == null ? "" : cfg.getString("avatar-url", "").trim(); }
    public String pingRoleId() { return cfg == null ? "" : cfg.getString("ping-role-id", "").trim(); }
    public boolean pingHere() { return cfg != null && cfg.getBoolean("ping-here", false); }
    public boolean includeCoordinates() { return cfg == null || cfg.getBoolean("include-coordinates", true); }
    public boolean includeDescription() { return cfg != null && cfg.getBoolean("include-description", true); }
    public int maxDescriptionLength() { return cfg == null ? 900 : Math.max(0, cfg.getInt("max-description-length", 900)); }
    public int timeoutSeconds() { return cfg == null ? 10 : Math.max(1, cfg.getInt("timeout-seconds", 10)); }
    public int retryAttempts() { return cfg == null ? 2 : Math.max(0, cfg.getInt("retry-attempts", 2)); }
    public boolean logErrors() { return cfg == null || cfg.getBoolean("log-errors", true); }
    public boolean testOnReload() { return cfg != null && cfg.getBoolean("test-on-reload", false); }

    /**
     * Color de los embeds. La prioridad se quito del plugin, asi que ya no hay
     * un color por nivel: se usa uno solo para todos los avisos.
     */
    public String color() {
        return normalizeColor(cfg == null ? null : cfg.getString("color", null), "#38bdf8");
    }

    /**
     * Discord exige el color en decimal y sin la almohadilla. Si alguien
     * escribe "eab308" o un color inventado, se cae al de por defecto
     * en vez de mandar la peticion y que Discord la rechace.
     */
    private String normalizeColor(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9a-fA-F]{6}")) return fallback;
        try {
            return String.valueOf(Integer.parseInt(value, 16));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Si hay que avisar de este tipo de evento. */
    public boolean shouldNotify(String event) {
        if (cfg == null) return false;
        return cfg.getBoolean("notify-on." + event, false);
    }

    /**
     * Si al tomar un reporte en el juego se crea un canal de Discord para ese
     * reporte, con el chat del reporte sincronizado en los dos sentidos.
     *
     * Antes esta opcion era "mandar un boton para que otro servicio lo pulsara".
     * Ahora el canal se crea solo, asi que no hace falta ningun servicio: el
     * bot le el historial del canal por la API REST cada poll-seconds.
     */
    public boolean ticketEnabled() { return cfg != null && cfg.getBoolean("ticket.enabled", false); }
    public String ticketCategoryId() { return cfg == null ? "" : cfg.getString("ticket.category-id", "").trim(); }
    public String ticketChannelName() {
        return cfg == null ? "reporte-{id}" : cfg.getString("ticket.channel-name", "reporte-{id}");
    }
    /** Rol de Discord al que se le da acceso al canal nuevo, para que no lo vea todo el mundo. */
    public String ticketStaffRoleId() { return cfg == null ? "" : cfg.getString("ticket.staff-role-id", "").trim(); }
    /** Que pasa con el canal cuando se cierra el reporte: archive, delete o none. */
    public String ticketCloseAction() {
        String value = cfg == null ? "archive" : cfg.getString("ticket.close-action", "archive");
        return value == null ? "archive" : value.trim().toLowerCase(Locale.ROOT);
    }
    public int ticketPollSeconds() {
        return cfg == null ? 3 : Math.max(1, cfg.getInt("ticket.poll-seconds", 3));
    }
    public int ticketMaxMessagesPerPoll() {
        return cfg == null ? 50 : Math.max(1, Math.min(100, cfg.getInt("ticket.max-messages-per-poll", 50)));
    }
    /** Si lo que se escribe en el chat del juego se manda al canal. */
    public boolean ticketSyncToDiscord() { return cfg != null && cfg.getBoolean("ticket.sync-to-discord", true); }
    /** Si lo que se escribe en el canal aparece en el chat del juego. */
    public boolean ticketSyncToGame() { return cfg == null || cfg.getBoolean("ticket.sync-to-game", true); }
    /** Ignora los mensajes de otros bots al leer el canal. */
    public boolean ticketIgnoreBots() { return cfg == null || cfg.getBoolean("ticket.ignore-bots", true); }
    /** Pone el enlace del archivo cuando el mensaje lleva una imagen o un adjunto. */
    public boolean ticketShowAttachments() { return cfg == null || cfg.getBoolean("ticket.show-attachments", true); }
    /** Al reabrir un reporte, desarchivar el canal para seguir escribiendo en el. */
    public boolean ticketUnarchiveOnReopen() { return cfg == null || cfg.getBoolean("ticket.unarchive-on-reopen", true); }

    /**
     * Los tickets necesitan saber en que servidor de Discord crear el canal, y
     * por eso el ID del servidor. La API de crear canales es /guilds/{id}/channels
     * y no admite adivinar el servidor.
     */
    public boolean ticketReady() {
        return enabled() && !token().isEmpty() && ticketEnabled()
                && guildId().matches("\\d{15,25}") && ticketCategoryId().matches("\\d{15,25}");
    }

    /**
     * Si el bloque ticket esta en su sitio.
     *
     * Se comprueba porque es facil pegar enabled: true sin el "ticket:" delante y
     * entonces el plugin se queda en false sin decir nada. Con esto se avisa por
     * consola en vez de fallar en silencio.
     */
    public boolean hasTicketSection() {
        return cfg != null && cfg.isConfigurationSection("ticket");
    }

    /**
     * Si falta algo de lo imprescindible para los tickets, dice cual. Se llama al
     * arrancar para que el problema se vea en consola y no tres dias despues,
     * cuando alguien toma un reporte y no aparece ningun canal.
     */
    public String ticketProblem() {
        if (!ticketEnabled()) return "";
        if (token().isEmpty()) return "ticket.enabled esta a true pero falta 'token'.";
        if (!guildId().matches("\\d{15,25}")) {
            return "ticket.enabled esta a true pero falta 'guild-id' (ID del servidor de Discord).";
        }
        if (!ticketCategoryId().matches("\\d{15,25}")) {
            return "ticket.enabled esta a true pero falta 'ticket.category-id'.";
        }
        if (!ticketStaffRoleId().isEmpty() && !ticketStaffRoleId().matches("\\d{15,25}")) {
            return "ticket.staff-role-id no parece un ID de Discord.";
        }
        return "";
    }

    public File getFile() { return file; }
}
