package com.aaronjamt.minecraftdiscordplugin;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Plugin(
        id = "minecraftdiscordplugin",
        name = "MinecraftDiscordPlugin",
        version = BuildConstants.VERSION,
        authors = {"Aaronjamt"}
)
public class MinecraftDiscordPlugin  {
    @Inject
    final ProxyServer server;
    final Logger logger;
    final Config config;
    private final DiscordBot discordBot;
    final SQLiteDatabaseConnector database;

    @Inject
    public MinecraftDiscordPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;

        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.error("Unable to create data directory ({}):", dataDirectory.toFile().getAbsolutePath());
            throw new RuntimeException(e);
        }
        // TODO: Config file in dataDirectory
        this.config = new Config(dataDirectory);

        // Set up Discord bot
        discordBot = new DiscordBot(this, logger, config);
        discordBot.setChatMessageCallback(this::sendChatMessage);

        // Register commands
        CommandManager commandManager = server.getCommandManager();

            commandManager.register(
                commandManager.metaBuilder("msg")
                        .aliases("tell")
                        .aliases("dm")
                        .aliases("pm")
                        .aliases("m")
                        .plugin(this)
                        .build(),
                new PrivateMessageCommand(this, discordBot, config)
        );
        commandManager.register(
                commandManager.metaBuilder("r")
                        .aliases("reply")
                        .plugin(this)
                        .build(),
                new ReplyCommand(this, discordBot, config)
        );
        commandManager.register(
                commandManager.metaBuilder("discord")
                        .aliases("disc")
                        .aliases("d")
                        .plugin(this)
                        .build(),
                new ConfigurationCommand(this)
        );

        // Set up database
        try {
            this.database = new SQLiteDatabaseConnector(logger, config);
        } catch (SQLException e) {
            server.shutdown();
                throw new RuntimeException(e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // Send announcement synchronously so that we can make sure it sends before completely shutting down
        this.discordBot.sendAnnouncementSync(config.serverStoppedMessage);
        this.discordBot.shutdown();
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        // Reload the config file
        config.reload();
    }

    @Subscribe
    public void onUserLoginEvent(LoginEvent event) {
        // Check if the player is allowed to connect (i.e. whether they've linked the Discord account)
        Player player = event.getPlayer();
        String linkCode = database.checkAllowedToConnect(player.getUsername(), player.getUniqueId().toString());
        if (linkCode != null) {
            // Since we got a link code, they are not allowed to connect. Kick them and provide the link code.
            event.setResult(ResultedEvent.ComponentResult.denied(
                    Component.text(config.playerNeedsToLinkMessage.replace("{code}", linkCode))
            ));

            logger.info("Sending announcement to link...");

            // Post a message to the Discord server announcing that they attempted to join, with a button for easy linking
            discordBot.sendLinkAnnouncement(config.minecraftPlayerJoinUnlinkedMessage.replace("{username}", event.getPlayer().getUsername()));
            return;
        }

        // If they're in the database, make sure they're still a member of the Discord server
        String discordID = database.getDiscordIDFor(player.getUniqueId());
        if (!discordBot.isMemberInServer(discordID)) {
            // Kick them with the appropriate message
            event.setResult(ResultedEvent.ComponentResult.denied(Component.text(config.discordUserLeftServerMessage)));
        }
    }

    @Subscribe
    public void onConnect(ServerConnectedEvent event) {
        // Send a message to all players and to Discord announcing that the player joined/switched servers
        Player player = event.getPlayer();
        String mcName = player.getUsername();
        String mcIcon = String.format("https://heads.discordsrv.com/head.png?texture=&uuid=%s&name=%s&overlay", player.getUniqueId().toString().replaceAll("-", ""), mcName);
        String message;
        Color discordColor;
        // If they were already on a different server, show a "server switch" message instead
        if (event.getPreviousServer().isPresent()) {
            message = config.minecraftPlayerSwitchServersMessage
                    .replace("{username}", mcName)
                    .replace("{new_server}", event.getServer().getServerInfo().getName())
                    .replace("{old_server}", event.getPreviousServer().get().getServerInfo().getName());
            discordColor = Color.blue;
        } else {
            message = config.minecraftPlayerJoinMessage.replace("{username}", mcName);
            discordColor = Color.green;
        }
        sendMessageToAll(message);

        discordBot.sendAnnouncement(discordColor, message, mcName, mcIcon);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // If the player wasn't connected yet, don't send a disconnect announcement
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN)
            return;

        // Send a message to all players and to Discord announcing that the player left
        Player player = event.getPlayer();
        String mcName = player.getUsername();
        String mcIcon = String.format("https://heads.discordsrv.com/head.png?texture=&uuid=%s&name=%s&overlay", player.getUniqueId().toString().replaceAll("-",""), mcName);
        String message = config.minecraftPlayerLeaveMessage.replace("{username}", mcName);
        sendMessageToAll(message);

        discordBot.sendAnnouncement(Color.red, message, mcName, mcIcon);
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String playerName = player.getUsername();
        String playerUuid = player.getUniqueId().toString();

        String serverName = "no server";
        Optional<ServerConnection> server = player.getCurrentServer();
        if (server.isPresent()) {
            serverName = server.get().getServerInfo().getName();
        }

        // Send message to all Minecraft clients, but not the backend server(s)
        sendChatMessage(new ChatMessage(playerUuid, message, serverName, false));
        event.setResult(PlayerChatEvent.ChatResult.denied());

        // Get player head URL
        String mcIcon = String.format("https://heads.discordsrv.com/head.png?texture=%s&uuid=%s&name=%s&overlay", "", playerUuid.replaceAll("-",""), playerName);

        // Get linked Discord username and icon

        String discordUser = database.getDiscordIDFor(player.getUniqueId());
        String discordName = discordBot.getUsernameFromID(discordUser);
        String discordIcon = discordBot.getUserIconFromID(discordUser);

        // Replace @mentions with <@123456789012345678> mentions
        // TODO: This should probably have a config option and/or be configurable per-user and/or per-Discord-account
        message = discordBot.replaceMentions(message);

        // Send message to Discord
        discordBot.chatWebhookSendMessage(discordName, discordIcon, playerName, mcIcon, message);
    }

    void sendChatMessage(ChatMessage message) {
        String mcName;
        String discName;

        if (message.isDiscordMessage) {
            // If it's coming from Discord, treat the "user" field as a Discord account ID
            discName = discordBot.getUsernameFromID(message.user);

            // Look for a linked Minecraft account
            UUID mcUUID = database.getUUIDFromName(message.user);
            if (mcUUID != null) {
                mcName = database.getMinecraftNicknameFor(mcUUID);
                if (mcName == null) {
                    // We should never get here, but if we do, check if a player with this UUID is currently online
                    Optional<Player> potentialPlayer = server.getPlayer(mcUUID);
                    if (potentialPlayer.isPresent()) {
                        // Since they're online, add their username to the HashMap and send the message successfully, but still log a warning in the console
                        mcName = potentialPlayer.get().getUsername();
                        database.updateMinecraftUsername(mcUUID, mcName);
                        logger.warn("WARNING: Message '{}' sent by Discord user with linked Minecraft account (UUID '{}'), but no Minecraft username was found in the database! However, the player is online with username '{}', so was able to use that. This should never happen!", message.message, message.user, mcName);
                    } else {
                        logger.error("ERROR: Message '{}' sent by Discord user with linked Minecraft account (UUID '{}'), but no Minecraft username was found!", message.message, message.user);
                    }
                    return;
                }
            } else {
                mcName = config.noMinecraftAccountPlaceholder;
            }
        } else {
            // If it's coming from Minecraft, treat the "user" field as a Minecraft account UUID
            UUID mcUUID = UUID.fromString(message.user);
            Optional<Player> potentialPlayer = server.getPlayer(mcUUID);
            if (potentialPlayer.isEmpty()) {
                // We should never get here
                logger.error("ERROR: Message '{}' sent by Minecraft player with UUID '{}', but no such player is online!", message.message, message.user);
                return;
            } else {
                mcName = potentialPlayer.get().getUsername();
            }

            discName = discordBot.getUsernameFromID(database.getDiscordIDFor(mcUUID));
        }

        String finalMessage;
        if (message.isDiscordMessage)
            finalMessage = config.discordMessageTemplate;
        else
            finalMessage = config.minecraftMessageTemplate.replace("{server}", message.server);

        finalMessage = finalMessage
                .replace("{minecraftUsername}", mcName)
                .replace("{discordUsername}", discName)
                .replace("{message}", message.message);

        sendMessageToAll(finalMessage);
    }

    void sendMessageToAll(String message) {
        for (Player player : server.getAllPlayers()) {
            player.sendRichMessage(message);
        }
    }
}

class ChatMessage {
    public final String user;
    public final String message;
    public final String server;
    public final boolean isDiscordMessage;

    ChatMessage(String user, String message, String server, boolean isDiscordMessage) {
        this.user = user;
        this.message = message;
        this.server = server;
        this.isDiscordMessage = isDiscordMessage;
    }
}