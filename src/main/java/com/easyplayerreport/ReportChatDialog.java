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
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat privado por reporte entre el reportero y el staff que lo tomo.
 * /reportechat <id> abre la conversacion: historial + campo de texto + enviar.
 * Al enviar se reabre la ventana del emisor y, si la otra persona tiene la
 * ventana abierta (se abrio hace pocos segundos), se le reabre tambien con el
 * historial actualizado. Si no, recibe un ping clickeable en el chat.
 */
public class ReportChatDialog {
    private static final TextColor ACCENT = TextColor.color(0x1DB954);

    private final EasyPlayerReport plugin;
    private final Map<UUID, Long> chatLastOpen = new HashMap<>();
    private final Map<UUID, Integer> chatReport = new HashMap<>();

    public ReportChatDialog(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    public void open(Player p, int id) {
        Report r = plugin.getReportManager().getById(id);
        if (r == null) {
            forget(p.getUniqueId());
            p.sendMessage(colored(cfg("messages.report-not-found")));
            return;
        }
        if (!r.isActive()) {
            forget(p.getUniqueId());
            p.sendMessage(colored(cfg("messages.staff-already-closed")));
            return;
        }
        if (r.getAssignedStaff() == null) {
            forget(p.getUniqueId());
            p.sendMessage(colored(cfg("messages.chat-no-staff")));
            return;
        }
        if (!canAccess(p, r)) {
            forget(p.getUniqueId());
            p.sendMessage(colored(cfg("messages.no-permission")));
            return;
        }
        chatLastOpen.put(p.getUniqueId(), System.currentTimeMillis());
        chatReport.put(p.getUniqueId(), id);

        Menus m = plugin.getMenus();
        List<DialogBody> body = new ArrayList<>();
        List<String> lines = plugin.getReportManager().getChatLines(id);
        if (lines.isEmpty()) {
            body.add(DialogBody.plainMessage(
                    m.comp("chat-menu.empty-history", "(Aún no hay mensajes. Escribe el primero.)", NamedTextColor.GRAY)));
        } else {
            // 0 en config.yml significa "sin limite": se muestran todas.
            int max = plugin.intOr("chat-max-lines", 10);
            int from = max > 0 ? Math.max(0, lines.size() - max) : 0;
            for (int i = from; i < lines.size(); i++) {
                body.add(DialogBody.plainMessage(Component.text(lines.get(i)).color(NamedTextColor.WHITE)));
            }
        }

        DialogBase base = DialogBase.builder(m.comp("chat-menu.title", "Chat reporte", ACCENT)
                        .append(Component.text(" #" + id).color(NamedTextColor.GRAY)))
                .body(body)
                .inputs(List.of(DialogInput.text("msg",
                                m.comp("chat-menu.input-msg", "Tu mensaje", NamedTextColor.GOLD))
                        .maxLength(plugin.limit("limits.chat-message-max-length", 120))
                        .width(plugin.limit("limits.input-width", 310))
                        .build()))
                .canCloseWithEscape(true)
                .build();
        p.showDialog(Dialog.create(b -> b.empty().base(base)
                .type(DialogType.multiAction(List.of(infoButton(id), sendButton(id), closeButton()), null, 3))));
    }

    private ActionButton infoButton(int id) {
        Menus m = plugin.getMenus();
        Report r = plugin.getReportManager().getById(id);

        String lineRaw = Menus.stripMarkup(m.raw("chat-menu.aviso-line",
                "Desconectarse del servidor borra el registro de mensajes"));
        String expireRaw = null;
        String timeFmt = null;
        if (r != null && plugin.getReportManager().getRemainingSeconds(r) > 0) {
            expireRaw = Menus.stripMarkup(m.raw("chat-menu.aviso-expire", "Cierre automático: "));
            timeFmt = Lang.fmtRemaining(plugin.getReportManager().getRemainingSeconds(r) * 1000L);
        }

        List<String> infoLines = Menus.wrapText(Menus.stripCodes(lineRaw), 190);
        int maxPx = 0;
        for (String part : infoLines) maxPx = Math.max(maxPx, Menus.textWidth(part));
        if (expireRaw != null) {
            maxPx = Math.max(maxPx, Menus.textWidth(Menus.stripCodes(expireRaw) + timeFmt));
        }

        String titleRaw = m.raw("chat-menu.aviso-title", "Aviso");
        maxPx = Math.max(maxPx, Menus.textWidth(Menus.stripCodes(titleRaw)));
        Component title = titleRaw.contains("<center>")
                ? Menus.buildCentered(titleRaw, NamedTextColor.RED, maxPx)
                : Menus.build(Menus.stripMarkup(titleRaw), NamedTextColor.RED);

        Component tooltip = title
                .append(Component.newline())
                .append(Menus.buildLines(infoLines, NamedTextColor.GRAY));
        if (expireRaw != null) {
            tooltip = tooltip
                    .append(Component.newline())
                    .append(Menus.build(expireRaw, NamedTextColor.GRAY))
                    .append(Component.text(timeFmt).color(NamedTextColor.GOLD));
        }
        return ActionButton.create(
                m.comp("chat-menu.info-symbol", "❗", NamedTextColor.YELLOW),
                tooltip,
                30,
                null);
    }

    private ActionButton sendButton(int id) {
        Menus m = plugin.getMenus();
        return ActionButton.create(
                Component.text("📤 ").append(m.comp("chat-menu.send", "Enviar", NamedTextColor.GREEN)),
                Component.text("Envía el mensaje al otro participante").color(NamedTextColor.GRAY),
                200,
                DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player pl)) return;
                    String msg = response.getText("msg");
                    if (msg == null || msg.trim().isEmpty()) {
                        open(pl, id);
                        return;
                    }
                    Report r = plugin.getReportManager().getById(id);
                    if (!canAccess(pl, r)) {
                        forget(pl.getUniqueId());
                        pl.sendMessage(colored(cfg("messages.report-not-found")));
                        return;
                    }
                    String senderLabel = senderLabel(r, pl);
                    if (!plugin.getReportManager().addChatMessage(id, senderLabel, msg.trim())) {
                        pl.sendMessage(colored(cfg("messages.chat-no-staff")));
                        return;
                    }
                    pl.sendMessage(colored(cfg("messages.chat-sent")));
                    open(pl, id);
                    refreshOther(r, pl, msg.trim());
                }, opts()));
    }

    private ActionButton closeButton() {
        Menus m = plugin.getMenus();
        // Sin acción: en vanilla cualquier clic de botón ya cierra el diálogo,
        // así que la acción nula es lo correcto aquí.
        return ActionButton.create(
                Component.text("❌ ").append(m.comp("chat-menu.close", "Cerrar", NamedTextColor.RED)),
                Component.text("Cierra la ventana del chat").color(NamedTextColor.GRAY),
                200,
                null);
    }

    /** Olvida la ventana abierta de este jugador: ya no tiene chat delante. */
    public void forget(UUID uuid) {
        if (uuid == null) return;
        chatLastOpen.remove(uuid);
        chatReport.remove(uuid);
    }

    /** Si este jugador tiene ahora mismo el chat de este reporte delante. */
    private boolean isChatOpen(Player player, int reportId) {
        Integer openReport = chatReport.get(player.getUniqueId());
        Long lastOpen = chatLastOpen.get(player.getUniqueId());
        if (openReport == null || openReport != reportId || lastOpen == null) return false;
        return System.currentTimeMillis() - lastOpen <= plugin.seconds("chat-refresh-seconds", 20) * 1000L;
    }

    /** El reportero y el staff asignado, que son los dos que pueden usar el chat. */
    private void refreshOther(Report r, Player sender, String msg) {
        Player other = otherParty(r, sender);
        if (other == null || !other.isOnline()) return;
        int window = plugin.seconds("chat-refresh-seconds", 20);
        Long lastOpen = chatLastOpen.get(other.getUniqueId());
        Integer openReport = chatReport.get(other.getUniqueId());
        long now = System.currentTimeMillis();
        if (openReport != null && openReport == r.getId()
                && lastOpen != null && (now - lastOpen) <= window * 1000L) {
            open(other, r.getId());
            return;
        }
        notifyOther(r, sender, msg);
    }

    private void notifyOther(Report r, Player sender, String msg) {
        Player other = otherParty(r, sender);
        if (other == null || !other.isOnline()) return;
        String senderLabel = senderLabel(r, sender);
        other.sendMessage(Component.newline()
                .append(Component.text("[REPORTE] ").color(ACCENT).decorate(TextDecoration.BOLD))
                .append(Component.text("#" + r.getId()).color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" \u00B7 ").color(NamedTextColor.WHITE))
                .append(Component.text(senderLabel).color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text(": ").color(NamedTextColor.GRAY))
                .append(Component.text(msg).color(NamedTextColor.WHITE)));
        other.sendMessage(Component.text("» Abre el chat del reporte «")
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand("/reportechat " + r.getId())));
    }

    private Player otherParty(Report r, Player sender) {
        UUID uid = sender.getUniqueId();
        if (uid.equals(r.getReporter())) {
            return r.getAssignedStaff() == null ? null : Bukkit.getPlayer(r.getAssignedStaff());
        }
        if (r.getAssignedStaff() != null && uid.equals(r.getAssignedStaff())) {
            return Bukkit.getPlayer(r.getReporter());
        }
        return null;
    }

    private String senderLabel(Report r, Player sender) {
        if (sender.getUniqueId().equals(r.getReporter())) return sender.getName();
        return r.getAssignedStaffName() != null ? r.getAssignedStaffName() : sender.getName();
    }

    /**
     * Llega un mensaje de Discord al chat de este reporte.
     *
     * Las lineas ya estan guardadas (lo hace DiscordTickets), aqui solo se
     * avisa: si el jugador tiene el chat abierto se le refresca la ventana y si
     * no, se le manda el aviso con el comando para abrirlo.
     */
    public void onDiscordMessage(Report report, int count) {
        if (report == null || count <= 0) return;
        List<Player> targets = new ArrayList<>();
        Player reporter = Bukkit.getPlayer(report.getReporter());
        if (reporter != null) targets.add(reporter);
        Player staff = report.getAssignedStaff() == null ? null : Bukkit.getPlayer(report.getAssignedStaff());
        if (staff != null) targets.add(staff);
        for (Player player : targets) {
            if (!player.isOnline()) continue;
            if (isChatOpen(player, report.getId())) {
                open(player, report.getId());
                continue;
            }
            // Dos textos, uno para cada plural: si se metiera "(s)" en el
            // mensaje, en ingles quedaria "1 message(s)".
            String aviso = plugin.getLang().colorMsg(
                    count == 1 ? "messages.chat-discord-1" : "messages.chat-discord-n",
                    count == 1 ? "&7[REPORTE] &b#%id% &8» &fDiscord: &71 mensaje nuevo."
                            : "&7[REPORTE] &b#%id% &8» &fDiscord: &7%count% mensajes nuevos.")
                    .replace("%id%", String.valueOf(report.getId()))
                    .replace("%count%", String.valueOf(count));
            player.sendMessage(aviso);
            player.sendMessage(Component.text("» Abre el chat del reporte «")
                    .color(NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.runCommand("/reportechat " + report.getId())));
        }
    }

    private boolean canAccess(Player p, Report r) {
        if (r == null || !r.isActive() || r.getAssignedStaff() == null) return false;
        UUID uid = p.getUniqueId();
        if (uid.equals(r.getReporter())) return true;
        return p.hasPermission("easyplayerreport.staff")
                && r.getAssignedStaff() != null && uid.equals(r.getAssignedStaff());
    }

    private ClickCallback.Options opts() {
        return ClickCallback.Options.builder().build();
    }

    private String cfg(String path) {
        return plugin.getLang().msg(path, "&7");
    }

    private String colored(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}