package com.easyplayerreport;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * Carga los mensajes del plugin desde la carpeta lang/lang_<idioma>.yml
 * (config "lang": "es" o "en"). Si un mensaje falta, cae al antiguo bloque
 * messages de config.yml por compatibilidad.
 */
public class Lang {
    private final EasyPlayerReport plugin;
    private YamlConfiguration cfg;

    public Lang(EasyPlayerReport plugin) {
        this.plugin = plugin;
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!new File(langDir, "lang_es.yml").exists()) plugin.saveResource("lang/lang_es.yml", false);
        if (!new File(langDir, "lang_en.yml").exists()) plugin.saveResource("lang/lang_en.yml", false);
        reload();
    }

    public void reload() {
        String lang = plugin.getConfig().getString("lang", "es");
        File f = new File(new File(plugin.getDataFolder(), "lang"), "lang_" + lang + ".yml");
        if (!f.exists()) {
            f = new File(new File(plugin.getDataFolder(), "lang"), "lang_es.yml");
        }
cfg = YamlConfiguration.loadConfiguration(f);
        // Un jugador que actualiza el plugin ya tiene lang_<idioma>.yml en su
        // carpeta, y Bukkit no lo sobrescribe: sin esto, los mensajes nuevos se
        // quedaban en blanco en vez de aparecer. Se usa el nombre del archivo
        // que de verdad se abrio, por si hubo que caer al de español.
        List<String> added = YamlMerge.mergeDefaults(plugin, "lang/" + f.getName(), f);
        if (!added.isEmpty()) {
            plugin.getLogger().info(added.size() == 1
                    ? "Se añadió el texto nuevo a " + f.getName() + ": " + added.get(0)
                    : "Se añadieron " + added.size() + " textos nuevos a " + f.getName() + ": " + String.join(", ", added));
        }
    }

    public String msg(String path, String def) {
        String v = cfg.getString(path);
        if (v != null) return v;
        String legacy = plugin.getConfig().getString(path, def);
        return legacy == null ? def : legacy;
    }

    public String colorMsg(String path, String def) {
        return ChatColor.translateAlternateColorCodes('&', msg(path, def));
    }

    /**
     * Nombre del estado en el idioma configurado, con su color de Minecraft.
     * Un solo punto de traduccion para todo el plugin: antes cada dialogo
     * tenia su propio mapa y los que usaban Report.Status.name() se quedaban
     * en ingles aunque lang fuera es.
     */
    public String statusLabel(Report.Status status) {
        return msg("statuses." + status.name().toLowerCase(java.util.Locale.ROOT), fallbackStatus(status));
    }

    /** Igual que statusLabel pero sin el codigo de color, para la UI con color propio. */
    public String plainStatusLabel(Report.Status status) {
        return ChatColor.stripColor(statusLabel(status));
    }

    private static String fallbackStatus(Report.Status status) {
        return switch (status) {
            case OPEN -> "&6Abierto";
            case TAKEN -> "&bEn proceso";
            case CLOSED -> "&aCerrado";
        };
    }

    public static String fmtRemaining(long millis) {
        long totalSec = Math.max(0, (millis + 999) / 1000);
        if (totalSec < 60) return totalSec + "s";
        long totalMin = (totalSec + 59) / 60;
        if (totalMin < 60) return totalMin + " min";
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h + "h " + m + "m";
    }

    /**
     * Duración en palabras: "1 hora", "30 minutos", "45 segundos", "2 horas 15 minutos".
     * Se usa en los mensajes que el jugador lee, donde "1h 0m" queda feo.
     * Es de instancia y no estatica porque las unidades se traducen.
     */
    public String fmtHuman(long millis) {
        long totalSec = Math.max(0, (millis + 999) / 1000);
        if (totalSec < 60) return unit(totalSec, "time.second");
        long totalMin = (totalSec + 59) / 60;
        if (totalMin < 60) return unit(totalMin, "time.minute");
        long h = totalMin / 60;
        long m = totalMin % 60;
        if (m == 0) return unit(h, "time.hour");
        return unit(h, "time.hour") + " " + unit(m, "time.minute");
    }

    private String unit(long amount, String path) {
        String single = msg(path, "segundo");
        String plural = msg(path + "-plural", single + "s");
        return amount + " " + (amount == 1 ? single : plural);
    }

    /**
     * Duracion en palabras sin traducir, para la consola y los logs, donde el
     * ingles es lo esperado. La UI del jugador pasa por fmtHuman.
     */
    public static String fmtHumanPlain(long millis) {
        long totalSec = Math.max(0, (millis + 999) / 1000);
        if (totalSec < 60) return plural(totalSec, "second");
        long totalMin = (totalSec + 59) / 60;
        if (totalMin < 60) return plural(totalMin, "minute");
        long h = totalMin / 60;
        long m = totalMin % 60;
        if (m == 0) return plural(h, "hour");
        return plural(h, "hour") + " " + plural(m, "minute");
    }

    private static String plural(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}