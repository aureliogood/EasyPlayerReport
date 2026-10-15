package com.easyplayerreport;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ReportStatsDialog {
    private static final TextColor ACCENT = TextColor.color(0x1DB954);

    private final EasyPlayerReport plugin;

    public ReportStatsDialog(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("easyplayerreport.stats")) return;
        List<Report> reports = plugin.getReportManager().getAll();
        int active = (int) reports.stream().filter(Report::isActive).count();
        int closed = reports.size() - active;
        Map<Report.Status, Integer> states = new EnumMap<>(Report.Status.class);
        for (Report.Status status : Report.Status.values()) states.put(status, 0);
        Map<String, Integer> reasons = new TreeMap<>();
        long sevenDaysAgo = Instant.now().minus(Duration.ofDays(7)).toEpochMilli();
        int recent = 0;
        for (Report report : reports) {
            states.merge(report.getStatus(), 1, Integer::sum);
            reasons.merge(report.getReason(), 1, Integer::sum);
            if (report.getTimestamp() >= sevenDaysAgo) recent++;
        }
        Menus menus = plugin.getMenus();
        List<DialogBody> body = new ArrayList<>();
        body.add(line(menus, "stats-menu.total", "Total: %count%", reports.size()));
        body.add(line(menus, "stats-menu.active", "Activos: %count%", active));
        body.add(line(menus, "stats-menu.closed", "Cerrados: %count%", closed));
        body.add(line(menus, "stats-menu.recent", "Últimos 7 días: %count%", recent));
        body.add(title(menus, "stats-menu.states-title", "Por estado:"));
        states.forEach((status, count) -> body.add(line(menus, "stats-menu.state", "· %state%: %count%", "state",
                plugin.getLang().plainStatusLabel(status), count)));
        body.add(title(menus, "stats-menu.reasons-title", "Por motivo:"));
        reasons.entrySet().stream().sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> body.add(line(menus, "stats-menu.reason", "· %reason%: %count%", "reason", entry.getKey(), entry.getValue())));
        DialogBase base = DialogBase.builder(menus.comp("stats-menu.title", "Estadísticas de reportes", ACCENT))
                .body(body).canCloseWithEscape(true).build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.notice(ActionButton.create(net.kyori.adventure.text.Component.text("❌ ").append(menus.comp("stats-menu.close", "Cerrar", NamedTextColor.RED)), null, 120, null)))));
    }

    private DialogBody title(Menus menus, String key, String defaultText) {
        return DialogBody.plainMessage(menus.comp(key, defaultText, NamedTextColor.WHITE));
    }

    private DialogBody line(Menus menus, String key, String defaultText, int count) {
        return DialogBody.plainMessage(menus.comp(key, defaultText, NamedTextColor.GRAY,
                "%count%", String.valueOf(count)));
    }

    private DialogBody line(Menus menus, String key, String defaultText, String firstKey, String firstValue, int count) {
        return DialogBody.plainMessage(menus.comp(key, defaultText, NamedTextColor.GRAY,
                "%count%", String.valueOf(count), "%" + firstKey + "%", firstValue));
    }
}
