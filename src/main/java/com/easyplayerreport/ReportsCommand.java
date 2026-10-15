package com.easyplayerreport;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReportsCommand implements CommandExecutor {
    private final EasyPlayerReport plugin;

    public ReportsCommand(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getLang().colorMsg("messages.player-only", "&7Solo jugadores."));
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("easyplayerreport.staff")) {
            p.sendMessage(plugin.getLang().colorMsg("messages.no-permission", "&cNo tienes permiso para esto."));
            return true;
        }
        plugin.getStaffDialog().open(p);
        return true;
    }
}