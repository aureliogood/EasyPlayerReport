package com.easyplayerreport;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PlayerHistoryDialog {
    private static final TextColor ACCENT = TextColor.color(0x1DB954);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EasyPlayerReport plugin;

    public PlayerHistoryDialog(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String query) {
        if (!player.hasPermission("easyplayerreport.history")) return;
        List<Report> reports = find(query);
        String titleQuery = query == null ? "" : query;
        if (reports.isEmpty()) {
            openEmpty(player, titleQuery);
            return;
        }
        openPage(player, titleQuery, reports, 0);
    }

    private void openPage(Player player, String query, List<Report> reports, int requestedPage) {
        if (!player.hasPermission("easyplayerreport.history")) return;
        int maxPage = (reports.size() - 1) / plugin.pageSize();
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Menus menus = plugin.getMenus();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(menus.comp("history-menu.hint", "Reportes de %player% · %count%",
                NamedTextColor.GRAY, "%player%", query, "%count%", String.valueOf(reports.size()))));
        body.add(DialogBody.plainMessage(menus.comp("history-menu.page", "Página %page%/%pages%",
                NamedTextColor.DARK_GRAY, "%page%", String.valueOf(page + 1), "%pages%", String.valueOf(maxPage + 1))));
        int start = page * plugin.pageSize();
        for (int i = start; i < Math.min(start + plugin.pageSize(), reports.size()); i++) {
            Report report = reports.get(i);
            body.add(DialogBody.plainMessage(menus.comp("history-menu.report", "Reporte #%id%",
                    report.getStatus() == Report.Status.CLOSED ? NamedTextColor.GREEN : NamedTextColor.AQUA,
                    "%id%", String.valueOf(report.getId()))));
            body.add(DialogBody.plainMessage(menus.comp("history-menu.details",
                    "Estado: %status% · Motivo: %reason% · Fecha: %date%",
                    NamedTextColor.GRAY, "%status%", plugin.getLang().plainStatusLabel(report.getStatus()),
                    "%reason%", report.getReason(), "%date%", formatDate(report.getTimestamp()))));
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (page > 0) buttons.add(button("history-menu.previous", "Anterior", page - 1, query, reports));
        if (page < maxPage) buttons.add(button("history-menu.next", "Siguiente", page + 1, query, reports));
        buttons.add(ActionButton.create(Component.text("❌ ").append(menus.comp("history-menu.close", "Cerrar", NamedTextColor.RED)), null, 120, null));
        DialogBase base = DialogBase.builder(menus.comp("history-menu.title", "Historial de %player%", ACCENT,
                        "%player%", query))
                .body(body).canCloseWithEscape(true).build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.multiAction(buttons, null, Math.max(2, Math.min(buttons.size(), 3))))));
    }

    private void openEmpty(Player player, String query) {
        Menus menus = plugin.getMenus();
        DialogBase base = DialogBase.builder(menus.comp("history-menu.title", "Historial de %player%", ACCENT,
                        "%player%", query))
                .body(List.of(DialogBody.plainMessage(menus.comp("history-menu.empty", "No hay reportes para este jugador.",
                        NamedTextColor.GRAY))))
                .canCloseWithEscape(true).build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.notice(ActionButton.create(Component.text("❌ ").append(menus.comp("history-menu.close", "Cerrar", NamedTextColor.RED)), null, 120, null)))));
    }

    private ActionButton button(String key, String defaultText, int page, String query, List<Report> reports) {
        return ActionButton.create(plugin.getMenus().comp(key, defaultText, NamedTextColor.YELLOW), null, 180,
                DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) openPage(player, query, reports, page);
                }, ClickCallback.Options.builder().build()));
    }

    private List<Report> find(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.trim();
        UUID uuid = null;
        try {
            uuid = UUID.fromString(normalized);
        } catch (IllegalArgumentException ignored) {
        }
        String name = normalized.toLowerCase(Locale.ROOT);
        List<Report> found = new ArrayList<>();
        for (Report report : plugin.getReportManager().getAll()) {
            boolean reporterMatches = (uuid != null && report.getReporter().equals(uuid))
                    || report.getReporterName().toLowerCase(Locale.ROOT).equals(name);
            boolean targetMatches = (uuid != null && uuid.equals(report.getTargetUuid()))
                    || report.getTarget().toLowerCase(Locale.ROOT).equals(name);
            if (reporterMatches || targetMatches) found.add(report);
        }
        return found;
    }

    private String formatDate(long timestamp) {
        return DATE.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
    }
}
