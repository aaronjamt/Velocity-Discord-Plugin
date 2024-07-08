package com.aaronjamt.minecraftdiscordplugin;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class Config {
    public Path dataDirectoryPath;
    public String discordBotToken;
    public String discordBotGuild;
    public String discordBotChannel;
    public String sqliteDatabasePath;
    public String minecraftMessageTemplate;
    public String discordMessageTemplate;
    public String noMinecraftAccountPlaceholder;
    public String minecraftPlayerJoinMessage;
    public String minecraftPlayerJoinUnlinkedMessage;
    public String minecraftPlayerSwitchServersMessage;
    public String minecraftNewPlayerMessage;
    public String minecraftPlayerLeaveMessage;
    public String playerNeedsToLinkMessage;
    public String serverStoppedMessage;
    public String serverStartedMessage;
    public String minecraftPrivateMessageFormat;
    public String discordPrivateMessageFormat;
    public String discordAccountAlreadyLinkedMessage;
    public String discordAccountLinkedSuccessfullyMessage;
    public String invalidLinkCodeMessage;
    public String discordUserLeftServerMessage;

    Config(Path dataDirectoryPath) {
        this.dataDirectoryPath = dataDirectoryPath;
        // Call reload() to load the data from disk
        reload();
    }

    // Loads the data from disk
    // Since it overwrites all existing data, it's also suitable as a reload method, and hence is named as such.
    public void reload() {
        File configFile = new File(dataDirectoryPath.toFile(), "config.toml");
        try {
            TomlParseResult parse = Toml.parse(configFile.toPath());
            // Parse Discord bot settings
            discordBotToken = parse.getString(List.of("discord", "token"));
            discordBotGuild = parse.getString(List.of("discord", "serverID"));
            discordBotChannel = parse.getString(List.of("discord", "channelID"));
            // Parse database settings
            sqliteDatabasePath = parse.getString(List.of("database", "filename"));
            // Parse messages
            // TODO: Clean up names and order
            minecraftMessageTemplate = parse.getString(List.of("messages", "minecraftMessageTemplate"));
            discordMessageTemplate = parse.getString(List.of("messages", "discordMessageTemplate"));
            noMinecraftAccountPlaceholder = parse.getString(List.of("messages", "noMinecraftAccountPlaceholder"));
            minecraftPlayerJoinMessage = parse.getString(List.of("messages", "minecraftPlayerJoinMessage"));
            minecraftPlayerJoinUnlinkedMessage = parse.getString(List.of("messages", "minecraftPlayerJoinUnlinkedMessage"));
            minecraftPlayerSwitchServersMessage = parse.getString(List.of("messages", "minecraftPlayerSwitchServersMessage"));
            minecraftNewPlayerMessage = parse.getString(List.of("messages", "minecraftNewPlayerMessage"));
            minecraftPlayerLeaveMessage = parse.getString(List.of("messages", "minecraftPlayerLeaveMessage"));
            playerNeedsToLinkMessage = parse.getString(List.of("messages", "playerNeedsToLinkMessage"));
            serverStoppedMessage = parse.getString(List.of("messages", "serverStoppedMessage"));
            serverStartedMessage = parse.getString(List.of("messages", "serverStartedMessage"));
            minecraftPrivateMessageFormat = parse.getString(List.of("messages", "minecraftPrivateMessageFormat"));
            discordPrivateMessageFormat = parse.getString(List.of("messages", "discordPrivateMessageFormat"));
            discordAccountAlreadyLinkedMessage = parse.getString(List.of("messages", "discordAccountAlreadyLinkedMessage"));
            discordAccountLinkedSuccessfullyMessage = parse.getString(List.of("messages", "discordAccountLinkedSuccessfullyMessage"));
            invalidLinkCodeMessage = parse.getString(List.of("messages", "invalidLinkCodeMessage"));
            discordUserLeftServerMessage = parse.getString(List.of("messages", "discordUserLeftServerMessage"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
