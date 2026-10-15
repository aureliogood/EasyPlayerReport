package com.easyplayerreport;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dialogo de reporte con la Dialog API nativa de Paper (ventana con campos de
 * texto reales, sin cofre ni yunque). Un solo dialogo con jugador, motivo
 * (opcion unica) y descripcion, botones Enviar/Cancelar.
 */
public class ReportDialog {
    private final EasyPlayerReport plugin;

    public ReportDialog(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    // ================= apertura =================

    private List<String> reasons(Player player) {
        return plugin.getPermissionConfig().reasons(player);
    }

    /**
     * Un reporte falso bloquea los reportes del jugador durante
     * false-report-block-seconds. Se comprueba al abrir el formulario y otra vez
     * al enviarlo, porque entre medias el staff puede habérselo marcado.
     */
    private boolean falseReportBlocked(Player player) {
        long left = plugin.getReportManager().falseReportBlockLeft(player.getUniqueId());
        if (left <= 0) return false;
        player.sendMessage(colored(cfg("messages.false-report-blocked")
                .replace("%time%", plugin.getLang().fmtHuman(left))));
        return true;
    }

    public void open(Player p) {
        if (!p.hasPermission("easyplayerreport.use")) {
            p.sendMessage(colored(cfg("messages.no-permission")));
            return;
        }
        if (falseReportBlocked(p)) return;
        if (plugin.getReportManager().hasCooldown(p.getUniqueId(), p)) {
            long left = plugin.getReportManager().cooldownLeft(p.getUniqueId(), p);
            p.sendMessage(colored(cfg("messages.cooldown").replace("%time%", String.valueOf(Math.max(1, left / 1000)))));
            return;
        }
        if (plugin.getReportManager().activeCountFor(p.getUniqueId()) >= plugin.getPermissionConfig().limits(p).maxActive()) {
            p.sendMessage(colored(cfg("messages.too-many")));
            return;
        }

        List<String> reasons = reasons(p);
        if (reasons.isEmpty()) {
            p.sendMessage(colored(cfg("messages.reason-unavailable")));
            return;
        }
        openReasonSelection(p, reasons);
    }

    private void openReasonSelection(Player player, List<String> reasons) {
        Menus m = plugin.getMenus();
        List<ActionButton> buttons = new ArrayList<>();
        for (String reason : reasons) {
            buttons.add(ActionButton.create(Component.text(reason).color(NamedTextColor.WHITE),
                    Component.text("Seleccionar este motivo").color(NamedTextColor.GRAY), 200,
                    DialogAction.customClick((response, audience) -> {
                        if (audience instanceof Player selected) openForm(selected, reason);
                    }, ClickCallback.Options.builder().build())));
        }
        buttons.add(cancelarButton());
        DialogBase base = DialogBase.builder(m.comp("report-menu.title", "EasyPlayerReport", TextColor.color(0x1DB954)))
                .body(List.of(DialogBody.plainMessage(m.comp("report-menu.hint-reason", "Selecciona un motivo para continuar.", NamedTextColor.GRAY))))
                .canCloseWithEscape(true)
                .build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.multiAction(buttons, null, 2))));
    }

    private void openForm(Player player, String reason) {
        Menus m = plugin.getMenus();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(m.comp("report-menu.title", "EasyPlayerReport", TextColor.color(0x1DB954)))
                        .body(List.of(
                                DialogBody.plainMessage(m.comp("report-menu.hint", "Completa el formulario y pulsa enviar.", NamedTextColor.GRAY)),
                                DialogBody.plainMessage(Component.text("Motivo seleccionado: ").color(NamedTextColor.GOLD)
                                        .append(Component.text(reason).color(NamedTextColor.WHITE)))))
                        .inputs(List.of(
                                DialogInput.text("target", m.comp("report-menu.input-player", "Jugador a reportar", NamedTextColor.GOLD))
                                        .maxLength(plugin.limit("limits.target-name-max-length", 16))
                                        .width(plugin.limit("limits.input-width", 310)).build(),
                                DialogInput.text("desc", m.comp("report-menu.input-desc", "Descripción (opcional)", NamedTextColor.GOLD))
                                        .maxLength(plugin.limit("limits.description-max-length", 120))
                                        .width(plugin.limit("limits.input-width", 310)).build()))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(enviarButton(reason), cancelarButton())));
        player.showDialog(dialog);
    }

    // ================= botones =================

    private ActionButton enviarButton(String reason) {
        Menus m = plugin.getMenus();
        return ActionButton.create(
                Component.text("📤 ").append(m.comp("report-menu.submit", "Enviar", NamedTextColor.GREEN)),
                Component.text("Envía el reporte al staff").color(NamedTextColor.GRAY),
                200,
                DialogAction.customClick((response, audience) -> submit(response, audience, reason),
                        ClickCallback.Options.builder().build()));
    }

    private ActionButton cancelarButton() {
        Menus m = plugin.getMenus();
        return ActionButton.create(
                Component.text("❌ ").append(m.comp("report-menu.cancel", "Cancelar", NamedTextColor.RED)),
                Component.text("Descarta el reporte").color(NamedTextColor.GRAY),
                200,
                DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) {
                        player.sendMessage(colored(cfg("messages.report-cancelled")));
                    }
                }, ClickCallback.Options.builder().build()));
    }

    // ================= envio =================

    private void submit(DialogResponseView response, Audience audience, String reason) {
        if (!(audience instanceof Player player)) {
            return;
        }

        String targetName = response.getText("target");
        String desc = response.getText("desc");
        if (targetName == null || targetName.trim().isEmpty()) {
            player.sendMessage(colored(cfg("messages.invalid-player")));
            open(player);
            return;
        }
        if (reason == null || !reasons(player).contains(reason) || !plugin.getPermissionConfig().canUseReason(player, reason)) {
            player.sendMessage(colored(cfg("messages.reason-unavailable")));
            open(player);
            return;
        }

        String target = targetName.trim();
        if (target.equalsIgnoreCase(player.getName())) {
            player.sendMessage(colored(cfg("messages.self-report")));
            open(player);
            return;
        }
        if (Bukkit.getPlayerExact(target) == null) {
            player.sendMessage(colored(cfg("messages.invalid-player")));
            open(player);
            return;
        }

        String description = desc == null ? "" : desc.trim();

        // si ya hay un reporte abierto contra el mismo jugador, avisar antes de guardar
        Report dup = plugin.getReportManager().findDuplicate(player.getUniqueId(), target);
        if (dup != null) {
            showDuplicateDialog(player, dup, target, reason, description);
            return;
        }
        doCreate(player, target, reason, description);
    }

    private void doCreate(Player player, String target, String reason, String description) {
        if (!player.hasPermission("easyplayerreport.use")) {
            player.sendMessage(colored(cfg("messages.no-permission")));
            return;
        }
        if (falseReportBlocked(player)) return;
        if (plugin.getReportManager().hasCooldown(player.getUniqueId(), player)) {
            long left = plugin.getReportManager().cooldownLeft(player.getUniqueId(), player);
            player.sendMessage(colored(cfg("messages.cooldown").replace("%time%", String.valueOf(Math.max(1, left / 1000)))));
            return;
        }
        if (plugin.getReportManager().activeCountFor(player.getUniqueId())
                >= plugin.getPermissionConfig().limits(player).maxActive()) {
            player.sendMessage(colored(cfg("messages.too-many")));
            return;
        }
        Player reported = Bukkit.getPlayerExact(target);
        if (reported == null || !reported.isOnline()) {
            player.sendMessage(colored(cfg("messages.invalid-player")));
            return;
        }
        UUID targetUuid = reported.getUniqueId();
        Location targetLoc = reported.getLocation();

        Report report = plugin.getReportManager().createReport(
                player.getUniqueId(), player.getName(), player, target, targetUuid,
                reason, description, player.getLocation(), targetLoc);
        plugin.getReportManager().setCooldown(player.getUniqueId());
        // Aviso a Discord. Va antes que el sonido y los mensajes para que salga
        // lo antes posible, y es asincrono: no frena al jugador ni el hilo.
        plugin.getDiscordNotifier().notifyCreated(report);
        player.sendMessage(colored(cfg("messages.report-created")));
        int blindTicks = plugin.seconds("effects.reported-blindness-ticks", 60);
        if (blindTicks > 0) {
            reported.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindTicks, plugin.intOr("effects.reported-blindness-amplifier", 0)));
        }
        reported.playSound(reported.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                (float) plugin.getConfig().getDouble("sounds.reported-volume", 1.0),
                (float) plugin.getConfig().getDouble("sounds.reported-pitch", 1.5));
        reported.sendMessage(colored(cfg("messages.target-reported").replace("%reporter%", player.getName())));

        // avisar al staff online, con contador de pendientes y sonido
        int pending = plugin.getReportManager().getActive().size();
        int threshold = plugin.seconds("staff-alert-threshold", 0);
        Component alert = global(TextColor.color(0x1DB954), "[REPORTE] ", line("#" + report.getId() + " ", NamedTextColor.WHITE))
                .append(line(player.getName(), NamedTextColor.AQUA))
                .append(line(" reporto a ", NamedTextColor.WHITE))
                .append(line(target, NamedTextColor.GOLD))
                .append(line(" motivo ", NamedTextColor.WHITE))
                .append(line(reason, NamedTextColor.GOLD));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("easyplayerreport.staff") && plugin.areStaffNotificationsEnabled(online)) {
                online.sendMessage(alert);
                String pend = cfg("messages.staff-pending").replace("%count%", String.valueOf(pending));
                if (!pend.isEmpty()) online.sendMessage(colored(pend));
                online.sendMessage(Component.text("Usa ")
                        .append(line("/reportesadmin", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/reportesadmin")))
                        .append(Component.text(" para gestionarlo.")));
                boolean busy = threshold > 0 && pending > threshold;
                float pitch = (float) plugin.getConfig().getDouble(
                        busy ? "sounds.alert-pitch-busy" : "sounds.alert-pitch", busy ? 2.0 : 1.0);
                if (plugin.getPermissionConfig().playStaffSound(online)) {
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,
                            (float) plugin.getConfig().getDouble("sounds.alert-volume", 1.0), pitch);
                }
            }
        }
    }

    private void showDuplicateDialog(Player player, Report dup, String target, String reason, String description) {
        Menus m = plugin.getMenus();
        DialogBase base = DialogBase.builder(m.comp("report-menu.duplicate-title", "Reporte duplicado",
                        TextColor.color(0x1DB954)))
                .body(List.of(DialogBody.plainMessage(m.comp("report-menu.duplicate-message",
                        "Ya tienes un reporte abierto contra %target% (#%id%). ¿Enviarlo de todos modos?",
                        NamedTextColor.GRAY, "%target%", target, "%id%", String.valueOf(dup.getId())))))
                .canCloseWithEscape(true)
                .build();
        Dialog dialog = Dialog.create(b -> b.empty().base(base)
                .type(DialogType.multiAction(List.of(
                        ActionButton.create(Component.text("📤 ").append(m.comp("report-menu.duplicate-send", "Enviar de todos modos", NamedTextColor.GREEN)),
                                Component.text("Guarda el reporte igualmente").color(NamedTextColor.GRAY),
                                200,
                                DialogAction.customClick((response, audience) -> {
                                    if (audience instanceof Player pl) doCreate(pl, target, reason, description);
                                }, ClickCallback.Options.builder().build())),
                        ActionButton.create(Component.text("↩ ").append(m.comp("report-menu.duplicate-cancel", "No enviar", NamedTextColor.RED)),
                                Component.text("Descarta el reporte").color(NamedTextColor.GRAY),
                                200,
                                DialogAction.customClick((response, audience) -> {
                                    if (audience instanceof Player pl) {
                                        pl.sendMessage(colored(cfg("messages.report-cancelled")));
                                    }
                                }, ClickCallback.Options.builder().build()))
                ), null, 2)));
        player.showDialog(dialog);
    }

    // ================= utilidades =================

    private Component global(TextColor labelColor, String label, Component value) {
        return Component.text(label).color(labelColor).append(value);
    }
    private Component line(String text, TextColor color) {
        return Component.text(text).color(color);
    }
    private String cfg(String path) {
        return plugin.getLang().msg(path, "&7");
    }
    private String colored(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}