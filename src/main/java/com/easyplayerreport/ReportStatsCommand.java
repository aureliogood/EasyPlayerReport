package com.easyplayerreport;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ReportStatsCommand implements CommandExecutor {
    private final EasyPlayerReport plugin;

    public ReportStatsCommand(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    plugin.getLang().msg("messages.player-only", "&7Solo jugadores.")));
            return true;
        }
        if (!player.hasPermission("easyplayerreport.stats")) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    plugin.getLang().msg("messages.no-permission", "&cNo tienes permiso para esto.")));
            return true;
        }
        plugin.getReportStatsDialog().open(player);
        return true;
    }
}
