package com.easyplayerreport;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class StaffNotificationsCommand implements CommandExecutor {
    private final EasyPlayerReport plugin;

    public StaffNotificationsCommand(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getLang().msg("messages.player-only", "&7Solo jugadores.")));
            return true;
        }
        if (!player.hasPermission("easyplayerreport.staff")) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getLang().msg("messages.no-permission", "&cNo tienes permiso para esto.")));
            return true;
        }
        boolean enabled = plugin.toggleStaffNotifications(player);
        String path = enabled ? "messages.staff-notifications-enabled" : "messages.staff-notifications-disabled";
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getLang().msg(path, enabled ? "&aNotificaciones de reportes activadas." : "&eNotificaciones de reportes desactivadas.")));
        return true;
    }
}
