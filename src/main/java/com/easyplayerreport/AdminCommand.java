package com.easyplayerreport;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Path;

public class AdminCommand implements CommandExecutor {
    private final EasyPlayerReport plugin;

    public AdminCommand(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("easyplayerreport.admin")) {
                send(sender, "messages.no-permission");
                return true;
            }
            plugin.reloadConfig();
            plugin.getMenus().reload();
            plugin.getLang().reload();
            int seconds = plugin.getReportManager().applyAutoCloseDuration();
            plugin.getReportManager().flush(true);
            plugin.reloadDiscordClient();
            plugin.getUpdateChecker().reload();
            if (seconds > 0) {
                send(sender, "messages.admin-reloaded", "%time%", plugin.getLang().fmtHuman(seconds * 1000L));
            } else {
                send(sender, "messages.admin-reloaded-never");
            }
            DiscordConfig discord = plugin.getDiscordConfig();
            if (discord.isReady()) {
                send(sender, "messages.discord-reloaded", "%channel%", discord.channelId());
                if (discord.testOnReload()) {
                    plugin.getDiscordNotifier().getClient().sendTest();
                    send(sender, "messages.discord-test-sent");
                }
            } else if (discord.enabled()) {
                send(sender, "messages.discord-incomplete");
            } else {
                send(sender, "messages.discord-off");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player player)) {
                send(sender, "messages.player-only");
                return true;
            }
            if (!player.hasPermission("easyplayerreport.stats")) {
                send(sender, "messages.no-permission");
                return true;
            }
            plugin.getReportStatsDialog().open(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("export")) {
            if (!sender.hasPermission("easyplayerreport.export")) {
                send(sender, "messages.no-permission");
                return true;
            }
            if (args.length != 2 || (!args[1].equalsIgnoreCase("json") && !args[1].equalsIgnoreCase("csv"))) {
                send(sender, "messages.export-usage");
                return true;
            }
            try {
                Path path = plugin.getReportExporter().export(args[1]);
                send(sender, "messages.export-success", "%file%", path.toString());
            } catch (Exception exception) {
                plugin.getLogger().warning("No se pudo exportar reportes: " + exception.getMessage());
                send(sender, "messages.export-failed");
            }
            return true;
        }
        usage(sender);
        return true;
    }

    private void usage(CommandSender sender) {
        send(sender, "messages.admin-usage");
    }

    private void send(CommandSender sender, String path, String... replacements) {
        String message = plugin.getLang().msg(path, "&7");
        for (int i = 0; i + 1 < replacements.length; i += 2) message = message.replace(replacements[i], replacements[i + 1]);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
