package com.aaronjamt.minecraftdiscordplugin;

import java.nio.file.Path;

public class Config {
    // TODO: Get these into config/database files
    public final String minecraftMessageTemplate = "[<red>{server}</red> <white>|</white> <green>{minecraftUsername}</green> <white>|</white> <blue>{discordUsername}</blue>] <white>{message}</white>";
    public final String discordMessageTemplate = "[<red>DISCORD</red> <white>|</white> <green>{minecraftUsername}</green> <white>|</white> <blue>{discordUsername}</blue>] <white>{message}</white>";
    public final String noMinecraftAccountPlaceholder = "[NonCrafter]";
    public final String discordBotToken = "MTI1ODIzNzMxMTQzOTYwMTY5Ng.GofcME.fZM0NG9C5XhuuD87RQ96ERL0RS-MnCMW__P4xk";
    public final String discordBotGuild = "1257847609494863973";
    public final String discordBotChannel = "1259427811903537194";
    public final String minecraftPlayerJoinMessage = "{username} has joined the network!";
    public final String minecraftPlayerJoinUnlinkedMessage = "Welcome to the server, {username}! Click the button below and enter your link code to link your Discord and Minecraft accounts!";
    public final String minecraftNewPlayerMessage = "Welcome to the server, {username}!"; // The minecraftPlayerJoinUnlinkedMessage is swapped for this when the account is linked
    public final String minecraftPlayerLeaveMessage = "{username} has left the network!";
    public final String playerNeedsToLinkMessage = "Please link your Discord account!\nCheck the Discord server for details.\n\nLink code:\n{code}";
    public final String serverStoppedMessage = "🛑 Server has stopped!";
    public final String serverStartedMessage = "✅ Server has started!";
    public final String minecraftPrivateMessageFormat = "[<blue>{sender}</blue> <dark_gray>-></dark_gray> <green>{recipient}</green>] {message}";
    public final String discordPrivateMessageFormat = "*{sender} whispers to you:* {message}";
    public final String discordAccountAlreadyLinkedMessage = "Your Discord account is already linked to a Minecraft account! Unlink the account '{username}' first.";
    public final String discordAccountLinkedSuccessfullyMessage = "Successfully linked with Minecraft account '{username}'! You can now join the server!";
    public final String invalidLinkCodeMessage = "Invalid link code '{code}'. Please check to make sure you typed it correctly!";
    public final String discordUserLeftServerMessage = "You left the Discord server! You must be in the Discord server to access the Minecraft server.";
    public final String sqliteDatabasePath = "database.sqlite";
    public Path dataDirectoryPath;

    Config(Path dataDirectoryPath) {
        this.dataDirectoryPath = dataDirectoryPath;
        // TODO: Load settings from config file
    }

    public void reload() {
        // TODO: Reload settings from config file
    }
}
