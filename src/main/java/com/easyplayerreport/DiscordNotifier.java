package com.easyplayerreport;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Traduce los cambios de un reporte a los embeds que se mandan a Discord.
 *
 * Cada metodo es independiente y comprueba su propio interruptor de
 * notify-on, asi que el cliente HTTP no necesita saber nada de reportes.
 */
public class DiscordNotifier {
    private final EasyPlayerReport plugin;
    private final DiscordConfig config;
    private final DiscordClient client;

    private static final DateTimeFormatter HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    public DiscordNotifier(EasyPlayerReport plugin, DiscordConfig config, DiscordClient client) {
        this.plugin = plugin;
        this.config = config;
        this.client = client;
    }

    public boolean isReady() {
        return config.isReady();
    }

    public DiscordClient getClient() {
        return client;
    }

    // ------------------------------------------------------------ eventos

    /** Reporte nuevo. Es el aviso importante: por eso es el unico que pinguea. */
    public void notifyCreated(Report report) {
        if (!should("created")) return;

        List<DiscordClient.Field> fields = new ArrayList<>();
        fields.add(new DiscordClient.Field("Reportado por", safe(report.getReporterName()), true));
        fields.add(new DiscordClient.Field("Reportado a", safe(report.getTarget()), true));
        fields.add(new DiscordClient.Field("Estado", plugin.getLang().plainStatusLabel(report.getStatus()), true));
        fields.add(new DiscordClient.Field("Motivo", safe(report.getReason()), false));
        addLocation(fields, report);

        client.sendEmbed(
                "Nuevo reporte #" + report.getId(),
                description(report),
                config.color(),
                fields,
                "ID " + report.getId() + " · " + HORA.format(Instant.ofEpochMilli(report.getTimestamp())),
                client.buildMention());
    }

    public void notifyTaken(Report report, String staffName) {
        if (!should("taken")) return;
        single("Reporte #" + report.getId() + " tomado",
                "**" + safe(staffName) + "** se ha hecho cargo del reporte de **" + safe(report.getTarget()) + "**.",
                config.color(), new DiscordClient.Field("Pendientes", String.valueOf(plugin.getReportManager().getActive().size()), true));
    }

    public void notifyClosed(Report report, String actorName) {
        if (!should("closed")) return;
        long minutes = Duration.between(Instant.ofEpochMilli(report.getTimestamp()),
                Instant.ofEpochMilli(System.currentTimeMillis())).toMinutes();
        String duration = minutes < 60 ? minutes + " min" : (minutes / 60) + " h " + (minutes % 60) + " min";

        List<DiscordClient.Field> fields = new ArrayList<>();
        fields.add(new DiscordClient.Field("Cerrado por", safe(actorName), true));
        fields.add(new DiscordClient.Field("Reportado a", safe(report.getTarget()), true));
        fields.add(new DiscordClient.Field("Abierto durante", duration, true));
        if (!safe(report.getStaffNote()).isBlank()) {
            fields.add(new DiscordClient.Field("Nota del staff", report.getStaffNote(), false));
        }
        client.sendEmbed("Reporte #" + report.getId() + " cerrado", null, "#22c55e", fields, null, null);
    }

    public void notifyReopened(Report report, String actorName) {
        if (!should("reopened")) return;
        List<DiscordClient.Field> fields = new ArrayList<>();
        fields.add(new DiscordClient.Field("Reabierto por", safe(actorName), true));
        fields.add(new DiscordClient.Field("Reportado a", safe(report.getTarget()), true));
        client.sendEmbed("Reporte #" + report.getId() + " reabierto",
                "Vuelve a estar activo y necesita revision.", "#f97316", fields, null, null);
    }

    public void notifyAppeal(Report report) {
        if (!should("appeal")) return;
        String author = report.getAppealAuthorName() != null
                ? report.getAppealAuthorName() : "el reportado";
        List<DiscordClient.Field> fields = new ArrayList<>();
        fields.add(new DiscordClient.Field("Apela", safe(author), true));
        fields.add(new DiscordClient.Field("Reporte original", "#" + report.getId(), true));
        client.sendEmbed("Apelacion del reporte #" + report.getId(),
                appealText(report), "#a855f7", fields,
                "El staff debe revisarla antes de cerrar.", null);
    }

    public void notifyModeration(Report report, String actorName, String action) {
        if (!should("moderation")) return;
        List<DiscordClient.Field> fields = new ArrayList<>();
        fields.add(new DiscordClient.Field("Accion", safe(action), true));
        fields.add(new DiscordClient.Field("Ejecutada por", safe(actorName), true));
        fields.add(new DiscordClient.Field("Sobre", safe(report.getTarget()), true));
        client.sendEmbed("Moderacion desde el reporte #" + report.getId(), null, "#f43f5e", fields, null, null);
    }

    public void notifyFalseReport(Report report, String actorName) {
        if (!should("false-report")) return;
        List<DiscordClient.Field> fields = new ArrayList<>();
        fields.add(new DiscordClient.Field("Marcado por", safe(actorName), true));
        fields.add(new DiscordClient.Field("Reportado por", safe(report.getReporterName()), true));
        fields.add(new DiscordClient.Field("Reportado a", safe(report.getTarget()), true));
        client.sendEmbed("Reporte #" + report.getId() + " marcado como falso",
                "El reportador queda bloqueado temporalmente para enviar nuevos reportes.", "#ef4444", fields, null, null);
    }

    // ------------------------------------------------------------ ayudas

    private boolean should(String event) {
        return config.isReady() && config.shouldNotify(event);
    }

    /** Texto que escribio el jugador, si la config lo permite. */
    private String description(Report report) {
        if (!config.includeDescription()) return null;
        String text = safe(report.getDescription());
        if (text.isBlank()) return null;
        int max = config.maxDescriptionLength();
        if (max <= 0 || text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }

    private String appealText(Report report) {
        String text = safe(report.getAppealText());
        if (text.isBlank()) return null;
        int max = Math.min(config.maxDescriptionLength(), 1500);
        if (max <= 0) return null;
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private void addLocation(List<DiscordClient.Field> fields, Report report) {
        if (!config.includeCoordinates()) return;
        if (report.hasReporterLocation()) {
            fields.add(new DiscordClient.Field("Lugar", format(report.getReporterWorld(),
                    report.getReporterX(), report.getReporterY(), report.getReporterZ()), false));
        } else {
            fields.add(new DiscordClient.Field("Lugar", "sin datos", false));
        }
    }

    private void single(String title, String description, String color, DiscordClient.Field field) {
        client.sendEmbed(title, description, color, List.of(field), null, null);
    }

    private String format(String world, int x, int y, int z) {
        return safe(world) + " · " + x + ", " + y + ", " + z;
    }

    /**
     * safe() es el unico punto por el que pasa todo el texto que sale a Discord.
     * Ahi se limpian los codigos de color de Minecraft, que en Discord salian
     * escritos tal cual: "&aNormal" en vez de "Normal", "&&Abierto" en vez de
     * "Abierto". Tambien los limpia si los escribe un jugador en su reporte.
     */
    private String safe(String value) {
        return DiscordClient.plain(value);
    }
}
