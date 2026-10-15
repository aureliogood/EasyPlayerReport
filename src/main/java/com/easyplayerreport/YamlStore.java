package com.easyplayerreport;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class YamlStore implements ReportStore {
    private final EasyPlayerReport plugin;
    private final File dataFile;

    public YamlStore(EasyPlayerReport plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "reports.yml");
    }

    @Override
    public void initialize() {
    }

    @Override
    public DatabaseData load() {
        DatabaseData data = new DatabaseData();
        if (!dataFile.exists()) return data;
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(dataFile);
        data.setNextId(conf.getInt("next-id", 1));
        ConfigurationSection reportSection = conf.getConfigurationSection("reports");
        int errors = 0;
        if (reportSection != null) {
            for (String key : reportSection.getKeys(false)) {
                ConfigurationSection section = reportSection.getConfigurationSection(key);
                if (section == null) continue;
                try {
                    Report report = deserialize(Integer.parseInt(key), section);
                    data.getReports().add(report);
                    List<String> chat = section.getStringList("chat");
                    if (!chat.isEmpty()) data.getChatLog().put(report.getId(), new ArrayList<>(chat));
                } catch (Exception exception) {
                    errors++;
                    plugin.getLogger().warning("Reporte corrupto #" + key + ": " + exception.getMessage());
                }
            }
        } else {
            List<String> invalid = new ArrayList<>();
            for (String line : conf.getStringList("reports")) {
                try {
                    Report report = deserializeLegacy(line);
                    data.getReports().add(report);
                } catch (Exception exception) {
                    errors++;
                    invalid.add(line);
                    plugin.getLogger().warning("Se ignoró un reporte antiguo inválido: " + exception.getMessage());
                }
            }
            quarantine(invalid);
        }
        readTimedSection(conf, "cooldowns", data.getCooldowns());
        readTimedSection(conf, "appeal-cooldowns", data.getAppealCooldowns());
        readTimedSection(conf, "false-report-blocks", data.getFalseReportBlocks());
        ConfigurationSection deletedSection = conf.getConfigurationSection("deleted-events");
        if (deletedSection != null) {
            for (String key : deletedSection.getKeys(false)) {
                try {
                    ConfigurationSection section = deletedSection.getConfigurationSection(key);
                    if (section != null) data.getDeletedEvents().put(Integer.parseInt(key), readHistory(section));
                } catch (Exception exception) {
                    plugin.getLogger().warning("Historial eliminado inválido ignorado: " + key);
                }
            }
        }
        for (Report report : data.getReports()) {
            if (report.getId() >= data.getNextId()) data.setNextId(report.getId() + 1);
            if (report.isActive() && report.getExpiresAt() <= 0) {
                int seconds = plugin.getAutoCloseSeconds();
                report.setExpiresAt(seconds > 0 ? report.getTimestamp() + seconds * 1000L : 0);
            }
        }
        if (errors > 0) plugin.getLogger().warning("Se cargaron " + data.getReports().size() + " reportes y se omitieron " + errors + " inválidos.");
        return data;
    }

    @Override
    public void save(DatabaseData data) {
        YamlConfiguration conf = new YamlConfiguration();
        conf.set("version", 3);
        conf.set("next-id", data.getNextId());
        for (Report report : data.getReports()) conf.set("reports." + report.getId(), serialize(report));
        for (var entry : data.getCooldowns().entrySet()) conf.set("cooldowns." + entry.getKey(), entry.getValue());
        for (var entry : data.getAppealCooldowns().entrySet()) conf.set("appeal-cooldowns." + entry.getKey(), entry.getValue());
        for (var entry : data.getFalseReportBlocks().entrySet()) conf.set("false-report-blocks." + entry.getKey(), entry.getValue());
        for (var entry : data.getChatLog().entrySet()) {
            for (Report report : data.getReports()) {
                if (report.getId() == entry.getKey()) conf.set("reports." + report.getId() + ".chat", entry.getValue());
            }
        }
        for (var entry : data.getDeletedEvents().entrySet()) {
            YamlConfiguration section = new YamlConfiguration();
            setHistory(section, entry.getValue());
            conf.set("deleted-events." + entry.getKey(), section);
        }
        try {
            writeAtomically(conf, dataFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo guardar reports.yml: " + exception.getMessage());
        }
    }

    @Override
    public boolean isMigrated() { return true; }
    @Override
    public void markMigrated() { }
    @Override
    public void close() { }

    private ConfigurationSection serialize(Report report) {
        YamlConfiguration section = new YamlConfiguration();
        section.set("reporter", report.getReporter().toString());
        section.set("reporter-name", report.getReporterName());
        section.set("target", report.getTarget());
        section.set("target-uuid", report.getTargetUuid() == null ? null : report.getTargetUuid().toString());
        section.set("reason", report.getReason());
        section.set("description", report.getDescription());
        section.set("timestamp", report.getTimestamp());
        section.set("expires-at", report.getExpiresAt());
        section.set("closed-at", report.getClosedAt());
        section.set("status", report.getStatus().name());
        section.set("assigned-staff", report.getAssignedStaff() == null ? null : report.getAssignedStaff().toString());
        section.set("assigned-staff-name", report.getAssignedStaffName());
        section.set("staff-note", report.getStaffNote());
        section.set("appeal-text", report.getAppealText());
        section.set("appeal-author", report.getAppealAuthor() == null ? null : report.getAppealAuthor().toString());
        section.set("appeal-author-name", report.getAppealAuthorName());
        section.set("appeal-at", report.getAppealAt());
        setLocation(section, "reporter-location", report.getReporterWorld(), report.getReporterX(), report.getReporterY(), report.getReporterZ());
        setLocation(section, "target-location", report.getTargetWorld(), report.getTargetX(), report.getTargetY(), report.getTargetZ());
        section.set("discord-channel-id", report.getDiscordChannelId());
        setHistory(section, report);
        return section;
    }

    private Report deserialize(int id, ConfigurationSection section) {
        Report report = new Report(id, UUID.fromString(section.getString("reporter")),
                section.getString("reporter-name", "Desconocido"), section.getString("target", "Desconocido"),
                section.getString("reason", "Sin motivo"), section.getString("description", ""), section.getLong("timestamp"));
        report.setTargetUuid(optionalUuid(section.getString("target-uuid")));
        report.setExpiresAt(section.getLong("expires-at"));
        String staff = section.getString("assigned-staff");
        if (staff != null && !staff.isBlank()) report.setAssigned(UUID.fromString(staff), section.getString("assigned-staff-name", "Desconocido"));
        Report.Status status;
        try {
            status = Report.Status.valueOf(section.getString("status", "OPEN"));
        } catch (IllegalArgumentException exception) {
            status = Report.Status.OPEN;
        }
        report.setStatus(status);
        if (status == Report.Status.OPEN) report.release();
        report.setClosedAt(section.getLong("closed-at"));
        report.setStaffNote(section.getString("staff-note", ""));
        report.setAppeal(section.getString("appeal-text", ""), optionalUuid(section.getString("appeal-author")),
                section.getString("appeal-author-name", ""), section.getLong("appeal-at"));
        loadLocation(section.getConfigurationSection("reporter-location"),
                value -> report.setReporterLocation(value.world(), value.x(), value.y(), value.z()));
        loadLocation(section.getConfigurationSection("target-location"),
                value -> report.setTargetLocation(value.world(), value.x(), value.y(), value.z()));
        report.setDiscordChannelId(section.getString("discord-channel-id", ""));
        report.addLoadedEvents(readHistory(section));
        return report;
    }

    private Report deserializeLegacy(String line) {
        List<String> values = splitEscaped(line);
        if (values.size() < 8) throw new IllegalArgumentException("campos insuficientes");
        Report report = new Report(Integer.parseInt(values.get(0)), UUID.fromString(values.get(1)), values.get(2),
                values.get(3), values.get(4), values.get(5), Long.parseLong(values.get(6)));
        report.setStatus(Report.Status.valueOf(values.get(7)));
        if (values.size() > 8 && !"-".equals(values.get(8))) report.setAssigned(UUID.fromString(values.get(8)), values.size() > 9 ? values.get(9) : "Desconocido");
        if (values.size() > 10) report.setClosedAt(Long.parseLong(values.get(10)));
        if (values.size() > 11) report.setTargetUuid(optionalUuid(values.get(11)));
        if (values.size() > 15 && !"-".equals(values.get(12))) report.setReporterLocation(values.get(12), Integer.parseInt(values.get(13)), Integer.parseInt(values.get(14)), Integer.parseInt(values.get(15)));
        if (values.size() > 19 && !"-".equals(values.get(16))) report.setTargetLocation(values.get(16), Integer.parseInt(values.get(17)), Integer.parseInt(values.get(18)), Integer.parseInt(values.get(19)));
        return report;
    }

    private List<String> splitEscaped(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (char character : value.toCharArray()) {
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '|') {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) current.append('\\');
        result.add(current.toString());
        return result;
    }

    private UUID optionalUuid(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        return UUID.fromString(value);
    }

    private void loadLocation(ConfigurationSection section, Consumer<LocationData> setter) {
        if (section == null) return;
        setter.accept(new LocationData(section.getString("world"), section.getInt("x"), section.getInt("y"), section.getInt("z")));
    }

    private void readTimedSection(YamlConfiguration conf, String path, java.util.Map<UUID, Long> target) {
        ConfigurationSection section = conf.getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                target.put(UUID.fromString(key), section.getLong(key));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Cooldown inválido ignorado en " + path + ": " + key);
            }
        }
    }

    private void quarantine(List<String> invalid) {
        if (invalid.isEmpty()) return;
        File file = new File(plugin.getDataFolder(), "corrupt-reports-" + System.currentTimeMillis() + ".txt");
        try {
            Files.write(file.toPath(), invalid, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo guardar la cuarentena de reportes: " + exception.getMessage());
        }
    }

    private void writeAtomically(YamlConfiguration conf, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("No se pudo crear la carpeta de datos");
        Path temp = file.toPath().resolveSibling(file.getName() + ".tmp");
        Files.writeString(temp, conf.saveToString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
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

    private List<Report.Event> readHistory(ConfigurationSection section) {
        List<Report.Event> history = new ArrayList<>();
        ConfigurationSection historySection = section.getConfigurationSection("action-history");
        if (historySection == null) return history;
        for (String key : historySection.getKeys(false)) {
            ConfigurationSection event = historySection.getConfigurationSection(key);
            if (event == null) continue;
            history.add(new Report.Event(event.getString("type", "UNKNOWN"), optionalUuid(event.getString("actor")),
                    event.getString("actor-name", "Sistema"), event.getLong("timestamp"), event.getString("detail", "")));
        }
        return history;
    }

    private record LocationData(String world, int x, int y, int z) {
    }
}
