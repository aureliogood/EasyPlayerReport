package com.easyplayerreport;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReportCommand implements CommandExecutor {
    private final EasyPlayerReport plugin;

    public ReportCommand(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getLang().colorMsg("messages.player-only", "&7Solo jugadores."));
            return true;
        }
        Player p = (Player) sender;
        plugin.getDialog().open(p);
        return true;
    }
}