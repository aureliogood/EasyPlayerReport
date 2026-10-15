package com.easyplayerreport;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SqlStore implements ReportStore {
    private final EasyPlayerReport plugin;
    private final String type;
    private final File sqliteFile;
    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private Connection cached;

    public SqlStore(EasyPlayerReport plugin, String type) {
        this.plugin = plugin;
        this.type = type;
        this.sqliteFile = resolveSqliteFile(plugin, plugin.getConfig().getString("database.sqlite.file", "reports.db"));
        this.host = plugin.getConfig().getString("database.mysql.host", "localhost");
        this.port = plugin.getConfig().getInt("database.mysql.port", 3306);
        this.database = plugin.getConfig().getString("database.mysql.database", "odisseyreports");
        this.user = plugin.getConfig().getString("database.mysql.user", "root");
        this.password = plugin.getConfig().getString("database.mysql.password", "");
    }

    @Override
    public void initialize() throws SQLException {
        if ("sqlite".equals(type)) {
            File parent = sqliteFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new SQLException("No se pudo crear la carpeta SQLite");
        }
        try {
            Class.forName("sqlite".equals(type) ? "org.sqlite.JDBC" : "com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("Driver JDBC no disponible", exception);
        }
        try {
            Connection connection = open();
            try (Statement statement = connection.createStatement()) {
                for (String table : tables()) statement.executeUpdate(table);
            }
            ensureReportAppealColumns(connection);
        } catch (SQLException exception) {
            invalidate();
            throw new SQLException("No se pudo inicializar la base de datos", exception);
        }
    }

    @Override
    public DatabaseData load() throws SQLException {
        DatabaseData data = new DatabaseData();
        try {
            Connection connection = open();
            data.setNextId(getMetaInt(connection, "next-id", 1));
            loadReports(connection, data);
            loadChat(connection, data);
            loadEvents(connection, data);
            loadCooldowns(connection, data);
            loadAppealCooldowns(connection, data);
            loadFalseReportBlocks(connection, data);
            loadDeletedEvents(connection, data);
            return data;
        } catch (SQLException exception) {
            throw new SQLException("No se pudo leer la base de datos", exception);
        }
    }

    @Override
    public void save(DatabaseData data) throws SQLException {
        try {
            Connection connection = open();
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                clear(connection);
                insertReports(connection, data);
                insertChat(connection, data);
                insertEvents(connection, data);
                insertCooldowns(connection, data);
                insertAppealCooldowns(connection, data);
                insertFalseReportBlocks(connection, data);
                insertDeletedEvents(connection, data);
                setMeta(connection, "next-id", Integer.toString(data.getNextId()));
                setMeta(connection, "yaml-migrated", "true");
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            invalidate();
            throw new SQLException("No se pudo guardar la base de datos", exception);
        }
    }

    @Override
    public boolean isMigrated() throws SQLException {
        try {
            Connection connection = open();
            try (PreparedStatement statement = connection.prepareStatement("SELECT meta_value FROM report_metadata WHERE meta_key = ?")) {
                statement.setString(1, "yaml-migrated");
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && "true".equalsIgnoreCase(result.getString(1));
                }
            }
        } catch (SQLException exception) {
            invalidate();
            throw new SQLException("No se pudo comprobar la migración", exception);
        }
    }

    @Override
    public void markMigrated() throws SQLException {
        try {
            setMeta(open(), "yaml-migrated", "true");
        } catch (SQLException exception) {
            invalidate();
            throw new SQLException("No se pudo marcar la migración", exception);
        }
    }

    @Override
    public void close() {
        invalidate();
    }

    /**
     * Devuelve la conexión cacheada del plugin. Antes se abría una nueva en cada
     * llamada (5-6 por guardado), lo que en MySQL suponia un handshake completo
     * por cada transaccion. La conexion se reutiliza y solo se renueva si el
     * driver reporta que quedo invalida.
     */
    private Connection open() throws SQLException {
        if (cached != null) {
            try {
                if (cached.isValid(2)) return cached;
            } catch (SQLException exception) {
                // se descarta y se reconecta abajo
            }
            invalidate();
        }
        if ("sqlite".equals(type)) {
            cached = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
        } else {
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&serverTimezone=UTC";
            cached = DriverManager.getConnection(url, user, password);
        }
        return cached;
    }

    private void invalidate() {
        if (cached == null) return;
        try {
            cached.close();
        } catch (SQLException ignored) {
        }
        cached = null;
    }

    private void loadReports(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, reporter, reporter_name, target, target_uuid, reason, description, timestamp, expires_at, closed_at, status, assigned_staff, assigned_staff_name, staff_note, appeal_text, appeal_author, appeal_author_name, appeal_at, reporter_world, reporter_x, reporter_y, reporter_z, target_world, target_x, target_y, target_z, discord_channel_id FROM reports")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Report report = new Report(result.getInt("id"), UUID.fromString(result.getString("reporter")),
                            result.getString("reporter_name"), result.getString("target"), result.getString("reason"),
                            result.getString("description"), result.getLong("timestamp"));
                    report.setTargetUuid(optionalUuid(result.getString("target_uuid")));
                    report.setExpiresAt(result.getLong("expires_at"));
                    report.setClosedAt(result.getLong("closed_at"));
                    Report.Status storedStatus = status(result.getString("status"));
                    // El estado se aplica DESPUES de asignar el staff: setAssigned
                    // pone el estado en TAKEN, asi que leerlo antes reabria como
                    // "en proceso" todos los reportes cerrados que tengan staff.
                    UUID assigned = optionalUuid(result.getString("assigned_staff"));
                    if (assigned != null) report.setAssigned(assigned, result.getString("assigned_staff_name"));
                    report.setStatus(storedStatus);
                    if (storedStatus == Report.Status.OPEN) report.release();
                    report.setStaffNote(result.getString("staff_note"));
                    report.setAppeal(result.getString("appeal_text"), optionalUuid(result.getString("appeal_author")),
                            result.getString("appeal_author_name"), result.getLong("appeal_at"));
                    report.setReporterLocation(result.getString("reporter_world"), result.getInt("reporter_x"), result.getInt("reporter_y"), result.getInt("reporter_z"));
                    report.setTargetLocation(result.getString("target_world"), result.getInt("target_x"), result.getInt("target_y"), result.getInt("target_z"));
                    report.setDiscordChannelId(result.getString("discord_channel_id"));
                    data.getReports().add(report);
                    if (report.getId() >= data.getNextId()) data.setNextId(report.getId() + 1);
                }
            }
        }
    }

    private void loadChat(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT report_id, message FROM report_chat ORDER BY report_id, line_no")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) data.getChatLog().computeIfAbsent(result.getInt("report_id"), ignored -> new java.util.ArrayList<>()).add(result.getString("message"));
            }
        }
    }

    private void loadEvents(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT report_id, event_type, actor, actor_name, timestamp, detail FROM report_events ORDER BY report_id, event_index")) {
            try (ResultSet result = statement.executeQuery()) {
                Map<Integer, Report> reports = new java.util.HashMap<>();
                for (Report report : data.getReports()) reports.put(report.getId(), report);
                while (result.next()) {
                    Report report = reports.get(result.getInt("report_id"));
                    if (report != null) report.addLoadedEvents(List.of(new Report.Event(result.getString("event_type"), optionalUuid(result.getString("actor")), result.getString("actor_name"), result.getLong("timestamp"), result.getString("detail"))));
                }
            }
        }
    }

    private void loadCooldowns(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, expires_at FROM report_cooldowns")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) data.getCooldowns().put(UUID.fromString(result.getString("player_uuid")), result.getLong("expires_at"));
            }
        }
    }

    private void loadAppealCooldowns(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, expires_at FROM report_appeal_cooldowns")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) data.getAppealCooldowns().put(UUID.fromString(result.getString("player_uuid")), result.getLong("expires_at"));
            }
        }
    }

    private void loadFalseReportBlocks(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, expires_at FROM report_false_blocks")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) data.getFalseReportBlocks().put(UUID.fromString(result.getString("player_uuid")), result.getLong("expires_at"));
            }
        }
    }

    private void loadDeletedEvents(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT report_id, event_type, actor, actor_name, timestamp, detail FROM deleted_report_events ORDER BY report_id, event_index")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) data.getDeletedEvents().computeIfAbsent(result.getInt("report_id"), ignored -> new java.util.ArrayList<>()).add(new Report.Event(result.getString("event_type"), optionalUuid(result.getString("actor")), result.getString("actor_name"), result.getLong("timestamp"), result.getString("detail")));
            }
        }
    }

    private void clear(Connection connection) throws SQLException {
        for (String sql : List.of("DELETE FROM report_chat", "DELETE FROM report_events", "DELETE FROM report_cooldowns", "DELETE FROM report_appeal_cooldowns", "DELETE FROM report_false_blocks", "DELETE FROM deleted_report_events", "DELETE FROM reports")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }
    }

    private void insertReports(Connection connection, DatabaseData data) throws SQLException {
        // El orden de los SET tiene que ser el mismo que el de las columnas del
        // INSERT. Antes se saltaba el 14 y a partir del 15 todo iba corrido una
        // posicion, asi que con MySQL o SQLite el guardado fallaba y el plugin
        // caia al YAML.
        String sql = "INSERT INTO reports (id, reporter, reporter_name, target, target_uuid, reason, description, timestamp, expires_at, closed_at, status, assigned_staff, assigned_staff_name, staff_note, appeal_text, appeal_author, appeal_author_name, appeal_at, reporter_world, reporter_x, reporter_y, reporter_z, target_world, target_x, target_y, target_z, discord_channel_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Report report : data.getReports()) {
                statement.setInt(1, report.getId());
                statement.setString(2, report.getReporter().toString());
                statement.setString(3, report.getReporterName());
                statement.setString(4, report.getTarget());
                setNullableString(statement, 5, report.getTargetUuid());
                statement.setString(6, report.getReason());
                statement.setString(7, report.getDescription());
                statement.setLong(8, report.getTimestamp());
                statement.setLong(9, report.getExpiresAt());
                statement.setLong(10, report.getClosedAt());
                statement.setString(11, report.getStatus().name());
                setNullableString(statement, 12, report.getAssignedStaff());
                statement.setString(13, report.getAssignedStaffName());
                statement.setString(14, report.getStaffNote());
                statement.setString(15, report.getAppealText());
                setNullableUuid(statement, 16, report.getAppealAuthor());
                statement.setString(17, report.getAppealAuthorName());
                statement.setLong(18, report.getAppealAt());
                statement.setString(19, report.getReporterWorld());
                statement.setInt(20, report.getReporterX());
                statement.setInt(21, report.getReporterY());
                statement.setInt(22, report.getReporterZ());
                statement.setString(23, report.getTargetWorld());
                statement.setInt(24, report.getTargetX());
                statement.setInt(25, report.getTargetY());
                statement.setInt(26, report.getTargetZ());
                setNullableString(statement, 27, report.getDiscordChannelId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertChat(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO report_chat (report_id, line_no, message) VALUES (?, ?, ?)")) {
            for (Map.Entry<Integer, List<String>> entry : data.getChatLog().entrySet()) {
                for (int i = 0; i < entry.getValue().size(); i++) {
                    statement.setInt(1, entry.getKey());
                    statement.setInt(2, i);
                    statement.setString(3, entry.getValue().get(i));
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private void insertEvents(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO report_events (report_id, event_index, event_type, actor, actor_name, timestamp, detail) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (Report report : data.getReports()) insertEvents(statement, report.getId(), report.getActionHistory());
            statement.executeBatch();
        }
    }

    private void insertDeletedEvents(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO deleted_report_events (report_id, event_index, event_type, actor, actor_name, timestamp, detail) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (Map.Entry<Integer, List<Report.Event>> entry : data.getDeletedEvents().entrySet()) insertEvents(statement, entry.getKey(), entry.getValue());
            statement.executeBatch();
        }
    }

    private void insertEvents(PreparedStatement statement, int reportId, List<Report.Event> events) throws SQLException {
        for (int i = 0; i < events.size(); i++) {
            Report.Event event = events.get(i);
            statement.setInt(1, reportId);
            statement.setInt(2, i);
            statement.setString(3, event.getType());
            setNullableUuid(statement, 4, event.getActor());
            statement.setString(5, event.getActorName());
            statement.setLong(6, event.getTimestamp());
            statement.setString(7, event.getDetail());
            statement.addBatch();
        }
    }

    private void insertCooldowns(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO report_cooldowns (player_uuid, expires_at) VALUES (?, ?)")) {
            for (Map.Entry<UUID, Long> entry : data.getCooldowns().entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setLong(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertAppealCooldowns(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO report_appeal_cooldowns (player_uuid, expires_at) VALUES (?, ?)")) {
            for (Map.Entry<UUID, Long> entry : data.getAppealCooldowns().entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setLong(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertFalseReportBlocks(Connection connection, DatabaseData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO report_false_blocks (player_uuid, expires_at) VALUES (?, ?)")) {
            for (Map.Entry<UUID, Long> entry : data.getFalseReportBlocks().entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setLong(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private int getMetaInt(Connection connection, String key, int fallback) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT meta_value FROM report_metadata WHERE meta_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return fallback;
                // Un next-id corrupto no puede tumbar la carga: se recalcula
                // despues con el id mayor de los reportes leidos.
                try {
                    return Integer.parseInt(result.getString(1));
                } catch (NumberFormatException exception) {
                    return fallback;
                }
            }
        }
    }

    private void setMeta(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE report_metadata SET meta_value = ? WHERE meta_key = ?")) {
            update.setString(1, value);
            update.setString(2, key);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO report_metadata (meta_key, meta_value) VALUES (?, ?)")) {
                    insert.setString(1, key);
                    insert.setString(2, value);
                    insert.executeUpdate();
                }
            }
        }
    }

    private void setNullableString(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value.toString());
    }

    private void setNullableUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value.toString());
    }

    private UUID optionalUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private Report.Status status(String value) {
        try {
            return Report.Status.valueOf(value);
        } catch (Exception exception) {
            return Report.Status.OPEN;
        }
    }

    private void ensureReportAppealColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "appeal_text", "TEXT");
        addColumnIfMissing(connection, "appeal_author", "VARCHAR(36)");
        addColumnIfMissing(connection, "appeal_author_name", "VARCHAR(255)");
        addColumnIfMissing(connection, "appeal_at", "BIGINT");
        addColumnIfMissing(connection, "discord_channel_id", "VARCHAR(32)");
    }

    private void addColumnIfMissing(Connection connection, String column, String definition) throws SQLException {
        boolean present = false;
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "reports", null)) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE reports ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private List<String> tables() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY, reporter VARCHAR(36) NOT NULL, reporter_name VARCHAR(255) NOT NULL, target VARCHAR(255) NOT NULL, target_uuid VARCHAR(36), reason VARCHAR(255) NOT NULL, description TEXT NOT NULL, timestamp BIGINT NOT NULL, expires_at BIGINT NOT NULL, closed_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL, assigned_staff VARCHAR(36), assigned_staff_name VARCHAR(255), staff_note TEXT NOT NULL, appeal_text TEXT NOT NULL, appeal_author VARCHAR(36), appeal_author_name VARCHAR(255) NOT NULL, appeal_at BIGINT NOT NULL, reporter_world VARCHAR(255), reporter_x INTEGER NOT NULL, reporter_y INTEGER NOT NULL, reporter_z INTEGER NOT NULL, target_world VARCHAR(255), target_x INTEGER NOT NULL, target_y INTEGER NOT NULL, target_z INTEGER NOT NULL, discord_channel_id VARCHAR(32))",
                "CREATE TABLE IF NOT EXISTS report_chat (report_id INTEGER NOT NULL, line_no INTEGER NOT NULL, message TEXT NOT NULL, PRIMARY KEY (report_id, line_no))",
                "CREATE TABLE IF NOT EXISTS report_events (report_id INTEGER NOT NULL, event_index INTEGER NOT NULL, event_type VARCHAR(64) NOT NULL, actor VARCHAR(36), actor_name VARCHAR(255) NOT NULL, timestamp BIGINT NOT NULL, detail TEXT NOT NULL, PRIMARY KEY (report_id, event_index))",
                "CREATE TABLE IF NOT EXISTS report_cooldowns (player_uuid VARCHAR(36) PRIMARY KEY, expires_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS report_appeal_cooldowns (player_uuid VARCHAR(36) PRIMARY KEY, expires_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS report_false_blocks (player_uuid VARCHAR(36) PRIMARY KEY, expires_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS deleted_report_events (report_id INTEGER NOT NULL, event_index INTEGER NOT NULL, event_type VARCHAR(64) NOT NULL, actor VARCHAR(36), actor_name VARCHAR(255) NOT NULL, timestamp BIGINT NOT NULL, detail TEXT NOT NULL, PRIMARY KEY (report_id, event_index))",
                "CREATE TABLE IF NOT EXISTS report_metadata (meta_key VARCHAR(64) PRIMARY KEY, meta_value TEXT NOT NULL)");
    }

    private static File resolveSqliteFile(EasyPlayerReport plugin, String configured) {
        File file = new File(configured);
        return file.isAbsolute() ? file : new File(plugin.getDataFolder(), configured);
    }
}
