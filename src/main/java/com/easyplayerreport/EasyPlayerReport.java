package com.easyplayerreport;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class EasyPlayerReport extends JavaPlugin {
    private ReportManager reportManager;
    private ReportDialog dialog;
    private StaffDialog staffDialog;
    private MyReportsDialog myReportsDialog;
    private ReportChatDialog reportChatDialog;
    private PlayerHistoryDialog playerHistoryDialog;
    private ReportStatsDialog reportStatsDialog;
    private ReportExporter reportExporter;
    private PermissionConfig permissionConfig;
    private final Set<UUID> staffNotificationsDisabled = new HashSet<>();
    private File staffNotificationsFile;
    private Menus menus;
    private Lang lang;
    private DiscordConfig discordConfig;
    private DiscordClient discordClient;
    private DiscordNotifier discordNotifier;
    private DiscordTickets discordTickets;
    private UpdateChecker updateChecker;

    /**
     * Comprueba que este servidor expone la Paper Dialog API, sin la cual no hay
     * formulario de reporte. Existe desde Paper 1.21.7; en Spigot, CraftBukkit o
     * Paper anterior no esta y el plugin no tiene interfaz alternativa.
     */
    private boolean dialogApiDisponible() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
            for (Method method : org.bukkit.entity.Player.class.getMethods()) {
                if (method.getName().equals("showDialog")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void onEnable() {
        if (!dialogApiDisponible()) {
            getLogger().severe("No se puede habilitar EasyPlayerReport: este servidor no expone la Paper Dialog API.");
            getLogger().severe("EasyPlayerReport requires Paper 1.21.7 or newer (Paper Dialog API). Plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        migrateOldDataFolder();
        saveDefaultConfig();
        // Igual que con los idiomas: config.yml tampoco se sobrescribe si ya
        // existe, asi que sin esto las opciones nuevas (como check-update)
        // funcionarian pero el admin no veria nunca la opcion para cambiarla.
        java.util.List<String> added = YamlMerge.mergeDefaults(this, "config.yml", new File(getDataFolder(), "config.yml"));
        if (!added.isEmpty()) {
            getLogger().info(added.size() == 1
                    ? "Se añadió la opción nueva de config.yml: " + added.get(0)
                    : "Se añadieron " + added.size() + " opciones nuevas a config.yml: " + String.join(", ", added));
        }
        reloadConfig();
        staffNotificationsFile = new File(getDataFolder(), "staff-notifications.yml");
        loadStaffNotifications();
        lang = new Lang(this);
        menus = new Menus(this);
        reportManager = new ReportManager(this);
        dialog = new ReportDialog(this);
        staffDialog = new StaffDialog(this);
        myReportsDialog = new MyReportsDialog(this);
        reportChatDialog = new ReportChatDialog(this);
        playerHistoryDialog = new PlayerHistoryDialog(this);
        reportStatsDialog = new ReportStatsDialog(this);
        reportExporter = new ReportExporter(this);
        permissionConfig = new PermissionConfig(this);

        // Avisos a Discord. Se inicializa siempre para tener el getter, pero
        // solo envia si enabled esta a true y hay token y canal.
        discordConfig = new DiscordConfig(this);
        discordConfig.load();
        discordClient = new DiscordClient(this, discordConfig);
        discordNotifier = new DiscordNotifier(this, discordConfig, discordClient);
        // Un canal de Discord por reporte, con el chat sincronizado. Se crea
        // antes que las tareas del plugin porque markTaken lo necesita.
        discordTickets = new DiscordTickets(this);
        discordTickets.start();
        updateChecker = new UpdateChecker(this);
        updateChecker.start();

        register("reportar", new ReportCommand(this));
        register("reportes", new MyReportsCommand(this));
        register("reportesadmin", new ReportsCommand(this));
        register("reportechat", new ReportChatCommand(this));
        register("reporteshistorial", new PlayerHistoryCommand(this));
        register("reportesestadisticas", new ReportStatsCommand(this));
        register("apelacion", new AppealCommand(this));
        register("reportestoggle", new StaffNotificationsCommand(this));
        register("easyplayerreport", new AdminCommand(this));
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                reportChatDialog.forget(event.getPlayer().getUniqueId());
                staffDialog.forget(event.getPlayer().getUniqueId());
            }
        }, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            reportManager.purgeExpired();
            reportManager.flush(false);
        }, 20L, 20L);

        getLogger().info("EasyPlayerReport habilitado. /reportar para jugadores, /reportes para consultas, /reportesadmin para staff.");
        if (discordNotifier.isReady()) {
            getLogger().info("Avisos a Discord activados hacia el canal " + discordConfig.channelId() + ".");
        } else if (discordConfig.enabled()) {
            getLogger().warning("Los avisos a Discord estan activados pero incompletos. Revisa discord/discord.yml.");
        }
    }

    /**
     * El plugin se llamaba OdisseyReports y ahora se llama EasyPlayerReport, asi
     * que Bukkit crea una carpeta de datos nueva y deja la antigua con la
     * configuracion y la base de datos de reportes dentro. Sin esto, al arrancar
     * pareceria que se han perdido todos los reportes.
     *
     * Se copia la carpeta antigua a la nueva solo si la nueva esta vacia, y la
     * vieja no se borra: sigue siendo la copia de seguridad. Si la nueva ya
     * tiene algo, se respeta y no se toca nada, por si alguien reinstalo.
     */
    private void migrateOldDataFolder() {
        File parent = getDataFolder().getParentFile();
        if (parent == null) return;
        File old = new File(parent, "OdisseyReports");
        File nuevo = getDataFolder();
        if (!old.isDirectory() || !old.getAbsolutePath().equals(nuevo.getAbsolutePath())) {
            if (old.isDirectory() && nuevo.isDirectory() && esVacia(nuevo)) {
                getLogger().warning("Se ha encontrado la carpeta antigua " + old.getName()
                        + " y se va a copiar a " + nuevo.getName() + ".");
                if (copiarCarpeta(old, nuevo)) {
                    getLogger().info("Copia terminada. La carpeta antigua se conserva como respaldo; "
                            + "puedes borrarla cuando confirmes que todo esta bien.");
                } else {
                    getLogger().warning("No se pudo copiar del todo. Revisa los permisos de "
                            + old.getName() + " y copia a mano config.yml, menus.yml, lang/ y "
                            + databaseFile() + ".");
                }
            }
        }
    }

    /** Nombre del fichero de base de datos, segun el tipo configurado. */
    private String databaseFile() {
        return "mysql".equalsIgnoreCase(getConfig().getString("database.type", "sqlite"))
                ? "(MySQL, la base esta en su servidor)" : "reports.db";
    }

    private boolean esVacia(File dir) {
        String[] contenido = dir.list();
        return contenido == null || contenido.length == 0;
    }

    private boolean copiarCarpeta(File desde, File hasta) {
        boolean todo = true;
        File[] hijos = desde.listFiles();
        if (hijos == null) return false;
        for (File hijo : hijos) {
            File destino = new File(hasta, hijo.getName());
            try {
                if (hijo.isDirectory()) {
                    destino.mkdirs();
                    todo &= copiarCarpeta(hijo, destino);
                } else {
                    java.nio.file.Files.copy(hijo.toPath(), destino.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.io.IOException exception) {
                getLogger().warning("No se pudo copiar " + hijo.getName() + ": " + exception.getMessage());
                todo = false;
            }
        }
        return todo;
    }

    /**
     * Registra un comando avisando en vez de reventar con NullPointerException
     * si plugin.yml y el codigo se desincronizan.
     */
    private void register(String name, org.bukkit.command.CommandExecutor executor) {
        org.bukkit.command.PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("El comando /" + name + " no está definido en plugin.yml y no se pudo registrar.");
            return;
        }
        command.setExecutor(executor);
    }

    @Override
    public void onDisable() {
        if (discordTickets != null) {
            discordTickets.stop();
        }
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (reportManager != null) {
            reportManager.close();
        }
        saveStaffNotifications();
    }

    public boolean areStaffNotificationsEnabled(org.bukkit.entity.Player player) {
        return !staffNotificationsDisabled.contains(player.getUniqueId());
    }

    public boolean toggleStaffNotifications(org.bukkit.entity.Player player) {
        UUID uuid = player.getUniqueId();
        if (staffNotificationsDisabled.remove(uuid)) {
            saveStaffNotifications();
            return true;
        }
        staffNotificationsDisabled.add(uuid);
        saveStaffNotifications();
        return false;
    }

    private void loadStaffNotifications() {
        if (!staffNotificationsFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(staffNotificationsFile);
        for (String value : config.getStringList("disabled")) {
            try {
                staffNotificationsDisabled.add(UUID.fromString(value));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Se ignoró un UUID inválido en staff-notifications.yml: " + value);
            }
        }
    }

    private void saveStaffNotifications() {
        if (staffNotificationsFile == null) return;
        YamlConfiguration config = new YamlConfiguration();
        config.set("disabled", staffNotificationsDisabled.stream().map(UUID::toString).sorted().toList());
        try {
            config.save(staffNotificationsFile);
        } catch (IOException exception) {
            getLogger().warning("No se pudo guardar staff-notifications.yml: " + exception.getMessage());
        }
    }

    public ReportManager getReportManager() { return reportManager; }
    public Menus getMenus() { return menus; }
    public Lang getLang() { return lang; }
    public ReportDialog getDialog() { return dialog; }
    public StaffDialog getStaffDialog() { return staffDialog; }
    public MyReportsDialog getMyReportsDialog() { return myReportsDialog; }
    public ReportChatDialog getReportChatDialog() { return reportChatDialog; }
    public PlayerHistoryDialog getPlayerHistoryDialog() { return playerHistoryDialog; }
    public ReportStatsDialog getReportStatsDialog() { return reportStatsDialog; }
    public ReportExporter getReportExporter() { return reportExporter; }
    public PermissionConfig getPermissionConfig() { return permissionConfig; }
    public DiscordConfig getDiscordConfig() { return discordConfig; }
    public DiscordNotifier getDiscordNotifier() { return discordNotifier; }
    public DiscordTickets getDiscordTickets() { return discordTickets; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }

    /**
     * Reconstruye el cliente de Discord tras un reload. El HttpClient guarda el
     * timeout en el momento de crearse, asi que cambiarlo en la config no tendria
     * efecto sobre el cliente viejo.
     */
    public void reloadDiscordClient() {
        discordConfig.load();
        discordClient = new DiscordClient(this, discordConfig);
        discordNotifier = new DiscordNotifier(this, discordConfig, discordClient);
        // Los tickets se rearrancan tambien: su cliente es propio y la tarea de
        // lectura se queda parada si no se vuelve a programar.
        if (discordTickets != null) discordTickets.start();
    }

    /**
     * Segundos hasta que un reporte abierto se cierra solo (0 = nunca).
     * Soporta la clave nueva auto-close-seconds; si un config antiguo aún
     * tiene auto-close-hours, la convierte a segundos.
     */
    public int getAutoCloseSeconds() {
        if (getConfig().contains("auto-close-seconds")) {
            return Math.max(0, getConfig().getInt("auto-close-seconds", 0));
        }
        if (getConfig().contains("auto-close-hours")) {
            return Math.max(0, getConfig().getInt("auto-close-hours", 12)) * 3600;
        }
        return 0;
    }

    public int getAutoCloseSeconds(Permissible sender) {
        return permissionConfig.autoCloseSeconds(sender);
    }

    /**
     * Lee una duracion en segundos de config.yml, nunca negativa.
     * Todo los cooldowns y bloqueos pasan por aqui.
     */
    public int seconds(String path, int def) {
        return Math.max(0, getConfig().getInt(path, def));
    }

    /**
     * Lee un limite de config.yml (longitud de textos, elementos por pagina),
     * siempre al menos 1 porque la Dialog API no admite 0.
     */
    public int limit(String path, int def) {
        return Math.max(1, getConfig().getInt(path, def));
    }

    /**
     * Lee un valor numerico de config.yml que SI puede ser 0 (amplificadores
     * de efectos, "0 = sin limite" del historial del chat). Solo descarta lo
     * negativo, que no tiene sentido en ninguno de estos usos.
     */
    public int intOr(String path, int def) {
        return Math.max(0, getConfig().getInt(path, def));
    }

    /** Elementos por pagina en los menus con paginacion. */
    public int pageSize() {
        return limit("limits.reports-per-page", 6);
    }
}