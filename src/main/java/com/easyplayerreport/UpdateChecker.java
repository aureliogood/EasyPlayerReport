package com.easyplayerreport;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Avisa en el chat cuando hay una version nueva del plugin subida.
 *
 * La comprobacion se hace por HTTP a la API publica de SpigotMC y se cachea:
 * al arrancar se pregunta una vez y luego cada check-interval-seconds, de
 * forma que entrar al servidor no hace ninguna peticion de red. Todo va en
 * tareas asincronas, asi que ni el arranque ni el login se frenan.
 *
 * Si el JSON que devuelve SpigotMC no se entiende, o no hay red, o el
 * intervalo se pone a 0, el plugin sigue funcionando igual y no dice nada:
 * esto es una comodidad, nunca una dependencia.
 *
 * Con check-update: false no se hace ninguna peticion ni se manda mensaje.
 */
public class UpdateChecker implements Listener {
    private final EasyPlayerReport plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private String latestVersion = "";
    private String latestUrl = "";
    private int taskId = -1;
    private boolean notified = false;
    private boolean warnedAboutConfig = false;
    private boolean listening = false;
    private YamlConfiguration settings;

    public UpdateChecker(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ config

    private File configFile() {
        return new File(plugin.getDataFolder(), "update.yml");
    }

    /**
     * Lee update.yml. Se cachea porque enabled() y permission() se consultan en
     * cada entrada de jugador: leer el fichero dos veces por cada login en el
     * hilo principal es de las cosas que mas se notan cuando hay Mucho trafico.
     */
    private YamlConfiguration load() {
        if (settings != null) return settings;
        File file = configFile();
        if (!file.exists()) plugin.saveResource("update.yml", false);
        settings = YamlConfiguration.loadConfiguration(file);
        return settings;
    }

    public boolean enabled() {
        if (!plugin.getConfig().getBoolean("check-update", true)) return false;
        return load().getBoolean("enabled", true);
    }

    /**
     * La API de SpigotMC necesita el id del recurso y el GUID del servidor. Sin
     * los dos no hay nada que preguntar, asi que mejor decirlo una vez por
     * consola que ir soltando errores en cada comprobacion.
     */
    private boolean isConfigured(YamlConfiguration cfg) {
        String custom = cfg.getString("url", "").trim();
        if (!custom.isEmpty()) return true;
        boolean hasResource = cfg.getInt("resource-id", 0) > 0;
        boolean hasServer = !cfg.getString("server-id", "").trim().isEmpty()
                && !cfg.getString("server-id", "").trim().equals("00000000000000000000000000000000");
        if (!hasResource || !hasServer) {
            if (!warnedAboutConfig) {
                warnedAboutConfig = true;
                plugin.getLogger().warning("La comprobacion de actualizaciones esta activada pero update.yml no tiene "
                        + "'resource-id' y 'server-id'. Ponlos con la pagina de tu recurso en SpigotMC, o rellena 'url' "
                        + "con tu propia API. Si no, no se avisara de actualizaciones.");
            }
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ ciclo

    public void start() {
        stop();
        settings = null;
        if (!enabled()) {
            plugin.getLogger().info("La comprobacion de actualizaciones esta desactivada (check-update: false).");
            return;
        }
        // El listener se registra una sola vez. Bukkit NO deduplica: sin el
        // unregister de stop(), cada /easyplayerreport reload dejaba una copia
        // mas y el recuadro de actualización salia una vez por cada reload.
        if (!listening) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            listening = true;
        }

        YamlConfiguration cfg = load();
        if (!isConfigured(cfg)) return;

        long intervalTicks = Math.max(1200L, cfg.getLong("check-interval-seconds", 21600L) * 20L);
        // El primer aviso se retrasa un poco para no competir con el arranque.
        taskId = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::check, 100L, intervalTicks)
                .getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        if (listening) {
            HandlerList.unregisterAll(this);
            listening = false;
        }
    }

    /** Vuelve a leer la config. Lo llama /easyplayerreport reload. */
    public void reload() {
        latestVersion = "";
        latestUrl = "";
        notified = false;
        start();
    }

    // ------------------------------------------------------------ red

    private void check() {
        YamlConfiguration cfg = load();
        String url = endpoint(cfg);
        if (url.isEmpty()) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "EasyPlayerReport/" + currentVersion())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return;
            String version = extractVersion(response.body());
            if (version.isEmpty()) return;
            latestVersion = version;
            latestUrl = downloadUrl(cfg);
            if (cfg.getBoolean("console", true) && isNewer(version, currentVersion())) {
                plugin.getLogger().info("Hay una version nueva de EasyPlayerReport: " + version
                        + " (la de este servidor es " + currentVersion() + "). Descargala en " + latestUrl);
            }
        } catch (Exception exception) {
            // Sin red, con el servidor caido o con un 403: no se molesta al
            // admin en consola cada seis horas por algo que no es grave.
            if (cfg.getBoolean("log-errors", false)) {
                plugin.getLogger().warning("No se pudo comprobar si hay actualizaciones: " + exception.getMessage());
            }
        }
    }

    private String endpoint(YamlConfiguration cfg) {
        String custom = cfg.getString("url", "").trim();
        if (!custom.isEmpty()) return custom;
        int resource = cfg.getInt("resource-id", 0);
        String server = cfg.getString("server-id", "").trim();
        if (resource <= 0 || server.isEmpty()) return "";
        try {
            return "https://api.spigotmc.org/legacy/update.php?resource=" + resource
                    + "&server=" + URLEncoder.encode(server, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "";
        }
    }

    /**
     * La API de SpigotMC devuelve {"success":true,...,"version":"1.2.3"}. Si el
     * administrador pone su propia URL se espera el mismo formato: lo unico
     * que hace falta es el campo "version".
     */
    private String extractVersion(String body) {
        try {
            Map<String, Object> json = Json.object(body);
            String version = Json.string(json, "version");
            return version == null ? "" : version.trim();
        } catch (Exception exception) {
            return "";
        }
    }

    private String downloadUrl(YamlConfiguration cfg) {
        String custom = cfg.getString("download-url", "").trim();
        if (!custom.isEmpty()) return custom;
        int resource = cfg.getInt("resource-id", 0);
        return resource > 0 ? "https://www.spigotmc.org/resources/" + resource + "/" : "";
    }

    // ------------------------------------------------------------ aviso

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled() || notified || latestVersion.isEmpty()) return;
        Player player = event.getPlayer();
        if (!player.hasPermission(permission())) return;
        if (!isNewer(latestVersion, currentVersion())) return;
        // Una vez por arrastre de la sesion: si el jugador se reconecta no se
        // le repite el aviso.
        notified = true;
        String url = latestUrl;
        String separator = plugin.getLang().msg("messages.update-separator", "&6&m--------------------------------------------");
        player.sendMessage(Menus.build(separator, NamedTextColor.GOLD));
        player.sendMessage(Component.empty());
        for (String raw : lines()) {
            player.sendMessage(Menus.build(raw, NamedTextColor.WHITE));
        }
        if (!url.isEmpty()) {
            player.sendMessage(Menus.build(
                    plugin.getLang().msg("messages.update-download-link", "<u>Click here to download the update.</u>"),
                    NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text(url).color(NamedTextColor.GRAY))));
        }
        player.sendMessage(Menus.build(separator, NamedTextColor.GOLD));
    }

    private String permission() {
        return load().getString("notify-permission", "easyplayerreport.staff");
    }

    /** Las lineas de texto del recuadro, con los %version% ya puestos. */
    private String[] lines() {
        String header = plugin.getLang().msg("messages.update-header",
                "<red><bold>EasyPlayerReport</bold> <white>Update Available:");
        String version = plugin.getLang().msg("messages.update-version",
                "<white>A new version is available: <green>%version%").replace("%version%", latestVersion);
        return new String[]{header, version};
    }

    private String currentVersion() {
        String version = plugin.getPluginMeta().getVersion();
        return version == null || version.isEmpty() ? "0.0.0" : version;
    }

    static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    /**
     * Compara dos versiones por partes numericas. "1.10" cuenta como mas
     * grande que "1.9", que es justo donde el comparador de texto se
     * equivoca. Lo que no sea un numero se ignora, asi que los sufijos
     * ("1.2.0-SNAPSHOT", "1.2.0-beta3") no dan falsos positivos.
     */
    static int compare(String left, String right) {
        String[] a = (left == null ? "" : left).split("[.\\-]");
        String[] b = (right == null ? "" : right).split("[.\\-]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int first = number(a, i);
            int second = number(b, i);
            if (first != second) return Integer.compare(first, second);
            // Mismo numero y en una de las dos hay algo mas (un sufijo tipo
            // "beta"): esa version es la mas nueva. Sin esto, 1.2.0 y
            // 1.2.0-beta darian igual y quien tenga la beta no seria avisado.
            if (i < a.length && i >= b.length) {
                // a tiene un sufijo ("beta", "SNAPSHOT") y b se acabo: la
                // version con sufijo es la previa, asi que b es mas nueva.
                if (isSuffix(a, i)) return -1;
            } else if (i >= a.length && i < b.length) {
                if (isSuffix(b, i)) return 1;
            }
        }
        return 0;
    }

    /** Si la parte es un sufijo con texto, como "beta" o "SNAPSHOT". */
    private static boolean isSuffix(String[] parts, int index) {
        if (index >= parts.length) return false;
        String part = parts[index];
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) return true;
        }
        return false;
    }

    private static int number(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String part = parts[index];
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length() && Character.isDigit(part.charAt(i)); i++) digits.append(part.charAt(i));
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}