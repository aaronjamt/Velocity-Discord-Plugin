package com.aaronjamt.minecraftdiscordplugin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;

public class BroadcastCommand implements SimpleCommand {
    private final MinecraftDiscordPlugin plugin;
    private final Config config;

    public BroadcastCommand(MinecraftDiscordPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {

        // If a player tried to broadcast, check if they have permission to do so
        // If the console tried to broadcast, permission is automatically granted
        if (invocation.source() instanceof Player sender) {
            if (!sender.hasPermission("chat.broadcast")) return;
        }

        // Get the message to broadcast
        String message = String.join(" ", invocation.arguments());
        plugin.sendMessageToAll(config.broadcastMessageFormat.replace("{message}", message));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Return empty list (i.e. no suggestions) because it's just a freeform message
        return List.of();
    }
}
