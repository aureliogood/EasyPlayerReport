package com.easyplayerreport;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class MyReportsDialog {
    private static final TextColor ACCENT = TextColor.color(0x1DB954);

    private enum Mode { MINE, RECEIVED }

    private final EasyPlayerReport plugin;

    public MyReportsDialog(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("easyplayerreport.use")) {
            player.sendMessage(colored(plugin.getLang().msg("messages.no-permission", "&cNo tienes permiso para esto.")));
            return;
        }
        Menus menus = plugin.getMenus();
        List<ActionButton> buttons = List.of(
                ActionButton.create(menus.comp("myreports-menu.button-mine", "📋 Mis reportes", NamedTextColor.GREEN),
                        menus.comp("myreports-menu.button-mine-tooltip", "Ver reportes que has creado", NamedTextColor.GRAY), 200,
                        DialogAction.customClick((response, audience) -> {
                            if (audience instanceof Player user) openList(user, Mode.MINE, 0);
                        }, opts())),
                ActionButton.create(menus.comp("myreports-menu.button-received", "📥 Reportes recibidos", NamedTextColor.AQUA),
                        menus.comp("myreports-menu.button-received-tooltip", "Ver reportes recibidos", NamedTextColor.GRAY), 200,
                        DialogAction.customClick((response, audience) -> {
                            if (audience instanceof Player user) openList(user, Mode.RECEIVED, 0);
                        }, opts())),
                closeButton(null, null));
        DialogBase base = DialogBase.builder(menus.comp("myreports-menu.title", "📋 Reportes", ACCENT))
                .body(List.of(DialogBody.plainMessage(menus.comp("myreports-menu.hint", "Elige qué reportes quieres ver.", NamedTextColor.GRAY))))
                .canCloseWithEscape(true).build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.multiAction(buttons, null, 2))));
    }

    private void openList(Player player, Mode mode, int requestedPage) {
        List<Report> reports = mode == Mode.MINE
                ? plugin.getReportManager().getCreatedFor(player.getUniqueId())
                : plugin.getReportManager().getReceivedFor(player.getUniqueId());
        if (reports.isEmpty()) {
            openEmpty(player, mode);
            return;
        }
        int maxPage = (reports.size() - 1) / plugin.pageSize();
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        int start = page * plugin.pageSize();
        Menus menus = plugin.getMenus();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(menus.comp(mode == Mode.MINE
                ? "myreports-menu.list-mine" : "myreports-menu.list-received",
                mode == Mode.MINE ? "Todos tus reportes, abiertos y cerrados." : "Reportes que han creado otros contra ti.",
                NamedTextColor.GRAY)));
        boolean anyChat = false;
        for (int i = start; i < Math.min(reports.size(), start + plugin.pageSize()); i++) {
            Report report = reports.get(i);
            body.add(DialogBody.plainMessage(Component.text("📋 #" + report.getId() + " · " + report.getTarget()
                    + " · " + report.getReason()).color(NamedTextColor.WHITE)));
            anyChat |= chatAvailable(player, report, mode);
        }
        if (!anyChat) {
            body.add(DialogBody.plainMessage(menus.comp("myreports-menu.chat-locked",
                    "El chat se habilita cuando un miembro del staff toma el reporte.", NamedTextColor.DARK_GRAY)));
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = start; i < Math.min(reports.size(), start + plugin.pageSize()); i++) {
            Report report = reports.get(i);
            buttons.add(ActionButton.create(Component.text("📋 #" + report.getId()).color(NamedTextColor.GOLD),
                    Component.text("Abrir detalles").color(NamedTextColor.GRAY), 180,
                    DialogAction.customClick((response, audience) -> {
                        if (audience instanceof Player user) detail(user, report.getId(), mode);
                    }, opts())));
            if (chatAvailable(player, report, mode)) buttons.add(chatButton(report.getId(), true));
        }
        if (page > 0) buttons.add(pageButton(menus.comp("myreports-menu.previous", "◀ Anterior", NamedTextColor.YELLOW), page - 1, mode));
        if (page < maxPage) buttons.add(pageButton(menus.comp("myreports-menu.next", "▶ Siguiente", NamedTextColor.YELLOW), page + 1, mode));
        buttons.add(closeButton(null, null));
        DialogBase base = DialogBase.builder(menus.comp(mode == Mode.MINE
                        ? "myreports-menu.title-mine" : "myreports-menu.title-received",
                        mode == Mode.MINE ? "📋 Mis reportes" : "📥 Reportes recibidos", ACCENT))
                .body(body).canCloseWithEscape(true).build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.multiAction(buttons, null, 2))));
    }

    private void openEmpty(Player player, Mode mode) {
        Menus menus = plugin.getMenus();
        String emptyKey = mode == Mode.MINE ? "myreports-menu.empty-mine" : "myreports-menu.empty-received";
        String emptyDefault = mode == Mode.MINE
                ? "No tienes reportes todavía." : "Nadie te ha reportado todavía.";
        // Si el menus.yml del servidor es anterior a estas claves, se sigue
        // usando el texto generico myreports-menu.empty.
        String emptyText = menus.raw(emptyKey, null);
        DialogBase base = DialogBase.builder(menus.comp("myreports-menu.title", "📋 Reportes", ACCENT))
                .body(List.of(DialogBody.plainMessage(emptyText == null
                        ? menus.comp("myreports-menu.empty", emptyDefault, NamedTextColor.GRAY)
                        : Menus.build(emptyText, NamedTextColor.GRAY))))
                .canCloseWithEscape(true).build();
        ActionButton ok = ActionButton.create(
                menus.comp("myreports-menu.empty-button", "Entendido", NamedTextColor.GRAY), null, 120, null);
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.notice(ok))));
    }

    private void detail(Player player, int id, Mode mode) {
        Report report = plugin.getReportManager().getById(id);
        if (report == null || !canAccess(player, report, mode)) {
            open(player);
            return;
        }
        Menus menus = plugin.getMenus();
        List<DialogBody> body = new ArrayList<>();
        body.add(field(menus, "myreports-menu.label-reporter", "Persona que reportó:", report.getReporterName()));
        body.add(field(menus, "myreports-menu.label-target", "Persona reportada:", report.getTarget()));
        body.add(field(menus, "myreports-menu.label-reason", "Razón:", report.getReason()));
        body.add(DialogBody.plainMessage(menus.comp("myreports-menu.label-desc", "Descripción:", NamedTextColor.WHITE)));
        body.add(DialogBody.plainMessage(report.getDescription().isBlank()
                ? menus.comp("myreports-menu.label-no-desc", "(Sin descripción)", NamedTextColor.GRAY)
                : Component.text(report.getDescription()).color(NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(menus.comp("myreports-menu.label-status", "Estado: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.getLang().plainStatusLabel(report.getStatus())).color(NamedTextColor.GRAY))));
        if (report.hasAppeal()) {
            body.add(DialogBody.plainMessage(menus.comp("myreports-menu.appeal-of", "📨 Apelación del reportado", NamedTextColor.YELLOW)));
            body.add(DialogBody.plainMessage(Component.text(report.getAppealText()).color(NamedTextColor.WHITE)));
        }
        boolean canAppeal = canAppeal(player) && mode == Mode.RECEIVED && !report.hasAppeal()
                && plugin.getReportManager().appealCooldownLeft(player.getUniqueId()) == 0;
        if (canAppeal) {
            body.add(DialogBody.plainMessage(menus.comp("myreports-menu.appeal-available",
                    "Puedes apelar este reporte si crees que fue injusto.", NamedTextColor.YELLOW)));
        }
        boolean canChat = chatAvailable(player, report, mode);
        List<ActionButton> buttons = new ArrayList<>();
        if (canChat) {
            buttons.add(chatButton(report.getId()));
        }
        if (canAppeal) {
            buttons.add(appealButton(report.getId()));
        }
        buttons.add(closeButton(null, null));
        DialogBase base = DialogBase.builder(menus.comp("myreports-menu.detail-title", "📋 Reporte", ACCENT)
                        .append(Component.text(" #" + report.getId()).color(NamedTextColor.GRAY)))
                .body(body).canCloseWithEscape(true).build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.multiAction(buttons, null, buttons.size()))));
    }

    /**
     * El chat del reporte solo existe si sigue abierto y un miembro del staff ya
     * lo tomó; ReportChatDialog vuelve a validar el acceso de quien lo usa.
     */
    private boolean chatAvailable(Player player, Report report, Mode mode) {
        return report.isActive() && report.getAssignedStaff() != null && canAccess(player, report, mode);
    }

    private ActionButton chatButton(int id) {
        return chatButton(id, false);
    }

    private ActionButton chatButton(int id, boolean compact) {
        Menus menus = plugin.getMenus();
        String key = compact ? "myreports-menu.chat-compact" : "myreports-menu.chat";
        String fallback = compact ? "💬" : "💬 Ir al chat";
        return ActionButton.create(Menus.build(Menus.stripMarkup(menus.raw(key, fallback)), NamedTextColor.AQUA),
                menus.comp("myreports-menu.chat-tooltip", "Habla con el staff que lleva este reporte", NamedTextColor.GRAY), 200,
                DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) plugin.getReportChatDialog().open(player, id);
                }, opts()));
    }

    private ActionButton appealButton(int id) {
        Menus menus = plugin.getMenus();
        return ActionButton.create(Menus.build(Menus.stripMarkup(menus.raw("myreports-menu.appeal-button", "📨 Apelar reporte")), NamedTextColor.YELLOW),
                menus.comp("myreports-menu.appeal-tooltip", "Escribe por qué este reporte es injusto", NamedTextColor.GRAY), 200,
                DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) openAppeal(player, id);
                }, opts()));
    }

    /** El permiso easyplayerreport.appeal manda igual por GUI que por comando. */
    private boolean canAppeal(Player player) {
        return player.hasPermission("easyplayerreport.appeal");
    }

    public void openAppeal(Player player, int id) {
        if (!canAppeal(player)) {
            player.sendMessage(colored(plugin.getLang().msg("messages.no-permission", "&cNo tienes permiso para esto.")));
            open(player);
            return;
        }
        Report report = plugin.getReportManager().getById(id);
        if (report == null) {
            player.sendMessage(colored(plugin.getLang().msg("messages.appeal-not-found", "&cEse reporte no existe.")));
            open(player);
            return;
        }
        if (!player.getUniqueId().equals(report.getTargetUuid())) {
            detail(player, id, Mode.RECEIVED);
            return;
        }
        if (report.hasAppeal()) {
            player.sendMessage(colored(plugin.getLang().msg("messages.appeal-already", "&eEste reporte ya tiene una apelación.")));
            detail(player, id, Mode.RECEIVED);
            return;
        }
        long cooldown = plugin.getReportManager().appealCooldownLeft(player.getUniqueId());
        if (cooldown > 0) {
            player.sendMessage(colored(plugin.getLang().msg("messages.appeal-cooldown",
                    "&cEspera %time% antes de apelar otro reporte.").replace("%time%", Lang.fmtRemaining(cooldown * 1000L))));
            detail(player, id, Mode.RECEIVED);
            return;
        }
        Menus menus = plugin.getMenus();
        DialogBase base = DialogBase.builder(menus.comp("myreports-menu.appeal-title", "Apelar Reporte", ACCENT))
                .body(List.of(DialogBody.plainMessage(menus.comp("myreports-menu.appeal-hint", "Escribe por qué crees que este reporte es injusto.", NamedTextColor.GRAY))))
                .inputs(List.of(DialogInput.text("appeal", menus.comp("myreports-menu.appeal-input", "Descripción de la apelación", NamedTextColor.GOLD))
                        .maxLength(plugin.limit("limits.appeal-max-length", 500))
                        .width(plugin.limit("limits.input-width", 310)).build()))
                .canCloseWithEscape(true)
                .build();
        ActionButton send = ActionButton.create(menus.comp("myreports-menu.appeal-send", "📨 Enviar apelación", NamedTextColor.GREEN),
                menus.comp("myreports-menu.appeal-send-tooltip", "Enviar la apelación al staff", NamedTextColor.GRAY), 200,
                DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player user) submitAppeal(user, id, response.getText("appeal"));
                }, opts()));
        ActionButton cancel = closeButton(menus.raw("myreports-menu.appeal-cancel", "❌ Cancelar"),
                menus.comp("myreports-menu.appeal-cancel-tooltip", "No enviar la apelación", NamedTextColor.GRAY));
        player.showDialog(Dialog.create(builder -> builder.empty().base(base).type(DialogType.confirmation(send, cancel))));
    }

    private void submitAppeal(Player player, int id, String text) {
        if (text == null || text.isBlank()) {
            player.sendMessage(colored(plugin.getLang().msg("messages.appeal-empty", "&cEscribe una descripción antes de enviar.")));
            openAppeal(player, id);
            return;
        }
        Report report = plugin.getReportManager().getById(id);
        if (report == null) {
            player.sendMessage(colored(plugin.getLang().msg("messages.appeal-not-found", "&cEse reporte no existe.")));
            open(player);
            return;
        }
        if (!plugin.getReportManager().addAppeal(report, player.getUniqueId(), player.getName(), text)) {
            String reason = report.hasAppeal()
                    ? plugin.getLang().msg("messages.appeal-already", "&eEste reporte ya tiene una apelación.")
                    : plugin.getReportManager().appealCooldownLeft(player.getUniqueId()) > 0
                    ? plugin.getLang().msg("messages.appeal-cooldown", "&cEspera %time% antes de apelar otro reporte.")
                    .replace("%time%", Lang.fmtRemaining(plugin.getReportManager().appealCooldownLeft(player.getUniqueId()) * 1000L))
                    : plugin.getLang().msg("messages.appeal-failed", "&cNo se pudo enviar la apelación.");
            player.sendMessage(colored(reason));
            detail(player, id, Mode.RECEIVED);
            return;
        }
        player.sendMessage(colored(plugin.getLang().msg("messages.appeal-created", "&aTu apelación #%id% fue enviada al staff.").replace("%id%", String.valueOf(id))));
        detail(player, id, Mode.RECEIVED);
    }

    private boolean canAccess(Player player, Report report, Mode mode) {
        return mode == Mode.MINE
                ? report.getReporter().equals(player.getUniqueId())
                : player.getUniqueId().equals(report.getTargetUuid());
    }

    private DialogBody field(Menus menus, String path, String label, String value) {
        return DialogBody.plainMessage(menus.comp(path, label, NamedTextColor.WHITE)
                .append(Component.text(value).color(NamedTextColor.GOLD)));
    }

    private ActionButton pageButton(Component label, int page, Mode mode) {
        return ActionButton.create(label,
                Component.text("Cambiar de página").color(NamedTextColor.GRAY), 180,
                DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) openList(player, mode, page);
                }, opts()));
    }

    private ActionButton closeButton(String label, Component tooltip) {
        String raw = label != null ? label
                : "❌ " + Menus.stripMarkup(plugin.getMenus().raw("myreports-menu.close", "Cerrar"));
        return ActionButton.create(Menus.build(Menus.stripMarkup(raw), NamedTextColor.RED), tooltip, 120, null);
    }

    private ClickCallback.Options opts() {
        return ClickCallback.Options.builder().build();
    }

    private String colored(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
