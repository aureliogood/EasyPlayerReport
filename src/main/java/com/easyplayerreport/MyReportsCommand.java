package com.easyplayerreport;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MyReportsCommand implements CommandExecutor {
    private final EasyPlayerReport plugin;

    public MyReportsCommand(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getLang().colorMsg("messages.player-only", "&7Solo jugadores."));
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("easyplayerreport.use")) {
            p.sendMessage(plugin.getLang().colorMsg("messages.no-permission", "&cNo tienes permiso para esto."));
            return true;
        }
        plugin.getMyReportsDialog().open(p);
        return true;
    }
}