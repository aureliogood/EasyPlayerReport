package com.easyplayerreport;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class StaffDialog {
    private static final TextColor ACCENT = TextColor.color(0x1DB954);

    private enum Filter { OPEN, TAKEN, CLOSED, ALL }

    private final EasyPlayerReport plugin;
    private final Map<UUID, Integer> pages = new HashMap<>();
    private final Map<UUID, Filter> filters = new HashMap<>();

    public StaffDialog(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    public void open(Player staff) {
        if (!hasStaff(staff)) return;
        filters.putIfAbsent(staff.getUniqueId(), Filter.OPEN);
        pages.putIfAbsent(staff.getUniqueId(), 0);
        openList(staff, pages.get(staff.getUniqueId()), filters.get(staff.getUniqueId()));
    }

    private void openList(Player staff, int requestedPage, Filter filter) {
        if (!hasStaff(staff)) return;
        plugin.getReportManager().purgeExpired();
        Filter selected = filter == null ? Filter.OPEN : filter;
        List<Report> reports = filtered(selected);
        int maxPage = reports.isEmpty() ? 0 : (reports.size() - 1) / plugin.pageSize();
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        pages.put(staff.getUniqueId(), page);
        filters.put(staff.getUniqueId(), selected);

        List<ActionButton> buttons = new ArrayList<>();
        for (Filter value : Filter.values()) buttons.add(filterButton(staff, value, selected));
        int start = page * plugin.pageSize();
        for (int i = start; i < Math.min(reports.size(), start + plugin.pageSize()); i++) buttons.add(reportButton(staff, reports.get(i)));
        if (page > 0) buttons.add(pageButton(staff, page - 1, "staff-menu.previous", "◀ Página anterior"));
        if (page < maxPage) buttons.add(pageButton(staff, page + 1, "staff-menu.next", "▶ Página siguiente"));
        buttons.add(closeButton("Cerrar", "Cierra la lista de reportes"));

        Menus menus = plugin.getMenus();
        String hintKey = "staff-menu.hint-" + selected.name().toLowerCase();
        Component hint = menus.comp(hintKey, hint(selected), NamedTextColor.GRAY,
                "%page%", String.valueOf(page + 1), "%pages%", String.valueOf(maxPage + 1),
                "%count%", String.valueOf(reports.size()));
        DialogBase base = DialogBase.builder(menus.comp("staff-menu.title", "Reportes del staff", ACCENT)
                        .append(Component.text(" (" + (page + 1) + "/" + (maxPage + 1) + ")").color(NamedTextColor.GRAY)))
                .body(List.of(DialogBody.plainMessage(hint)))
                .canCloseWithEscape(true)
                .build();
        staff.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.multiAction(buttons, null, 2))));
    }

    /** Al salir el jugador se olvida su pagina y filtro: si no, el mapa crecia
     *  para siempre con una entrada por cada staff que se conecto alguna vez. */
    public void forget(UUID uuid) {
        if (uuid == null) return;
        pages.remove(uuid);
        filters.remove(uuid);
    }

    private List<Report> filtered(Filter filter) {
        return switch (filter) {
            case OPEN -> plugin.getReportManager().getAll().stream().filter(r -> r.getStatus() == Report.Status.OPEN).toList();
            case TAKEN -> plugin.getReportManager().getAll().stream().filter(r -> r.getStatus() == Report.Status.TAKEN).toList();
            case CLOSED -> plugin.getReportManager().getClosed();
            case ALL -> plugin.getReportManager().getAll();
        };
    }

    private String hint(Filter filter) {
        return switch (filter) {
            case OPEN -> "&7Mostrando %count% reportes abiertos.";
            case TAKEN -> "&7Mostrando %count% reportes tomados.";
            case CLOSED -> "&7Mostrando %count% reportes cerrados.";
            case ALL -> "&7Mostrando %count% reportes en total.";
        };
    }

    private ActionButton filterButton(Player staff, Filter value, Filter selected) {
        boolean active = value == selected;
        String label = plugin.getMenus().raw("staff-menu.filter-" + value.name().toLowerCase(), fallbackFilterLabel(value));
        NamedTextColor color = active ? NamedTextColor.GREEN : NamedTextColor.GRAY;
        return ActionButton.create(Menus.build(Menus.stripMarkup(label), color),
                Component.text(active ? "Filtro seleccionado" : "Cambiar filtro").color(NamedTextColor.GRAY), 180,
                DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) openList(player, 0, value);
                }, opts()));
    }

    private String fallbackFilterLabel(Filter value) {
        return switch (value) {
            case OPEN -> "📂 Abiertos";
            case TAKEN -> "🛠 En proceso";
            case CLOSED -> "✅ Cerrados";
            case ALL -> "📋 Todos";
        };
    }

    private ActionButton reportButton(Player staff, Report report) {
        String status = plain(plugin.getLang().statusLabel(report.getStatus()));
        Component label = Component.text("📋 #" + report.getId() + " " + report.getTarget() + " - " + report.getReason())
                .color(report.getStatus() == Report.Status.OPEN ? NamedTextColor.WHITE : NamedTextColor.AQUA);
            Component tooltip = Component.text("Estado: " + status).color(NamedTextColor.GRAY);
        if (report.getAssignedStaffName() != null) tooltip = tooltip.append(Component.newline()).append(Component.text("Staff: " + report.getAssignedStaffName()).color(NamedTextColor.AQUA));
        return ActionButton.create(label, tooltip, 200, DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player) openDetail(player, report.getId());
        }, opts()));
    }

    public void openDetail(Player staff, int id) {
        if (!hasStaff(staff)) return;
        Report report = plugin.getReportManager().getById(id);
        if (report == null) {
            send(staff, "messages.report-not-found");
            open(staff);
            return;
        }
        Menus menus = plugin.getMenus();
        List<DialogBody> body = new ArrayList<>();
        body.add(field(menus, "staff-menu.label-reporter", "Persona que reportó:", report.getReporterName(), NamedTextColor.GOLD));
        body.add(field(menus, "staff-menu.label-target", "Persona reportada:", report.getTarget(), NamedTextColor.GOLD));
        body.add(field(menus, "staff-menu.label-reason", "Razón:", report.getReason(), NamedTextColor.GOLD));
        body.add(DialogBody.plainMessage(menus.comp("staff-menu.label-desc", "Descripción:", NamedTextColor.WHITE)));
        body.add(DialogBody.plainMessage(report.getDescription().isBlank()
                ? menus.comp("staff-menu.label-no-desc", "(Sin descripción)", NamedTextColor.GRAY)
                : Component.text(report.getDescription()).color(NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(menus.comp("staff-menu.label-status", "Estado: ", NamedTextColor.GRAY)
                .append(Component.text(plain(plugin.getLang().statusLabel(report.getStatus()))).color(NamedTextColor.GRAY))));
            if (report.getAssignedStaffName() != null) body.add(DialogBody.plainMessage(menus.comp("staff-menu.label-assigned", "Staff asignado: ", NamedTextColor.GRAY).append(Component.text(report.getAssignedStaffName()).color(NamedTextColor.AQUA))));
        body.add(DialogBody.plainMessage(menus.comp("staff-menu.label-note", "Nota privada: ", NamedTextColor.GRAY)
                .append(Component.text(report.getStaffNote().isBlank() ? "(Sin nota)" : report.getStaffNote()).color(NamedTextColor.WHITE))));
        if (report.hasAppeal() && (staff.getUniqueId().equals(report.getAssignedStaff()) || staff.hasPermission("easyplayerreport.admin"))) {
            body.add(DialogBody.plainMessage(Component.text("📨 Apelación de " + report.getAppealAuthorName()).color(NamedTextColor.YELLOW)));
            body.add(DialogBody.plainMessage(Component.text(report.getAppealText()).color(NamedTextColor.WHITE)));
        }
        body.add(DialogBody.plainMessage(menus.comp("staff-menu.label-history", "Historial de acciones:", NamedTextColor.WHITE)));
        List<Report.Event> history = report.getActionHistory();
        int from = Math.max(0, history.size() - 5);
        if (history.isEmpty()) body.add(DialogBody.plainMessage(Component.text("(Sin eventos)").color(NamedTextColor.GRAY)));
        for (int i = from; i < history.size(); i++) {
            Report.Event event = history.get(i);
            body.add(DialogBody.plainMessage(Component.text(event.getType() + " · " + event.getActorName() + " · " + event.getDetail()).color(NamedTextColor.DARK_GRAY)));
        }
        addExpiration(body, report);

        List<ActionButton> buttons = new ArrayList<>();
        if (report.getStatus() == Report.Status.OPEN) buttons.add(action(staff, "take", "Tomar reporte", NamedTextColor.GREEN, (response, audience) -> take(audience, report.getId())));
        if (report.getStatus() == Report.Status.TAKEN) {
            buttons.add(action(staff, "release", "Liberar reporte", NamedTextColor.YELLOW, (response, audience) -> release(audience, report.getId())));
            buttons.add(action(staff, "reassign", "Reasignar a mí", NamedTextColor.AQUA, (response, audience) -> reassign(audience, report.getId())));
            buttons.add(action(staff, "chat", "Ir al chat", NamedTextColor.WHITE, (response, audience) -> {
                if (audience instanceof Player player) plugin.getReportChatDialog().open(player, report.getId());
            }));
        }
        buttons.add(action(staff, "tp-reporter", "Teleport al que reportó", NamedTextColor.WHITE, (response, audience) -> teleport(audience, report.getId(), true)));
        buttons.add(action(staff, "tp-target", "Teleport al reportado", NamedTextColor.WHITE, (response, audience) -> teleport(audience, report.getId(), false)));
        if (report.getStatus() != Report.Status.CLOSED) buttons.add(action(staff, "close-report", "Cerrar reporte", NamedTextColor.RED, (response, audience) -> confirmClose(audience, report.getId())));
        if (report.getStatus() == Report.Status.CLOSED && staff.hasPermission("easyplayerreport.reopen")) {
            buttons.add(action(staff, "reopen", "Reabrir reporte", NamedTextColor.GREEN, (response, audience) -> confirmReopen(audience, report.getId())));
        }
        buttons.add(action(staff, "note", "Añadir nota privada", NamedTextColor.YELLOW, (response, audience) -> openNote(audience, report.getId())));
        if (staff.hasPermission("easyplayerreport.moderate")) {
            buttons.add(action(staff, "kick", "Kick", NamedTextColor.RED, (response, audience) -> moderate(audience, report.getId(), "KICK")));
            buttons.add(action(staff, "warn", "Warn", NamedTextColor.RED, (response, audience) -> moderate(audience, report.getId(), "WARN")));
            buttons.add(action(staff, "ban", "Ban", NamedTextColor.DARK_RED, (response, audience) -> moderate(audience, report.getId(), "BAN")));
        }
        if (report.getStatus() != Report.Status.CLOSED && staff.hasPermission("easyplayerreport.falsereport")) {
            buttons.add(action(staff, "false-report", "Marcar reporte falso", NamedTextColor.DARK_RED,
                    (response, audience) -> confirmFalseReport(audience, report.getId())));
        }
        if (staff.hasPermission("easyplayerreport.admin")) buttons.add(action(staff, "delete", "Eliminar reporte", NamedTextColor.RED, (response, audience) -> confirmDelete(audience, report.getId())));
        buttons.add(action(staff, "back", "Volver", NamedTextColor.WHITE, (response, audience) -> {
            if (audience instanceof Player player) openList(player, pages.getOrDefault(player.getUniqueId(), 0), filters.getOrDefault(player.getUniqueId(), Filter.OPEN));
        }));

        DialogBase base = DialogBase.builder(menus.comp("staff-menu.detail-title", "Reporte", ACCENT)
                        .append(Component.text(" #" + report.getId()).color(NamedTextColor.GRAY)))
                .body(body).canCloseWithEscape(true).build();
        staff.showDialog(Dialog.create(builder -> builder.empty().base(base).type(DialogType.multiAction(buttons, null, 2))));
    }

    private void take(Audience audience, int id) {
        if (!(audience instanceof Player staff) || !hasStaff(staff)) return;
        Report report = find(staff, id);
        if (report == null || report.getStatus() != Report.Status.OPEN) {
            already(staff, report);
            return;
        }
        if (plugin.getReportManager().markTaken(report, staff.getUniqueId(), staff.getName())) {
            send(staff, "messages.report-taken", "%id%", String.valueOf(id));
            notifyReporter(report, "messages.staff-taken", staff.getName());
        }
        openDetail(staff, id);
    }

    private void release(Audience audience, int id) {
        if (!(audience instanceof Player staff) || !hasStaff(staff)) return;
        Report report = find(staff, id);
        if (report == null || report.getStatus() != Report.Status.TAKEN || !canManageTaken(staff, report)) {
            already(staff, report);
            return;
        }
        if (plugin.getReportManager().markReleased(report, staff.getUniqueId(), staff.getName())) send(staff, "messages.report-released", "%id%", String.valueOf(id));
        openDetail(staff, id);
    }

    private void reassign(Audience audience, int id) {
        if (!(audience instanceof Player staff) || !hasStaff(staff)) return;
        Report report = find(staff, id);
        if (report == null || report.getStatus() != Report.Status.TAKEN || !canManageTaken(staff, report)) {
            already(staff, report);
            return;
        }
        if (staff.getUniqueId().equals(report.getAssignedStaff())) {
            send(staff, "messages.report-already-assigned");
        } else if (plugin.getReportManager().markReassigned(report, staff.getUniqueId(), staff.getName())) {
            send(staff, "messages.report-reassigned", "%id%", String.valueOf(id));
            notifyReporter(report, "messages.staff-taken", staff.getName());
        }
        openDetail(staff, id);
    }

    private void confirmClose(Audience audience, int id) {
        if (audience instanceof Player staff) confirm(staff, id, "staff-menu.confirm-close", "¿Seguro que quieres cerrar el reporte #%id%?", (response, player) -> {
            if (player instanceof Player closeStaff) close(closeStaff, id);
        });
    }

    private void confirmDelete(Audience audience, int id) {
        if (audience instanceof Player staff) confirm(staff, id, "staff-menu.confirm-delete", "¿Seguro que quieres eliminar el reporte #%id%? Esta acción no se puede deshacer.", (response, player) -> {
            if (player instanceof Player deleteStaff) remove(deleteStaff, id);
        });
    }

    private void confirmReopen(Audience audience, int id) {
        if (audience instanceof Player staff) confirm(staff, id, "staff-menu.confirm-reopen", "¿Seguro que quieres reabrir el reporte #%id%?", (response, player) -> {
            if (player instanceof Player reopenStaff) reopen(reopenStaff, id);
        });
    }

    private void confirmFalseReport(Audience audience, int id) {
        if (audience instanceof Player staff) {
            Report report = find(staff, id);
            if (report == null) return;
            if (!staff.hasPermission("easyplayerreport.falsereport")) {
                send(staff, "messages.no-permission");
                return;
            }
            if (!report.isActive()) {
                send(staff, "messages.staff-already-closed");
                openDetail(staff, id);
                return;
            }
            int seconds = plugin.getReportManager().falseReportBlockSeconds();
            confirm(staff, id, "staff-menu.confirm-false-report",
                    "¿Seguro que el reporte #%id% es falso? Se cerrará y %reporter% no podrá reportar durante %time%.",
                    (response, player) -> {
                        if (player instanceof Player falseStaff) falseReport(falseStaff, id, seconds);
                    },
                    "%reporter%", report.getReporterName(),
                    "%time%", seconds > 0 ? plugin.getLang().fmtHuman(seconds * 1000L) : plugin.getLang().fmtHuman(0));
        }
    }

    private void falseReport(Player staff, int id, int blockSeconds) {
        Report report = find(staff, id);
        if (report == null) return;
        if (!plugin.getReportManager().markFalseReport(report, staff.getUniqueId(), staff.getName())) {
            send(staff, "messages.staff-already-closed");
            openDetail(staff, id);
            return;
        }
        send(staff, "messages.report-false-marked",
                "%id%", String.valueOf(id),
                "%reporter%", report.getReporterName(),
                "%time%", blockSeconds > 0 ? plugin.getLang().fmtHuman(blockSeconds * 1000L) : plugin.getLang().fmtHuman(0));
        notifyReporterTime(report, "messages.false-report-notify", plugin.getLang().fmtHuman(blockSeconds * 1000L));
        openDetail(staff, id);
    }

    private void confirm(Player staff, int id, String path, String def, DialogActionCallback yes, String... extraReplacements) {
        Menus menus = plugin.getMenus();
        String[] replacements = new String[2 + extraReplacements.length];
        replacements[0] = "%id%";
        replacements[1] = String.valueOf(id);
        System.arraycopy(extraReplacements, 0, replacements, 2, extraReplacements.length);
        DialogBase base = DialogBase.builder(menus.comp("staff-menu.confirm-title", "Confirmación", ACCENT))
                .body(List.of(DialogBody.plainMessage(menus.comp(path, def, NamedTextColor.GRAY, replacements))))
                .canCloseWithEscape(true).build();
        List<ActionButton> buttons = List.of(
                ActionButton.create(Component.text("✅ ").append(menus.comp("staff-menu.confirm-yes", "Sí", NamedTextColor.GREEN)), Component.text("Confirma la acción").color(NamedTextColor.GRAY), 200, DialogAction.customClick(yes, opts())),
                ActionButton.create(Component.text("❌ ").append(menus.comp("staff-menu.confirm-no", "No", NamedTextColor.RED)), Component.text("Cancela la acción").color(NamedTextColor.GRAY), 200, DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) openDetail(player, id);
                }, opts())));
        staff.showDialog(Dialog.create(builder -> builder.empty().base(base).type(DialogType.multiAction(buttons, null, 2))));
    }

    private void close(Player staff, int id) {
        if (!hasStaff(staff)) return;
        Report report = find(staff, id);
        if (report == null || !report.isActive() || (report.getStatus() == Report.Status.TAKEN && !canManageTaken(staff, report))) {
            already(staff, report);
            return;
        }
        if (plugin.getReportManager().markClosed(report, staff.getUniqueId(), staff.getName())) {
            send(staff, "messages.report-closed", "%id%", String.valueOf(id));
            notifyReporter(report, "messages.staff-closed", staff.getName());
        }
        openList(staff, pages.getOrDefault(staff.getUniqueId(), 0), filters.getOrDefault(staff.getUniqueId(), Filter.OPEN));
    }

    private void reopen(Player staff, int id) {
        if (!hasStaff(staff) || !staff.hasPermission("easyplayerreport.reopen")) return;
        Report report = find(staff, id);
        if (report == null || report.getStatus() != Report.Status.CLOSED) {
            send(staff, "messages.staff-already-closed");
            return;
        }
        long cooldown = plugin.getReportManager().reopenCooldownLeft(report);
        if (cooldown > 0) {
            send(staff, "messages.reopen-cooldown", "%time%", String.valueOf(cooldown));
            openDetail(staff, id);
            return;
        }
        if (plugin.getReportManager().markReopened(report, staff.getUniqueId(), staff.getName())) {
            send(staff, "messages.report-reopened", "%id%", String.valueOf(id));
            notifyReporter(report, "messages.staff-reopened", staff.getName());
        }
        openDetail(staff, id);
    }

    private void remove(Player staff, int id) {
        if (!staff.hasPermission("easyplayerreport.admin")) {
            send(staff, "messages.no-permission");
            return;
        }
        Report report = find(staff, id);
        if (report == null) return;
        plugin.getReportManager().markDeleted(report, staff.getUniqueId(), staff.getName());
        send(staff, "messages.report-deleted", "%id%", String.valueOf(id));
        openList(staff, pages.getOrDefault(staff.getUniqueId(), 0), filters.getOrDefault(staff.getUniqueId(), Filter.OPEN));
    }

    private void openNote(Audience audience, int id) {
        if (!(audience instanceof Player staff) || !hasStaff(staff)) return;
        Menus menus = plugin.getMenus();
        DialogBase base = DialogBase.builder(menus.comp("staff-menu.note-title", "Nota privada", ACCENT))
                .body(List.of(DialogBody.plainMessage(menus.comp("staff-menu.note-hint", "La nota solo la verá el staff.", NamedTextColor.GRAY))))
                .inputs(List.of(DialogInput.text("note", menus.comp("staff-menu.note-input", "Escribe la nota", NamedTextColor.GOLD)).maxLength(plugin.limit("limits.note-max-length", 500)).width(plugin.limit("limits.input-width", 310)).build()))
                .canCloseWithEscape(true).build();
        List<ActionButton> buttons = List.of(
                ActionButton.create(Component.text("💾 ").append(menus.comp("staff-menu.note-save", "Guardar nota", NamedTextColor.GREEN)), Component.text("Guarda la nota privada").color(NamedTextColor.GRAY), 200, DialogAction.customClick((response, clickAudience) -> {
                    if (clickAudience instanceof Player player) saveNote(player, id, response.getText("note"));
                }, opts())),
                ActionButton.create(Component.text("❌ ").append(menus.comp("staff-menu.note-cancel", "Cancelar", NamedTextColor.RED)), Component.text("No guardar").color(NamedTextColor.GRAY), 200, DialogAction.customClick((response, clickAudience) -> {
                    if (clickAudience instanceof Player player) openDetail(player, id);
                }, opts())));
        staff.showDialog(Dialog.create(builder -> builder.empty().base(base).type(DialogType.confirmation(buttons.get(0), buttons.get(1)))));
    }

    private void saveNote(Player staff, int id, String note) {
        Report report = find(staff, id);
        if (report == null || note == null || note.isBlank()) return;
        plugin.getReportManager().addNote(report, staff.getUniqueId(), staff.getName(), note);
        send(staff, "messages.report-note-saved", "%id%", String.valueOf(id));
        openDetail(staff, id);
    }

                private void teleport(Audience audience, int id, boolean reporter) {
        if (!(audience instanceof Player staff) || !hasStaff(staff)) return;
        Report report = find(staff, id);
        if (report == null) return;
        Player target = reporter ? Bukkit.getPlayer(report.getReporter()) : (report.getTargetUuid() == null ? Bukkit.getPlayerExact(report.getTarget()) : Bukkit.getPlayer(report.getTargetUuid()));
        org.bukkit.Location location = target != null && target.isOnline() ? target.getLocation() : reporter ? report.getReporterLocation() : report.getTargetLocation();
        if (location == null) send(staff, "messages.teleport-none");
        else {
            staff.teleport(location);
            send(staff, "messages.teleported-to");
        }
        openDetail(staff, id);
    }

    private void moderate(Audience audience, int id, String action) {
        if (!(audience instanceof Player staff)) return;
        if (!staff.hasPermission("easyplayerreport.moderate")) {
            send(staff, "messages.moderate-only");
            return;
        }
        Report report = find(staff, id);
        if (report == null || !report.isActive()) return;
        Player target = report.getTargetUuid() == null ? Bukkit.getPlayerExact(report.getTarget()) : Bukkit.getPlayer(report.getTargetUuid());
        if (action.equals("BAN")) {
            // Sin codigos de color: el motivo de un ban se guarda tal cual en
            // la lista de vetados y se muestra al jugador banned al reconectar.
            String reason = plain(cfg("messages.target-banned"));
            Date expiration = banExpiration();
            // Se registra el ban antes de expulsar: al revés queda una ventana
            // en la que el jugador puede reconectar sin quedar baneado.
            plugin.getServer().getBanList(BanList.Type.NAME).addBan(report.getTarget(), reason, expiration, staff.getName());
            if (plugin.getConfig().getBoolean("moderation.ban-ip", false) && target != null && target.getAddress() != null) {
                plugin.getServer().getBanList(BanList.Type.IP).addBan(target.getAddress().getAddress().getHostAddress(), reason, expiration, staff.getName());
            }
            if (target != null && target.isOnline()) target.kickPlayer(reason);
            send(staff, "messages.staff-banned", "%target%", report.getTarget());
        } else {
            if (target == null || !target.isOnline()) {
                send(staff, "messages.target-offline", "%target%", report.getTarget());
                openDetail(staff, id);
                return;
            }
            if (action.equals("KICK")) {
                target.kickPlayer(colored(cfg("messages.target-kicked")));
                send(staff, "messages.staff-kicked", "%target%", report.getTarget());
            } else {
                target.sendMessage(colored(cfg("messages.target-warned").replace("%reason%", report.getReason())));
                send(staff, "messages.staff-warned", "%target%", report.getTarget());
            }
        }
        plugin.getReportManager().addModerationEvent(report, staff.getUniqueId(), staff.getName(), action);
        openDetail(staff, id);
    }

    /**
     * Fecha de expiración del ban aplicado desde un reporte, o null si es
     * permanente. Lee moderation.ban-duration: "30d", "12h", "45m", "1d12h"
     * o un numero suelto (segundos).
     */
    private Date banExpiration() {
        String configured = plugin.getConfig().getString("moderation.ban-duration", "permanent");
        if (configured == null) return null;
        String value = configured.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.equals("permanent") || value.equals("perm") || value.equals("0")) return null;
        Duration duration = parseDuration(value);
        if (duration == null || duration.isZero() || duration.isNegative()) return null;
        return new Date(System.currentTimeMillis() + duration.toMillis());
    }

    private static Duration parseDuration(String value) {
        if (value.chars().allMatch(Character::isDigit)) return Duration.ofSeconds(Long.parseLong(value));
        Duration total = Duration.ZERO;
        long current = 0;
        boolean hasUnit = false;
        for (char character : value.toCharArray()) {
            if (Character.isDigit(character)) {
                current = current * 10 + (character - '0');
                continue;
            }
            long amount = current;
            current = 0;
            switch (character) {
                case 'w' -> total = total.plusDays(amount * 7L);
                case 'd' -> total = total.plusDays(amount);
                case 'h' -> total = total.plusHours(amount);
                case 'm' -> total = total.plusMinutes(amount);
                case 's' -> total = total.plusSeconds(amount);
                default -> {
                    hasUnit = false;
                    continue;
                }
            }
            hasUnit = true;
        }
        // Si quedaron digitos sin unidad ("1d2"), la cadena esta incompleta y
        // truncarla en silencio daria un baneo mas corto del que se pidio.
        return hasUnit && current == 0 ? total : null;
    }

    private Report find(Player staff, int id) {
        Report report = plugin.getReportManager().getById(id);
        if (report == null) send(staff, "messages.report-not-found");
        return report;
    }

    private boolean hasStaff(Player player) {
        if (player.hasPermission("easyplayerreport.staff")) return true;
        send(player, "messages.no-permission");
        return false;
    }

    private boolean canManageTaken(Player staff, Report report) {
        return report.getStatus() != Report.Status.TAKEN || staff.getUniqueId().equals(report.getAssignedStaff()) || staff.hasPermission("easyplayerreport.admin");
    }

    private void already(Player staff, Report report) {
        if (report != null && report.getStatus() == Report.Status.CLOSED) send(staff, "messages.staff-already-closed");
        else if (report != null && report.getAssignedStaffName() != null) send(staff, "messages.staff-taken-other", "%staff%", report.getAssignedStaffName());
    }

    private void notifyReporter(Report report, String path, String staffName) {
        Player reporter = Bukkit.getPlayer(report.getReporter());
        if (reporter != null && reporter.isOnline()) reporter.sendMessage(colored(cfg(path).replace("%id%", String.valueOf(report.getId())).replace("%staff%", staffName)));
    }

    /** Igual que notifyReporter pero sustituyendo %time% por un texto de duración. */
    private void notifyReporterTime(Report report, String path, String humanDuration) {
        Player reporter = Bukkit.getPlayer(report.getReporter());
        if (reporter != null && reporter.isOnline()) {
            reporter.sendMessage(colored(cfg(path)
                    .replace("%id%", String.valueOf(report.getId()))
                    .replace("%time%", humanDuration)));
        }
    }

    private DialogBody field(Menus menus, String path, String def, String value, NamedTextColor color) {
        return DialogBody.plainMessage(menus.comp(path, def, NamedTextColor.WHITE).append(Component.text(value).color(color)));
    }

    private void addExpiration(List<DialogBody> body, Report report) {
        long remaining = plugin.getReportManager().getRemainingSeconds(report);
        if (remaining <= 0 || !report.isActive()) return;
        body.add(DialogBody.plainMessage(Component.text(cfg("messages.expiration-line")).color(NamedTextColor.GRAY)
                .append(Component.text(Lang.fmtRemaining(remaining * 1000L)).color(NamedTextColor.GOLD))));
    }

    private ActionButton action(Player staff, String key, String def, NamedTextColor color, DialogActionCallback callback) {
        return ActionButton.create(Component.text(iconFor(key) + " ").append(plugin.getMenus().comp("staff-menu." + key, def, color)),
                Component.text(def).color(NamedTextColor.GRAY), 200, DialogAction.customClick(callback, opts()));
    }

    private String iconFor(String key) {
        return switch (key) {
            case "take" -> "📋";
            case "release" -> "🔓";
            case "reassign" -> "♻";
            case "close-report" -> "✅";
            case "reopen" -> "↩";
            case "delete" -> "🗑";
            case "tp-reporter", "tp-target" -> "📍";
            case "chat" -> "💬";
            case "note" -> "📝";
            case "kick" -> "👢";
            case "warn" -> "⚠";
            case "ban" -> "🔨";
            case "false-report" -> "🚫";
            case "back" -> "↩";
            default -> "•";
        };
    }

    private ActionButton pageButton(Player staff, int page, String key, String def) {
        return ActionButton.create(Menus.build(Menus.stripMarkup(plugin.getMenus().raw(key, def)), NamedTextColor.YELLOW),
                Component.text("Cambia de página").color(NamedTextColor.GRAY), 180, DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player) openList(player, page, filters.getOrDefault(player.getUniqueId(), Filter.OPEN));
        }, opts()));
    }

    private ActionButton closeButton(String def, String tooltip) {
        return ActionButton.create(Component.text("❌ ").append(Menus.build(Menus.stripMarkup(plugin.getMenus().raw("staff-menu.close", def)), NamedTextColor.RED)),
                Component.text(tooltip).color(NamedTextColor.GRAY), 120, null);
    }

    private String plain(String value) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', value));
    }

    private String cfg(String path) {
        return plugin.getLang().msg(path, "&7");
    }

    private String colored(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private void send(Player player, String path, String... replacements) {
        String message = cfg(path);
        for (int i = 0; i + 1 < replacements.length; i += 2) message = message.replace(replacements[i], replacements[i + 1]);
        player.sendMessage(colored(message));
    }

    private ClickCallback.Options opts() {
        return ClickCallback.Options.builder().build();
    }
}
