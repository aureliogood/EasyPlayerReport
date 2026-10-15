package com.easyplayerreport;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReportChatCommand implements CommandExecutor {
    private final EasyPlayerReport plugin;

    public ReportChatCommand(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getLang().colorMsg("messages.player-only", "&7Solo jugadores."));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(plugin.getLang().colorMsg("messages.reportchat-usage", "&eUso: /reportechat <id>"));
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getLang().colorMsg("messages.reportchat-usage", "&eUso: /reportechat <id>"));
            return true;
        }
        plugin.getReportChatDialog().open((Player) sender, id);
        return true;
    }
}