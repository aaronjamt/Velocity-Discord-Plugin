package com.aaronjamt.minecraftdiscordplugin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.*;

public class ConfigurationCommand implements SimpleCommand {
    private final MinecraftDiscordPlugin plugin;

    private final List<String> configOptions = List.of("discordDMsWhenOnline", "discordDMsWhenOffline", "deathAlertDelay");
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
            StringBuilder helpMessage = new StringBuilder("Usage: /discord <option> [value]\n Valid options:");
            configOptions.forEach(option -> helpMessage.append(" ").append(option).append(","));
            invocation.source().sendPlainMessage(helpMessage.substring(0, helpMessage.length()-1));
            return;
        }

        UUID player = targetPlayer.getUniqueId();

        if (booleanOptions.contains(argv[0])) {
            int value = -1;
            if (argv.length > 1) {
                if (truthy.contains(argv[1].toLowerCase())) value = 1;
                else if (falsey.contains(argv[1].toLowerCase())) value = 0;
                else {
                    // It's not a valid boolean
                    invocation.source().sendPlainMessage("'" + argv[1] + "' is not a valid value! " + argv[0] + " is a true/false setting.");
                    return;
                }
            }
            switch (argv[0]) {
                case "discordDMsWhenOnline" -> {
                    // If there wasn't a new value to set, just get the current value and reply with the status
                    if (value == -1) {
                        value = plugin.database.getOnlineDiscordDMs(player) ? 1 : 0;
                    } else {
                        plugin.database.setOnlineDiscordDMs(player, value == 1);
                    }
                    invocation.source().sendRichMessage(
                            "<gold>You will " +
                                    (value == 1 ? "" : "<red>not</red> ") +
                                    "receive Discord DMs while you're online.</gold>"
                    );
                }
                case "discordDMsWhenOffline" -> {
                    // If there wasn't a new value to set, just get the current value and reply with the status
                    if (value == -1) {
                        value = plugin.database.getOfflineDiscordDMs(player) ? 1 : 0;
                    } else {
                        plugin.database.setOfflineDiscordDMs(player, value == 1);
                    }
                    invocation.source().sendRichMessage(
                            "<gold>You will " +
                                    (value == 1 ? "" : "<red>not</red> ") +
                                    "receive Discord DMs while you're away.</gold>"
                    );
                }
            }
        } else if (Objects.equals(argv[0], "deathAlertDelay")) {
            // Get the current value
            double delaySeconds = plugin.database.getDeathAlertDelay(player);

            if (argv.length > 1) {
                // Change the value
                try {
                    delaySeconds = Double.parseDouble(argv[1]);
                    plugin.database.setDeathAlertDelay(player, delaySeconds);
                } catch (NumberFormatException ignored) {
                    // It's not a valid float
                    invocation.source().sendPlainMessage("'" + argv[1] + "' is not a valid value! " + argv[0] + " is a numerical setting.");
                    return;
                }
            }

            // Show the current/new value
            if (delaySeconds > 0)
                invocation.source().sendRichMessage(
                        "<gold>You will be notified if you don't respawn for " +
                                "<red>" + delaySeconds + "</red> " +
                                "seconds after you die.</gold>"
                );
            else {
                invocation.source().sendRichMessage(
                        "<gold>You will <red>not</red> be notified if you don't " +
                                "respawn after you die.</gold>"
                );
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
                } else if (invocation.arguments()[0].equals("deathAlertDelay")) {
                    return List.of("0","60","120","150","180","240");
                }// Success
                break;
            // etc
        }
        invocation.source().sendPlainMessage("yeet");

        // Return empty list by default (i.e. no suggestions)
        return List.of();
    }
}
