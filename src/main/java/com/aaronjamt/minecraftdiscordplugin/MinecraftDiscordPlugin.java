package com.aaronjamt.minecraftdiscordplugin;

import com.google.common.io.ByteArrayDataInput;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final PlayerPlatform playerPlatform;
    private final Map<UUID, List<Long>> deathAlerts = new HashMap<>();

    public static final MinecraftChannelIdentifier CHANNEL_IDENTIFIER = MinecraftChannelIdentifier.from(Constants.COMMUNICATION_CHANNEL);

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
        discordBot.setServerMessageCallback(this::sendMessageToAll);

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
                new PrivateMessageCommand(this, config)
        );
        commandManager.register(
                commandManager.metaBuilder("r")
                        .aliases("reply")
                        .plugin(this)
                        .build(),
                new ReplyCommand(this, config)
        );
        commandManager.register(
                commandManager.metaBuilder("discord")
                        .aliases("disc")
                        .aliases("d")
                        .plugin(this)
                        .build(),
                new ConfigurationCommand(this)
        );
        commandManager.register(
                commandManager.metaBuilder("broadcast")
                        .aliases("announcement")
                        .aliases("announce")
                        .aliases("b")
                        .aliases("a")
                        .plugin(this)
                        .build(),
                new BroadcastCommand(this, config)
        );

        // Set up database
        try {
            this.database = new SQLiteDatabaseConnector(logger, config);
        } catch (SQLException e) {
            server.shutdown();
                throw new RuntimeException(e);
        }

        // Set up player platform module
        playerPlatform = new PlayerPlatform(logger);

        // Check death alerts every second
        Runnable deathAlertsRunnable = () -> {
            long timeNow = System.currentTimeMillis();

            for (Map.Entry<UUID, List<Long>> deathAlert : deathAlerts.entrySet()) {
                UUID mcUUID = deathAlert.getKey();
                long diedAt = deathAlert.getValue().get(0);
                long warnAt = deathAlert.getValue().get(1);
                if (warnAt <= timeNow) {
                    // Send the alert!
                    String discordID = database.getDiscordIDFor(mcUUID);
                    discordBot.sendDeathAlert(discordID, diedAt);

                    // Remove the alert from the map since we've sent it
                    deathAlerts.remove(deathAlert.getKey(), deathAlert.getValue());
                }
            }
        };

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(deathAlertsRunnable, 1, 1, TimeUnit.SECONDS);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Register for Bungeecord-compatible plugin messages
        server.getChannelRegistrar().register(CHANNEL_IDENTIFIER);
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
                    Component.textOfChildren(Component.text(config.playerNeedsToLinkMessage.replace("{code}", linkCode)))
            ));

            logger.info("Sending announcement to link...");

            // Post a message to the Discord server announcing that they attempted to join, with a button for easy linking
            discordBot.sendLinkAnnouncement(config.minecraftPlayerJoinUnlinkedMessage.replace("{username}", event.getPlayer().getUsername()));
            return;
        }

        // If they're in the database, make sure they're still a member of the Discord server
        String discordID = database.getDiscordIDFor(player.getUniqueId());
        if (!discordBot.isMemberLinkedInServer(discordID)) {
            // Kick them with the appropriate message
            event.setResult(ResultedEvent.ComponentResult.denied(Component.text(config.discordUserLeftServerMessage)));
        }
    }

    @Subscribe
    public void onConnect(ServerConnectedEvent event) {
        // Send a message to all players and to Discord announcing that the player joined/switched servers
        Player player = event.getPlayer();
        String mcName = player.getUsername();
        String mcIcon = String.format(config.minecraftHeadURL, player.getUniqueId().toString().replaceAll("-", ""), mcName);
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

        discordBot.sendAnnouncement(discordColor, message, mcName, mcIcon, playerPlatform.getPlayerPlatform(player));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // If the player wasn't connected yet, don't send a disconnect announcement
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN)
            return;

        // Send a message to all players and to Discord announcing that the player left
        Player player = event.getPlayer();
        String mcName = player.getUsername();
        String mcIcon = String.format(config.minecraftHeadURL, player.getUniqueId().toString().replaceAll("-",""), mcName);
        String message = config.minecraftPlayerLeaveMessage.replace("{username}", mcName);
        sendMessageToAll(message);

        discordBot.sendAnnouncement(Color.red, message, mcName, mcIcon, playerPlatform.getPlayerPlatform(player));

        // Remove them from the list of players to send death alerts to, if they're in there, as
        // otherwise they'll get a notification after they've left the game, which they probably
        // don't want
        deathAlerts.remove(player.getUniqueId());
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

        // Get Minecraft platform and associated icon
        //PlayerPlatform.Platform platform = playerPlatform.getPlayerPlatform(player);
        // Having the platform information in the footer of every single message seems overly verbose.
        // The foundation is there to allow this, but for now I'm disabling it.
        PlayerPlatform.Platform platform = null;

        // Send message to Discord
        discordBot.chatWebhookSendMessage(discordName, discordIcon, playerName, mcIcon, null, null, null, message, platform, null);
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPluginMessageFromBackend(PluginMessageEvent event) {
        logger.info("Plugin message from backend!");
        if (!(event.getSource() instanceof ServerConnection backend)) {
            return;
        }

        Player player = backend.getPlayer();
        String playerName = player.getUsername();
        String playerIcon = String.format(config.minecraftHeadURL, player.getUniqueId().toString().replaceAll("-", ""), playerName);

        ByteArrayDataInput buffer = event.dataAsDataStream();
        String eventType = buffer.readUTF();

        switch (eventType) {
            case "PlayerDeath":
                String message = buffer.readUTF();
                discordBot.sendAnnouncement(new Color(0xff7f00), message, playerName, playerIcon, null);

                double delaySeconds = database.getDeathAlertDelay(player.getUniqueId());
                if (delaySeconds <= 0) break; // 0 or negative = disabled

                long delayMillis = (long) (delaySeconds * 1000);

                long diedAtTime = System.currentTimeMillis();
                long warningTime = diedAtTime + delayMillis;

                // Add the player to the list of alerts, and store both the time they died, and the time to warn them
                deathAlerts.put(player.getUniqueId(), List.of(diedAtTime, warningTime));
                break;
            case "PlayerRespawn":
                logger.warn("Player respawned! UUID: {}", player.getUniqueId());
                // Remove the player from the list so they aren't alerted
                deathAlerts.remove(player.getUniqueId());
                break;
            case "PlayerAdvancement":
                String advancementType = buffer.readUTF();
                boolean isChallenge = buffer.readBoolean();
                String advancementTitle = buffer.readUTF();
                String advancementDescription = buffer.readUTF();

                discordBot.sendAnnouncement(isChallenge ? new Color(0x9400d3): Color.blue, advancementType, advancementTitle, playerName, playerIcon, advancementDescription, null, null);
                break;
            default:
                // Log any unknown plugin messages
                String serverName = backend.getServerInfo().getName();
                String identifier = event.getIdentifier().getId();
                String data = Arrays.toString(event.getData());

                logger.info("Got plugin message from backend! Server='{}', event='{}', player='{}', identifier='{}', data: {}", serverName, eventType, playerName, identifier, data);
        }
    }

    void sendChatMessage(ChatMessage message) {
        String mcName;
        UUID mcUUID;
        String discName;
        String discId;

        if (message.isDiscordMessage) {
            // If it's coming from Discord, treat the "user" field as a Discord account ID
            discId = message.user;
            discName = discordBot.getUsernameFromID(discId);

            // Look for a linked Minecraft account
            mcUUID = database.getAccountFromDiscord(message.user);
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
            mcUUID = UUID.fromString(message.user);
            Optional<Player> potentialPlayer = server.getPlayer(mcUUID);
            if (potentialPlayer.isEmpty()) {
                // We should never get here
                logger.error("ERROR: Message '{}' sent by Minecraft player with UUID '{}', but no such player is online!", message.message, message.user);
                return;
            } else {
                mcName = potentialPlayer.get().getUsername();
            }

            discId = database.getDiscordIDFor(mcUUID);
            discName = discordBot.getUsernameFromID(discId);
        }

        String playerMessage = message.message;
        // Prevent player from using color codes or escape sequences
        playerMessage = playerMessage.replace("\\", "\\\\").replace("<", "\\<");
        // Replace URLs with clickable links
        playerMessage = playerMessage.replaceAll(
                // URL match regex from https://stackoverflow.com/a/3809435
                "(https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b[-a-zA-Z0-9()@:%_+.~#?&/=]*)",
                // Replacement to make it clickable
                "<u><click:open_url:'$1'>$1</click><u>"
                );

        String finalMessage;
        if (message.isDiscordMessage) {
            if (message.isEditedMessage) {
                // If it's edited and there's an edited message template, use that
                if (config.discordMessageEditTemplate != null)
                    finalMessage = config.discordMessageEditTemplate;
                    // If it's edited and there's not an edited message template, don't send any message
                else
                    return;
            } else {
                finalMessage = config.discordMessageTemplate;
            }
        } else {
            finalMessage = config.minecraftMessageTemplate.replace("{server}", message.server);
        }

        finalMessage = finalMessage
                .replace("{minecraftUsername}", mcName)
                .replace("{discordUsername}", discName)
                .replace("{message}", playerMessage)
//                .replace("{minecraft_head}", new ChattableImage(logger, playerHeadUrl).toString())
//                .replace("{discord_avatar}", new ChattableImage(logger, discordAvatarUrl).toString())
        ;

        sendMessageToAll(finalMessage);
    }

    void sendMessageToAll(String message) {
        for (Player player : server.getAllPlayers()) {
            player.sendRichMessage(message);
        }
    }

    public void sendPrivateMessage(UUID sourceAccount, UUID destinationAccount, String message) {
        // Get nickname for each user
        String sourceName = database.getMinecraftNicknameFor(sourceAccount);
        String destinationName = database.getMinecraftNicknameFor(destinationAccount);

        // Check if player is online and, if so, send them the message in-game
        Optional<Player> destinationPlayerOptional = server.getPlayer(destinationAccount);
        boolean playerOnline = destinationPlayerOptional.isPresent();
        if (playerOnline) {
            Player destinationPlayer = destinationPlayerOptional.get();
            destinationPlayer.sendRichMessage(config.minecraftPrivateMessageFormat
                    .replace("{sender}", sourceName)
                    .replace("{recipient}", destinationName)
                    .replace("{message}", message)
            );
        }

        // Get linked Discord account IDs for both source and destination
        String sourceDiscordID = database.getDiscordIDFor(sourceAccount);
        if (sourceDiscordID == null) return;
        String destinationDiscordID = database.getDiscordIDFor(destinationAccount);
        if (destinationDiscordID == null) return;

        // Check if player wants to receive Discord DMs while online/offline
        if (playerOnline) {
            if (!database.getOnlineDiscordDMs(destinationAccount)) return;
        } else {
            if (!database.getOfflineDiscordDMs(destinationAccount)) return;
        }

        // Send the message to the Discord account
        discordBot.sendPrivateMessage(sourceDiscordID, destinationDiscordID, message);
    }
}

class ChatMessage {
    public final String user;
    public final String message;
    public final String server;
    public final boolean isDiscordMessage;
    public final boolean isEditedMessage;

    ChatMessage(String user, String message, String server, boolean isDiscordMessage) {
        this.user = user;
        this.message = message;
        this.server = server;
        this.isDiscordMessage = isDiscordMessage;
        this.isEditedMessage = false;
    }

    ChatMessage(String user, String message, String server, boolean isDiscordMessage, boolean isEditedMessage) {
        this.user = user;
        this.message = message;
        this.server = server;
        this.isDiscordMessage = isDiscordMessage;
        this.isEditedMessage = isEditedMessage;
    }
}