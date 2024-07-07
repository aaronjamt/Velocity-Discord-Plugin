package com.aaronjamt.minecraftdiscordplugin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ConfigurationCommand implements SimpleCommand {
    private final MinecraftDiscordPlugin plugin;

    private final List<String> configOptions = List.of("discordDMsWhenOnline", "discordDMsWhenOffline");
    private final List<String> booleanOptions = List.of("discordDMsWhenOnline", "discordDMsWhenOffline");
    private final List<String> truthy = List.of("true", "enable", "yes", "on", "y", "1", "+");
    private final List<String> falsey = List.of("false", "disable", "no", "off", "n", "0", "-");

    public ConfigurationCommand(MinecraftDiscordPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] argv = invocation.arguments();
        Player targetPlayer;

        if (invocation.source() instanceof Player) {
            targetPlayer = (Player) invocation.source();
        } else {
            // If we're running from the console, the first argument is the player to configure
            Optional<Player> requestedPlayer = plugin.server.getPlayer(argv[0]);
            if (requestedPlayer.isEmpty()) {
                invocation.source().sendPlainMessage("No such player!");
                return;
            }
            targetPlayer = requestedPlayer.get();
            argv = Arrays.copyOfRange(argv, 1, argv.length);
        }

        // Check if the command is valid
        if (argv.length < 1 || !configOptions.contains(argv[0])) {
            // Help text
            StringBuilder helpMessage = new StringBuilder("Usage: /discord [command]\n Valid commands:");
            configOptions.forEach(option -> {
                helpMessage.append(" ").append(option).append(",");
        });
            invocation.source().sendPlainMessage(helpMessage.substring(0, helpMessage.length()-1));
            return;
        }

        UUID player = targetPlayer.getUniqueId();

        if (booleanOptions.contains(argv[0])) {
            boolean value;
            if (truthy.contains(argv[1].toLowerCase())) value = true;
            else if (falsey.contains(argv[1].toLowerCase())) value = false;
            else {
                // It's not a valid boolean
                invocation.source().sendPlainMessage("'" + argv[1] + "' is not a valid value! " + argv[0] + " is a true/false setting.");
                return;
            }
            switch (argv[0]) {
                case "discordDMsWhenOnline" -> {
                    plugin.database.setOnlineDiscordDMs(player, value);
                    invocation.source().sendPlainMessage(
                            "You will " +
                                    (value ? "" : "not ") +
                                    "receive Discord DMs when players /msg you while you're on the server."
                    );
                }
                case "discordDMsWhenOffline" -> {
                    plugin.database.setOfflineDiscordDMs(player, value);
                    invocation.source().sendPlainMessage(
                            "You will " +
                                    (value ? "" : "not ") +
                                    "receive Discord DMs when players /msg you while you're away from the server."
                    );
                }
            }
        } /* add else-if's here */ else {
            invocation.source().sendPlainMessage("Unknown configuration option '" + argv[0] + "'. Type '/discord help' for valid options.");
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        switch (invocation.arguments().length) {
            case 0:
            case 1:
                // First argument
                return configOptions;
            case 2:
                // Second argument
                if (booleanOptions.contains(invocation.arguments()[0])) {
                    // It's a boolean command, suggest "yes" and "no"
                    return List.of("yes", "no");
                }// Success
                break;
            // etc
        }
        invocation.source().sendPlainMessage("yeet");

        // Return empty list by default (i.e. no suggestions)
        return List.of();
    }
}
