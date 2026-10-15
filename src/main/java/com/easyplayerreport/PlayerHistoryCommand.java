package com.easyplayerreport;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PlayerHistoryCommand implements CommandExecutor {
    private final EasyPlayerReport plugin;

    public PlayerHistoryCommand(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(colored(plugin.getLang().msg("messages.player-only", "&7Solo jugadores.")));
            return true;
        }
        if (!player.hasPermission("easyplayerreport.history")) {
            player.sendMessage(colored(plugin.getLang().msg("messages.no-permission", "&cNo tienes permiso para esto.")));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(colored(plugin.getLang().msg("messages.history-usage", "&eUso: /%command% <jugador>")
                    .replace("%command%", label)));
            return true;
        }
        plugin.getPlayerHistoryDialog().open(player, args[0]);
        return true;
    }

    private String colored(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
