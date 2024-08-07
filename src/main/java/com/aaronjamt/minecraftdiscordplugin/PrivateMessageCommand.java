package com.aaronjamt.minecraftdiscordplugin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.*;

public class PrivateMessageCommand implements SimpleCommand {
    protected final MinecraftDiscordPlugin plugin;
    private final Config config;

    public PrivateMessageCommand(MinecraftDiscordPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        // Make sure the source is a player and, if so, cast to a Player class
        if (!(invocation.source() instanceof Player source)) {
            invocation.source().sendPlainMessage("Only players can send direct/private messages!");
            return;
        }

        // Parse arguments to get the destination username and message
        String[] argv = invocation.arguments();
        String destinationUsername = argv[0];
        String message = String.join(" ", Arrays.copyOfRange(argv, 1, argv.length));

        sendMessage(source, destinationUsername, message);
    }

    protected void sendMessage(Player source, String destinationUsername, String message) {
        // Don't send empty messages
        if (message.isEmpty()) return;

        UUID sourceAccount = source.getUniqueId();
        UUID destinationAccount = plugin.database.getMinecraftIDFromNickname(destinationUsername);
        if (destinationAccount == null) {
            source.sendPlainMessage("No such player!");
            return;
        }

        // Get nickname for each user
        String sourceName = plugin.database.getMinecraftNicknameFor(sourceAccount);
        String destinationName = plugin.database.getMinecraftNicknameFor(destinationAccount);

        // Send message to source user, if not the same as the destination user
        // This prevents sending the message twice if a user DMs themselves
        if (!sourceName.equals(destinationName)) {
            source.sendRichMessage(config.minecraftPrivateMessageFormat
                    .replace("{sender}", sourceName)
                    .replace("{recipient}", destinationName)
                    .replace("{message}", message)
            );
        }

        // Update sender's reply user to the current destination (for the reply command)
        plugin.database.setMessageReplyUsername(sourceAccount, destinationUsername);

        // Update destination's reply user to the current sender (for the reply command)
        plugin.database.setMessageReplyUsername(destinationAccount, sourceName);

        // Send the message to the destination
        plugin.sendPrivateMessage(sourceAccount, destinationAccount, message);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Only give suggestions for first argument (the username to send the message to)
        // For anything after, make no suggestions
        if (invocation.arguments().length > 1) return List.of();

        // Get users that allow being messaged via Discord while offline
        List<String> availableUsers = plugin.database.getAllUsersWithOfflineMessaging();
        // Add all users that are currently online, if they aren't already in the list
        plugin.server.getAllPlayers().forEach(player -> {
            String username = player.getUsername();
            if (!availableUsers.contains(username))
                availableUsers.add(username);
        });
        // Sort the list alphabetically
        availableUsers.sort(String::compareToIgnoreCase);

        return availableUsers;
    }
}

// Specifically handles the "reply" command (where the destination username is the
// last person you messaged, and the entirety of the argument list is the message)
class ReplyCommand extends PrivateMessageCommand {
    public ReplyCommand(MinecraftDiscordPlugin plugin, Config config) {
        super(plugin, config);
    }

    @Override
    public void execute(Invocation invocation) {
        // Make sure the source is a player and, if so, cast to a Player class
        if (!(invocation.source() instanceof Player source)) {
            invocation.source().sendPlainMessage("Only players can send direct/private messages!");
            return;
        }

        // Find last messaged user to reply to
        String destinationUsername = plugin.database.getMessageReplyUsername(source.getUniqueId());
        if (destinationUsername == null) {
            invocation.source().sendPlainMessage("You haven't messaged anyone yet! Send someone a message with /msg first.");
            return;
        }

        // All the arguments are part of the message
        String message = String.join(" ", invocation.arguments());

        // Send the message using the method in the outer PrivateMessageCommand class
        sendMessage(source, destinationUsername, message);
    }
}