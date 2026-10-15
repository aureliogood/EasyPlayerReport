package com.easyplayerreport;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.permissions.Permissible;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReportManager {
    private static final long RETENTION_INTERVAL = 300000L;
    private final List<Report> reports = new ArrayList<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> appealCooldowns = new HashMap<>();
    private final Map<UUID, Long> falseReportBlocks = new HashMap<>();
    private final Map<Integer, List<String>> chatLog = new HashMap<>();
    private final Map<Integer, List<Report.Event>> deletedEvents = new HashMap<>();
    private final EasyPlayerReport plugin;
    private final File backupDir;
    private ReportStore store;
    private boolean databaseActive;
    private boolean dirty;
    private long lastWrite;
    private long lastRetention;
    private int nextId = 1;

    public ReportManager(EasyPlayerReport plugin) {
        this.plugin = plugin;
        this.backupDir = new File(plugin.getDataFolder(), "backupreports");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta backupreports.");
        }
        initializeStore();
    }

    private void initializeStore() {
        String type = plugin.getConfig().getString("database.type", "sqlite").trim().toLowerCase();
        try {
            if (!type.equals("sqlite") && !type.equals("mysql")) {
                plugin.getLogger().warning("Tipo de base de datos no válido; se usará YAML como fallback.");
                store = new YamlStore(plugin);
                store.initialize();
                applyData(store.load());
                return;
            }
            SqlStore sqlStore = new SqlStore(plugin, type);
            sqlStore.initialize();
            store = sqlStore;
            databaseActive = true;
            DatabaseData data = sqlStore.load();
            if (!sqlStore.isMigrated()) {
                if (data.isEmpty()) {
                    YamlStore yamlStore = new YamlStore(plugin);
                    DatabaseData migrated = yamlStore.load();
                    sqlStore.save(migrated);
                    data = migrated;
                    if (!migrated.isEmpty()) {
                        sqlStore.markMigrated();
                        plugin.getLogger().info("Migración YAML a " + type + " completada; reports.yml se conserva.");
                    }
                } else {
                    sqlStore.markMigrated();
                    plugin.getLogger().info("La base de datos ya contenía datos; no se sobrescribió con reports.yml.");
                }
            }
            applyData(data);
        } catch (Exception exception) {
            if (store != null) {
                try {
                    store.close();
                } catch (Exception ignored) {
                }
            }
            databaseActive = false;
            YamlStore yamlStore = new YamlStore(plugin);
            store = yamlStore;
            plugin.getLogger().warning("No se pudo usar la base de datos " + type + " (" + exception.getClass().getSimpleName() + "); se usará reports.yml como fallback.");
            applyData(yamlStore.load());
        }
    }

    public Report createReport(UUID reporter, String reporterName, String target, UUID targetUuid,
                               String reason, String description, org.bukkit.Location reporterLoc, org.bukkit.Location targetLoc) {
        return createReport(reporter, reporterName, plugin.getServer().getPlayer(reporter), target, targetUuid,
                reason, description, reporterLoc, targetLoc);
    }

    public Report createReport(UUID reporter, String reporterName, Permissible permissionSource, String target, UUID targetUuid,
                               String reason, String description, org.bukkit.Location reporterLoc, org.bukkit.Location targetLoc) {
        int seconds = plugin.getAutoCloseSeconds(permissionSource);
        long now = System.currentTimeMillis();
        Report report = new Report(nextId++, reporter, reporterName, target, reason, description, now);
        report.setExpiresAt(seconds > 0 ? now + seconds * 1000L : 0);
        report.setTargetUuid(targetUuid);
        report.setReporterLocation(reporterLoc);
        report.setTargetLocation(targetLoc);
        report.addEvent("CREATED", reporter, reporterName, "Reportado: " + target + " (" + reason + ")");
        reports.add(report);
        // Un reporte nuevo se persiste de inmediato: perderlo por un cierre
        // inesperado del servidor es lo unico que no se puede recuperar.
        save();
        flush(true);
        return report;
    }

    public Report createReport(UUID reporter, String reporterName, String target, String reason, String description) {
        return createReport(reporter, reporterName, target, null, reason, description, null, null);
    }

    public Report getById(int id) {
        purgeExpired();
        for (Report report : reports) if (report.getId() == id) return report;
        return null;
    }

    public List<Report> getAll() {
        List<Report> out = new ArrayList<>(reports);
        out.sort(Comparator.comparingLong(Report::getTimestamp).reversed());
        return out;
    }

    public List<Report> getActive() {
        purgeExpired();
        return filtered(true);
    }

    public List<Report> getClosed() {
        purgeExpired();
        return filtered(false);
    }

    private List<Report> filtered(boolean active) {
        List<Report> out = new ArrayList<>();
        for (Report report : reports) if (report.isActive() == active) out.add(report);
        out.sort(active ? Comparator.comparingLong(Report::getTimestamp) : Comparator.comparingLong(Report::getTimestamp).reversed());
        return out;
    }

    public long activeCountFor(UUID uuid) {
        purgeExpired();
        long count = 0;
        for (Report report : reports) if (report.getReporter().equals(uuid) && report.isActive()) count++;
        return count;
    }

    public List<Report> getActiveFor(UUID uuid) {
        purgeExpired();
        List<Report> out = new ArrayList<>();
        for (Report report : reports) if (report.getReporter().equals(uuid) && report.isActive()) out.add(report);
        out.sort(Comparator.comparingLong(Report::getTimestamp).reversed());
        return out;
    }

    public List<Report> getRelatedFor(UUID uuid) {
        purgeExpired();
        List<Report> out = new ArrayList<>();
        for (Report report : reports) {
            if (report.isActive() && (report.getReporter().equals(uuid) || uuid.equals(report.getTargetUuid()))) {
                out.add(report);
            }
        }
        out.sort(Comparator.comparingLong(Report::getTimestamp).reversed());
        return out;
    }

    public List<Report> getCreatedFor(UUID uuid) {
        purgeExpired();
        List<Report> out = new ArrayList<>();
        for (Report report : reports) {
            if (report.getReporter().equals(uuid)) out.add(report);
        }
        out.sort(Comparator.comparingLong(Report::getTimestamp).reversed());
        return out;
    }

    public List<Report> getReceivedFor(UUID uuid) {
        purgeExpired();
        List<Report> out = new ArrayList<>();
        for (Report report : reports) {
            if (uuid.equals(report.getTargetUuid())) out.add(report);
        }
        out.sort(Comparator.comparingLong(Report::getTimestamp).reversed());
        return out;
    }

    public boolean markTaken(Report report, UUID staff, String staffName) {
        if (report == null || report.getStatus() != Report.Status.OPEN) return false;
        report.setAssigned(staff, staffName);
        report.addEvent("TAKEN", staff, staffName, "El reporte fue tomado");
        save();
        plugin.getDiscordNotifier().notifyTaken(report, staffName);
        // El canal del ticket se crea aqui: el staff ya esta en el juego y ya ha
        // tomado el reporte, asi que no tiene que hacer nada mas.
        plugin.getDiscordTickets().onTaken(report, staffName);
        return true;
    }

    public boolean markReassigned(Report report, UUID staff, String staffName) {
        if (report == null || report.getStatus() != Report.Status.TAKEN || staff == null) return false;
        String previous = report.getAssignedStaffName();
        report.setAssigned(staff, staffName);
        report.addEvent("REASSIGNED", staff, staffName, "Reasignado desde " + (previous == null ? "otro staff" : previous));
        save();
        return true;
    }

    public boolean markReleased(Report report, UUID staff, String staffName) {
        if (report == null || report.getStatus() != Report.Status.TAKEN) return false;
        report.release();
        report.addEvent("RELEASED", staff, staffName, "El reporte volvió a estar abierto");
        save();
        return true;
    }

    public boolean markClosed(Report report, UUID actor, String actorName) {
        if (report == null || !report.isActive()) return false;
        report.close();
        report.addEvent("CLOSED", actor, actorName, "El reporte fue cerrado");
        archiveClosed(report);
        save();
        plugin.getDiscordNotifier().notifyClosed(report, actorName);
        plugin.getDiscordTickets().onClosed(report);
        return true;
    }

    public boolean markClosed(Report report) {
        return markClosed(report, null, "Sistema");
    }

    public long reopenCooldownLeft(Report report) {
        return cooldownSinceEvent(report, "REOPENED", "reopen-cooldown-seconds", 0);
    }

    /**
     * Marca un reporte como falso: lo cierra, avisa al que lo creó y le bloquea
     * los reportes durante false-report-block-seconds. Es la defensa contra el
     * spam de reportes, que el cooldown normal no cubre porque el jugador puede
     * esperar los 60 segundos entre cada uno.
     */
    public boolean markFalseReport(Report report, UUID actor, String actorName) {
        if (report == null || !report.isActive()) return false;
        int seconds = falseReportBlockSeconds();
        long now = System.currentTimeMillis();
        report.addEvent("FALSE_REPORT", actor, actorName, "Marcado como reporte falso por " + actorName);
        if (seconds > 0) falseReportBlocks.put(report.getReporter(), now + seconds * 1000L);
        report.close();
        report.addEvent("CLOSED", actor, actorName, "Cerrado automaticamente: reporte falso");
        archiveClosed(report);
        save();
        plugin.getDiscordNotifier().notifyFalseReport(report, actorName);
        // Tambien se cierra: un reporte falso es un reporte cerrado, asi que su
        // canal de Discord se archiva igual.
        plugin.getDiscordTickets().onClosed(report);
        return true;
    }

    /**
     * Milisegundos que le quedan a este jugador para poder reportar, o 0 si no
     * está bloqueado por un reporte falso.
     */
    public long falseReportBlockLeft(UUID player) {
        Long until = falseReportBlocks.get(player);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            falseReportBlocks.remove(player);
            return 0;
        }
        return left;
    }

    public int falseReportBlockSeconds() {
        return plugin.seconds("false-report-block-seconds", 3600);
    }

    private void pruneFalseReportBlocks() {
        long now = System.currentTimeMillis();
        falseReportBlocks.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    /**
     * Solo se puede apelar el jugador reportado, y una sola vez por reporte. El
     * reporte puede estar abierto o cerrado: apelar en activo sirve para
     * defenderse antes de que el staff decida. El cooldown
     * appeal-cooldown-seconds es global por jugador para que no se pueda
     * apelar en cadena contra distintos reportes.
     */
    public boolean addAppeal(Report report, UUID actor, String authorName, String text) {
        if (report == null || actor == null || !actor.equals(report.getTargetUuid())
                || text == null || text.isBlank() || report.hasAppeal()) return false;
        if (appealCooldownLeft(actor) > 0) return false;
        String appealText = text.trim();
        String appealAuthorName = authorName == null ? "" : authorName.trim();
        long now = System.currentTimeMillis();
        report.setAppeal(appealText, actor, appealAuthorName, now);
        report.addEvent("APPEALED", actor, appealAuthorName, appealText);
        int seconds = plugin.seconds("appeal-cooldown-seconds", 300);
        if (seconds > 0) appealCooldowns.put(actor, now + seconds * 1000L);
        archiveClosed(report);
        save();
        plugin.getDiscordNotifier().notifyAppeal(report);
        return true;
    }

    public boolean hasAppeal(Report report) {
        return report != null && report.hasAppeal();
    }

    /**
     * Segundos restantes antes de que este jugador pueda apelar otro reporte (0 = puede).
     */
    public long appealCooldownLeft(UUID player) {
        int seconds = plugin.seconds("appeal-cooldown-seconds", 300);
        if (seconds == 0) return 0;
        Long until = appealCooldowns.get(player);
        if (until == null) return 0;
        long left = (until - System.currentTimeMillis() + 999) / 1000;
        if (left <= 0) {
            appealCooldowns.remove(player);
            return 0;
        }
        return left;
    }

    private long cooldownSinceEvent(Report report, String eventType, String configKey, int def) {
        if (report == null) return 0;
        int seconds = plugin.seconds(configKey, def);
        if (seconds == 0) return 0;
        long last = 0;
        for (int i = report.getActionHistory().size() - 1; i >= 0; i--) {
            if (report.getActionHistory().get(i).getType().equals(eventType)) {
                last = report.getActionHistory().get(i).getTimestamp();
                break;
            }
        }
        if (last == 0) return 0;
        return Math.max(0, (last + seconds * 1000L - System.currentTimeMillis() + 999) / 1000);
    }

    public boolean markReopened(Report report, UUID actor, String actorName) {
        if (report == null || report.getStatus() != Report.Status.CLOSED || reopenCooldownLeft(report) > 0) return false;
        return reopen(report, actor, actorName, "REOPENED", "El reporte cerrado fue reabierto");
    }

    private boolean reopen(Report report, UUID actor, String actorName, String eventType, String detail) {
        // getPlayer(null) revienta: el actor puede ser el sistema (actor nulo).
        int seconds = plugin.getAutoCloseSeconds(actor == null ? null : plugin.getServer().getPlayer(actor));
        long now = System.currentTimeMillis();
        report.release();
        report.setClosedAt(0);
        report.setExpiresAt(seconds > 0 ? now + seconds * 1000L : 0);
        report.addEvent(eventType, actor, actorName, detail);
        save();
        plugin.getDiscordNotifier().notifyReopened(report, actorName);
        plugin.getDiscordTickets().onReopened(report);
        return true;
    }

    public void markDeleted(Report report, UUID actor, String actorName) {
        if (report == null) return;
        report.addEvent("DELETED", actor, actorName, "El reporte fue eliminado por el staff");
        deletedEvents.put(report.getId(), new ArrayList<>(report.getActionHistory()));
        reports.remove(report);
        chatLog.remove(report.getId());
        File backup = new File(backupDir, "report_" + report.getId() + ".yml");
        if (backup.exists() && !backup.delete()) plugin.getLogger().warning("No se pudo eliminar el respaldo del reporte #" + report.getId() + ".");
        save();
    }

    public void markDeleted(Report report) {
        markDeleted(report, null, "Sistema");
    }

    public boolean addNote(Report report, UUID actor, String actorName, String note) {
        if (report == null || note == null || note.trim().isEmpty()) return false;
        report.setStaffNote(note);
        report.addEvent("NOTE", actor, actorName, "Nota privada añadida");
        save();
        return true;
    }

    public void addModerationEvent(Report report, UUID actor, String actorName, String action) {
        if (report == null) return;
        report.addEvent("MODERATION", actor, actorName, action);
        report.addEvent(action, actor, actorName, "Moderación sobre " + report.getTarget());
        save();
        // Al final, y despues del guardado: si el aviso falla, el registro
        // interno ya esta, que es lo que no se puede perder.
        plugin.getDiscordNotifier().notifyModeration(report, actorName, action);
    }

    public Report findDuplicate(UUID reporter, String target) {
        purgeExpired();
        int window = plugin.seconds("duplicate-window-minutes", 5);
        if (window == 0) return null;
        long cutoff = System.currentTimeMillis() - window * 60000L;
        for (Report report : reports) {
            if (report.isActive() && report.getReporter().equals(reporter)
                    && report.getTarget().equalsIgnoreCase(target) && report.getTimestamp() >= cutoff) return report;
        }
        return null;
    }

    public List<String> getChatLines(int id) {
        return new ArrayList<>(chatLog.getOrDefault(id, List.of()));
    }

    public boolean addChatMessage(int id, String sender, String message) {
        boolean added = appendChat(id, sender + ": " + message);
        // Solo se avisa a Discord cuando el mensaje viene del juego. Los que
        // llegan de Discord se meten con addChatLine, que no pasa por aqui, y asi
        // no hay eco: el plugin no reenvia a Discord lo que acaba de leer de alli.
        if (added) plugin.getDiscordTickets().onChatMessage(getById(id), sender, message);
        return added;
    }

    /**
     * Anade al chat una linea ya formateada, sin avisar a nadie.
     * Se usa para el historial que se carga desde el almacen.
     */
    public boolean addChatLine(int id, String line) {
        return appendChat(id, line);
    }

    private boolean appendChat(int id, String line) {
        Report report = getById(id);
        if (report == null || !report.isActive() || report.getAssignedStaff() == null) return false;
        int max = plugin.seconds("chat-max-messages", 100);
        List<String> lines = chatLog.computeIfAbsent(id, ignored -> new ArrayList<>());
        lines.add(line);
        if (max > 0) while (lines.size() > max) lines.remove(0);
        save();
        return true;
    }

    public boolean hasCooldown(UUID uuid) {
        return hasCooldown(uuid, null);
    }

    public boolean hasCooldown(UUID uuid, Permissible permissionSource) {
        Long last = cooldowns.get(uuid);
        if (last == null) return false;
        long duration = cooldownSeconds(permissionSource) * 1000L;
        if (System.currentTimeMillis() - last >= duration) {
            cooldowns.remove(uuid);
            return false;
        }
        return true;
    }

    public long cooldownLeft(UUID uuid) {
        return cooldownLeft(uuid, null);
    }

    public long cooldownLeft(UUID uuid, Permissible permissionSource) {
        Long last = cooldowns.get(uuid);
        if (last == null) return 0;
        long duration = cooldownSeconds(permissionSource) * 1000L;
        long left = duration - (System.currentTimeMillis() - last);
        if (left <= 0) cooldowns.remove(uuid);
        return Math.max(0, left);
    }

    public void setCooldown(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis());
        save();
    }

    private long cooldownSeconds(Permissible permissionSource) {
        return plugin.getPermissionConfig().limits(permissionSource).cooldownSeconds();
    }

    public long getRemainingSeconds(Report report) {
        if (report == null || !report.isActive() || report.getExpiresAt() <= 0) return 0;
        return Math.max(0, (report.getExpiresAt() - System.currentTimeMillis() + 999) / 1000);
    }

    public int applyAutoCloseDuration() {
        int seconds = plugin.getAutoCloseSeconds();
        long now = System.currentTimeMillis();
        for (Report report : reports) if (report.isActive()) report.setExpiresAt(seconds > 0 ? now + seconds * 1000L : 0);
        save();
        return seconds;
    }

    public boolean purgeExpired() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        pruneFalseReportBlocks();
        List<Report> autoClosed = new ArrayList<>();
        for (Report report : new ArrayList<>(reports)) {
            if (report.isActive() && report.getExpiresAt() > 0 && report.getExpiresAt() <= now) {
                report.close();
                report.addEvent("CLOSED", null, "Sistema", "Cierre automático por tiempo");
                archiveClosed(report);
                autoClosed.add(report);
                changed = true;
            }
        }
        // La retencion recorre todos los reportes y lista la carpeta de backups,
        // y el timer llama a purgeExpired() cada segundo: hacerlo cada vez
        // gastaba disco y CPU en bucle. Con 5 minutos de margen es
        // indetectable para el staff y no toca nada del flujo de reportes.
        if (now - lastRetention >= RETENTION_INTERVAL) {
            lastRetention = now;
            changed |= cleanupRetention();
        }
        if (changed) {
            save();
            for (Report report : autoClosed) notifyAutoClosed(report);
        }
        return changed;
    }

    private void notifyAutoClosed(Report report) {
        var reporter = plugin.getServer().getPlayer(report.getReporter());
        if (reporter == null || !reporter.isOnline()) return;
        String message = plugin.getLang().msg("messages.auto-closed", "&7Tu reporte #%id% se cerró automáticamente por tiempo.");
        reporter.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message.replace("%id%", String.valueOf(report.getId()))));
    }

    private boolean cleanupRetention() {
        int closedDays = plugin.seconds("retention.closed-reports-days", 30);
        int backupDays = plugin.seconds("retention.backups-days", 30);
        boolean changed = false;
        long now = System.currentTimeMillis();
        if (closedDays > 0) {
            long cutoff = now - closedDays * 86400000L;
            for (Report report : new ArrayList<>(reports)) {
                if (!report.isActive() && (report.getClosedAt() > 0 ? report.getClosedAt() : report.getTimestamp()) < cutoff) {
                    archiveClosed(report);
                    reports.remove(report);
                    chatLog.remove(report.getId());
                    changed = true;
                }
            }
        }
        if (backupDays > 0 && backupDir.exists()) {
            long cutoff = now - backupDays * 86400000L;
            try (var files = Files.list(backupDir.toPath())) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    String name = path.getFileName().toString();
                    if (name.matches("report_\\d+\\.yml") && Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                        changed = true;
                    }
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("No se pudo limpiar la retención de backups: " + exception.getMessage());
            }
        }
        // El historial de los reportes eliminados tambien se guardaba para
        // siempre y se reescribia entero en cada guardado. Se poda con la misma
        // retencion que los backups.
        if (closedDays > 0 && !deletedEvents.isEmpty()) {
            long cutoff = now - closedDays * 86400000L;
            var iterator = deletedEvents.entrySet().iterator();
            while (iterator.hasNext()) {
                List<Report.Event> events = iterator.next().getValue();
                long last = events.isEmpty() ? 0 : events.get(events.size() - 1).getTimestamp();
                if (last < cutoff) {
                    iterator.remove();
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean archiveClosed(Report report) {
        if (report.isActive()) return false;
        File file = new File(backupDir, "report_" + report.getId() + ".yml");
        try {
            YamlConfiguration conf = new YamlConfiguration();
            conf.set("version", 3);
            conf.set("id", report.getId());
            conf.set("reporter", report.getReporter().toString());
            conf.set("reporter-name", report.getReporterName());
            conf.set("target", report.getTarget());
            conf.set("target-uuid", report.getTargetUuid() == null ? null : report.getTargetUuid().toString());
            conf.set("reason", report.getReason());
            conf.set("description", report.getDescription());
            conf.set("timestamp", report.getTimestamp());
            conf.set("expires-at", report.getExpiresAt());
            conf.set("closed-at", report.getClosedAt());
            conf.set("status", report.getStatus().name());
            conf.set("assigned-staff", report.getAssignedStaff() == null ? null : report.getAssignedStaff().toString());
            conf.set("assigned-staff-name", report.getAssignedStaffName());
            conf.set("staff-note", report.getStaffNote());
            conf.set("appeal-text", report.getAppealText());
            conf.set("appeal-author", report.getAppealAuthor() == null ? null : report.getAppealAuthor().toString());
            conf.set("appeal-author-name", report.getAppealAuthorName());
            conf.set("appeal-at", report.getAppealAt());
            setLocation(conf, "reporter-location", report.getReporterWorld(), report.getReporterX(), report.getReporterY(), report.getReporterZ());
            setLocation(conf, "target-location", report.getTargetWorld(), report.getTargetX(), report.getTargetY(), report.getTargetZ());
            conf.set("chat", getChatLines(report.getId()));
            setHistory(conf, report);
            writeAtomically(conf, file);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo guardar el respaldo del reporte #" + report.getId() + ": " + exception.getMessage());
            return false;
        }
    }

    /**
     * Marca los datos como pendientes de escritura. Antes cada cambio de estado
     * (tomar, cerrar, nota...) reescribia la tabla completa de
     * reportes; ahora solo se marca y el timer hace flush segun
     * save-interval-seconds.
     */
    public synchronized void save() {
        dirty = true;
    }

    /**
     * Escribe los cambios pendientes. force=true ignora el intervalo y escribe
     * siempre que haya algo sucio (reload del admin y apagado del servidor).
     */
    public synchronized void flush(boolean force) {
        if (!dirty) return;
        if (!force) {
            int interval = plugin.seconds("save-interval-seconds", 3);
            if (interval > 0 && System.currentTimeMillis() - lastWrite < interval * 1000L) return;
        }
        DatabaseData data = snapshot();
        try {
            store.save(data);
            dirty = false;
            lastWrite = System.currentTimeMillis();
        } catch (Exception exception) {
            if (databaseActive) {
                try {
                    store.close();
                } catch (Exception ignored) {
                }
                databaseActive = false;
                YamlStore yamlStore = new YamlStore(plugin);
                store = yamlStore;
                plugin.getLogger().warning("Falló la persistencia de base de datos (" + exception.getClass().getSimpleName() + "); se usará reports.yml como fallback.");
                yamlStore.save(data);
            } else {
                ((YamlStore) store).save(data);
            }
            dirty = true;
        }
    }

    private DatabaseData snapshot() {
        DatabaseData data = new DatabaseData();
        data.setNextId(nextId);
        data.getReports().addAll(reports);
        data.getCooldowns().putAll(cooldowns);
        data.getAppealCooldowns().putAll(appealCooldowns);
        data.getFalseReportBlocks().putAll(falseReportBlocks);
        data.getChatLog().putAll(chatLog);
        data.getDeletedEvents().putAll(deletedEvents);
        return data;
    }

    private void applyData(DatabaseData data) {
        reports.clear();
        reports.addAll(data.getReports());
        cooldowns.clear();
        cooldowns.putAll(data.getCooldowns());
        appealCooldowns.clear();
        appealCooldowns.putAll(data.getAppealCooldowns());
        falseReportBlocks.clear();
        falseReportBlocks.putAll(data.getFalseReportBlocks());
        chatLog.clear();
        chatLog.putAll(data.getChatLog());
        deletedEvents.clear();
        deletedEvents.putAll(data.getDeletedEvents());
        nextId = Math.max(1, data.getNextId());
        for (Report report : reports) {
            if (report.getId() >= nextId) nextId = report.getId() + 1;
            if (report.isActive() && report.getExpiresAt() <= 0) {
                int seconds = plugin.getAutoCloseSeconds();
                report.setExpiresAt(seconds > 0 ? report.getTimestamp() + seconds * 1000L : 0);
            }
            if (!report.isActive()) archiveClosed(report);
        }
        cleanupRetention();
    }

    private void writeAtomically(YamlConfiguration conf, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("No se pudo crear la carpeta de datos");
        Path temp = file.toPath().resolveSibling(file.getName() + ".tmp");
        Files.writeString(temp, conf.saveToString(), java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE);
        try {
            Files.move(temp, file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void close() {
        flush(true);
        try {
            if (store != null) store.close();
        } catch (Exception exception) {
            plugin.getLogger().warning("No se pudo cerrar el almacén de datos: " + exception.getMessage());
        }
    }

    private void setLocation(ConfigurationSection section, String path, String world, int x, int y, int z) {
        if (world == null) return;
        section.set(path + ".world", world);
        section.set(path + ".x", x);
        section.set(path + ".y", y);
        section.set(path + ".z", z);
    }

    private void setHistory(ConfigurationSection section, Report report) {
        setHistory(section, report.getActionHistory());
    }

    private void setHistory(ConfigurationSection section, List<Report.Event> history) {
        for (int i = 0; i < history.size(); i++) {
            Report.Event event = history.get(i);
            String path = "action-history." + i;
            section.set(path + ".type", event.getType());
            section.set(path + ".actor", event.getActor() == null ? null : event.getActor().toString());
            section.set(path + ".actor-name", event.getActorName());
            section.set(path + ".timestamp", event.getTimestamp());
            section.set(path + ".detail", event.getDetail());
        }
    }
}
