package com.aaronjamt.minecraftdiscordplugin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PrivateMessageCommand implements SimpleCommand {
    protected final MinecraftDiscordPlugin plugin;
    private final DiscordBot discordBot;

    public PrivateMessageCommand(MinecraftDiscordPlugin plugin, DiscordBot discordBot) {
        this.plugin = plugin;
        this.discordBot = discordBot;
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
        if (!sourceName.equals(destinationName)) {
            source.sendRichMessage(plugin.minecraftPrivateMessageFormat
                    .replace("{sender}", sourceName)
                    .replace("{recipient}", destinationName)
                    .replace("{message}", message)
            );
        }

        // Check if player is online and, if so, send them the message in-game
        Optional<Player> destinationPlayerOptional = plugin.server.getPlayer(destinationAccount);
        boolean playerOnline = destinationPlayerOptional.isPresent();
        if (playerOnline) {
            Player destinationPlayer = destinationPlayerOptional.get();
            destinationPlayer.sendRichMessage(plugin.minecraftPrivateMessageFormat
                    .replace("{sender}", sourceName)
                    .replace("{recipient}", destinationName)
                    .replace("{message}", message)
            );
        }

        // Get player's linked Discord account, if they have linked their account
        String destinationDiscordID = plugin.database.getDiscordIDFor(destinationAccount);
        if (destinationDiscordID == null) return;

        // Check if player wants to receive Discord DMs while online/offline
        if (playerOnline) {
            if (!plugin.database.getOnlineDiscordDMs(destinationAccount)) return;
        } else {
            if (!plugin.database.getOfflineDiscordDMs(destinationAccount)) return;
        }

        // Send the message to the Discord account
        discordBot.sendPrivateMessage(destinationDiscordID,
                plugin.discordPrivateMessageFormat
                        .replace("{sender}", sourceName)
                        .replace("{recipient}", destinationName)
                        .replace("{message}", message)
        );

        // Update sender's reply user to the current destination (for the reply command)
        plugin.database.setMessageReplyUsername(sourceAccount, destinationUsername);

        // Update destination's reply user to the current sender (for the reply command)
        plugin.database.setMessageReplyUsername(destinationAccount, sourceName);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Only give suggestions for first argument (the username to send the message to)
        // For anything after, make no suggestions
        if (invocation.arguments().length > 1) return List.of();

        // TODO: Populate with actual list of players
        // TODO: Populate with list of offline players that allow Discord DMs
        return List.of();
    }
}

// Specifically handles the "reply" command (where the destination username is the
// last person you messaged, and the entirety of the argument list is the message)
class ReplyCommand extends PrivateMessageCommand {
    public ReplyCommand(MinecraftDiscordPlugin plugin, DiscordBot discordBot) {
        super(plugin, discordBot);
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