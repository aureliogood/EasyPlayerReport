package com.easyplayerreport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ReportExporter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneOffset.UTC);

    private final EasyPlayerReport plugin;

    public ReportExporter(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    public Path export(String format) throws IOException {
        String normalized = format.toLowerCase();
        if (!normalized.equals("json") && !normalized.equals("csv")) throw new IllegalArgumentException("unsupported-format");
        Path directory = plugin.getDataFolder().toPath().resolve("exports");
        Files.createDirectories(directory);
        String base = "reports_" + FILE_TIME.format(Instant.now());
        byte[] payload = normalized.equals("json") ? json(plugin.getReportManager().getAll())
                : csv(plugin.getReportManager().getAll());
        for (int suffix = 0; suffix < 10000; suffix++) {
            String filename = base + (suffix == 0 ? "" : "_" + suffix) + "." + normalized;
            Path target = directory.resolve(filename);
            try {
                Files.write(target, payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return target;
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        throw new IOException("No se pudo encontrar un nombre de exportación libre");
    }

    /**
     * El CSV lleva BOM UTF-8 para que Excel lo abra con los acentos y emojis
     * correctos; sin el BOM, Excel en Windows asume la codificacion del
     * sistema y el texto sale ilegible.
     */
    private byte[] csv(List<Report> reports) {
        String body = csvBody(reports);
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[text.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(text, 0, out, 3, text.length);
        return out;
    }

    private byte[] json(List<Report> reports) {
        return jsonBody(reports).getBytes(StandardCharsets.UTF_8);
    }

    private String jsonBody(List<Report> reports) {
        StringBuilder out = new StringBuilder("[\n");
        for (int i = 0; i < reports.size(); i++) {
            Report report = reports.get(i);
            out.append("  {");
            field(out, "id", Integer.toString(report.getId())).append(',');
            field(out, "reporterUuid", report.getReporter().toString()).append(',');
            field(out, "reporterName", report.getReporterName()).append(',');
            field(out, "targetUuid", report.getTargetUuid() == null ? null : report.getTargetUuid().toString()).append(',');
            field(out, "targetName", report.getTarget()).append(',');
            field(out, "reason", report.getReason()).append(',');
            field(out, "description", report.getDescription()).append(',');
            field(out, "status", report.getStatus().name()).append(',');
            field(out, "createdAt", Instant.ofEpochMilli(report.getTimestamp()).toString()).append(',');
            field(out, "closedAt", report.getClosedAt() == 0 ? null : Instant.ofEpochMilli(report.getClosedAt()).toString()).append(',');
            field(out, "expiresAt", report.getExpiresAt() == 0 ? null : Instant.ofEpochMilli(report.getExpiresAt()).toString()).append(',');
            field(out, "assignedStaffUuid", report.getAssignedStaff() == null ? null : report.getAssignedStaff().toString()).append(',');
            field(out, "assignedStaffName", report.getAssignedStaffName()).append(',');
            field(out, "staffNote", report.getStaffNote()).append(',');
            out.append("\"actionHistory\": [");
            List<Report.Event> events = report.getActionHistory();
            for (int j = 0; j < events.size(); j++) {
                Report.Event event = events.get(j);
                if (j > 0) out.append(',');
                out.append('{');
                field(out, "type", event.getType()).append(',');
                field(out, "actorUuid", event.getActor() == null ? null : event.getActor().toString()).append(',');
                field(out, "actorName", event.getActorName()).append(',');
                field(out, "timestamp", Instant.ofEpochMilli(event.getTimestamp()).toString()).append(',');
                fieldLast(out, "detail", event.getDetail());
                out.append('}');
            }
            out.append("]}");
            if (i + 1 < reports.size()) out.append(',');
            out.append('\n');
        }
        return out.append("]\n").toString();
    }

    /**
     * Campo con coma detrás. La coma la pone siempre quien escribe el campo,
     * no el helper: si la pusiera aquí, cada llamada añadiría una segunda y el
     * JSON exportado quedaría con "clave":valor,, y no se podría leer.
     */
    private StringBuilder field(StringBuilder out, String name, String value) {
        return jsonString(out, name, value);
    }

    private StringBuilder fieldLast(StringBuilder out, String name, String value) {
        return jsonString(out, name, value);
    }

    private StringBuilder jsonString(StringBuilder out, String name, String value) {
        out.append('"').append(escapeJson(name)).append("\":");
        if (value == null) return out.append("null");
        return out.append('"').append(escapeJson(value)).append('"');
    }

    private String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        return out.toString();
    }

    private String csvBody(List<Report> reports) {
        StringBuilder out = new StringBuilder();
        List<String> headers = List.of("id", "reporter_uuid", "reporter_name", "target_uuid", "target_name", "reason",
                "description", "status", "created_at", "closed_at", "expires_at", "assigned_staff_uuid",
                "assigned_staff_name", "staff_note", "action_history");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) out.append(',');
            out.append(csv(headers.get(i)));
        }
        out.append("\r\n");
        for (Report report : reports) {
            StringBuilder events = new StringBuilder();
            for (Report.Event event : report.getActionHistory()) {
                if (events.length() > 0) events.append(" | ");
                events.append(event.getType()).append(';').append(event.getActorName()).append(';')
                        .append(Instant.ofEpochMilli(event.getTimestamp())).append(';').append(event.getDetail());
            }
            List<String> values = List.of(nullToEmpty(Integer.toString(report.getId())), report.getReporter().toString(),
                    report.getReporterName(), report.getTargetUuid() == null ? "" : report.getTargetUuid().toString(),
                    report.getTarget(), report.getReason(), report.getDescription(), report.getStatus().name(),
            Instant.ofEpochMilli(report.getTimestamp()).toString(),                    report.getClosedAt() == 0 ? "" : Instant.ofEpochMilli(report.getClosedAt()).toString(),
                    report.getExpiresAt() == 0 ? "" : Instant.ofEpochMilli(report.getExpiresAt()).toString(),
                    report.getAssignedStaff() == null ? "" : report.getAssignedStaff().toString(),
                    nullToEmpty(report.getAssignedStaffName()), report.getStaffNote(), events.toString());
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) out.append(',');
                out.append(csv(values.get(i)));
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    private String csv(String value) {
        String safe = nullToEmpty(value);
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
