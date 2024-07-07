package com.aaronjamt.minecraftdiscordplugin;

import org.slf4j.Logger;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Arrays;
import java.util.UUID;
import org.sqlite.SQLiteDataSource;

import javax.annotation.Nonnull;

public class SQLiteDatabaseConnector {
    private final MinecraftDiscordPlugin plugin;
    private final Logger logger;
    private final Connection connection;

    private final SecureRandom random = new SecureRandom();

    // TODO: Maybe use an enum instead of a String for column arguments to make sure there isn't an injection vuln
    enum DatabaseColumns {
        minecraftUUID,
        minecraftUser,
        minecraftName,
        discordId,
        onlineDiscordDMs,
                offlineDiscordDMs,
        msgReplyUser
    }

    SQLiteDatabaseConnector(MinecraftDiscordPlugin plugin, Logger logger, String databasePath) throws SQLException {
        this.plugin = plugin;
        this.logger = logger;

        //String url = "jdbc:sqlite:"+databasePath;

        final SQLiteDataSource dc = new SQLiteDataSource();
        dc.setUrl("jdbc:sqlite:" + databasePath);
        connection = dc.getConnection();

        // Create the database table if it doesn't exist
        String sql = "CREATE TABLE IF NOT EXISTS discord ("
                + "minecraftUUID TEXT PRIMARY KEY," // Minecraft UUID
                + "minecraftUser TEXT,"             // Account username
                + "minecraftName TEXT,"             // Account nickname
                + "discordId TEXT,"                 // Discord Snowflake ID
                // User preference flags
                + "onlineDiscordDMs INTEGER,"            // Whether to send Discord DMs for private messages while the user is online
                + "offlineDiscordDMs INTEGER,"           // Whether to send Discord DMs for private messages while the user is offline
                + "msgReplyUser TEXT"               // The username to reply to for /r, /reply
                + ");"

                + "CREATE UNIQUE INDEX IF NOT EXISTS idx_minecraftUser "
                + "ON discord(minecraftUser);"

                + "CREATE UNIQUE INDEX IF NOT EXISTS idx_minecraftName "
                + "ON discord(minecraftName);"

                + "CREATE UNIQUE INDEX IF NOT EXISTS idx_discordId "
                + "ON discord(discordId);";

        Statement statement = connection.createStatement();
        statement.execute(sql);
    }

    // Checks the database to make sure the user is allowed to connect.
    // If they are, returns null
    // If not, returns a link code they must enter to link their account
    // If the user is not in the database, adds them for the account linking process
    String checkAllowedToConnect(String minecraftUser, String minecraftUUID) {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT discordId FROM discord WHERE minecraftUser = ? AND minecraftUUID = ?");

            preparedStatement.setString(1, minecraftUser);
            preparedStatement.setString(2, minecraftUUID);

            ResultSet result = preparedStatement.executeQuery();
            if (result != null && result.getString(1) != null) {
                // Check if the account is actually linked, or if the user just has a temporary link code
                //noinspection StatementWithEmptyBody
                if (result.getString(1).startsWith("LINK")) {
                    // This is actually a link code so user is not allowed to connect.
                    // We just fall through to link code generation
                } else {
                    // The user is linked so return null to indicate they're good to go
                    return null;
                }
            } else {
                // The user is not in the database, so add them and fall through to link code generation
                preparedStatement = connection.prepareStatement(
                        "INSERT INTO discord (minecraftUser, minecraftName, minecraftUUID) " +
                                "VALUES (?, ?, ?)"
                );
                // Use the account username for both the username and display name fields
                preparedStatement.setString(1, minecraftUser);
                preparedStatement.setString(2, minecraftUser);
                preparedStatement.setString(3, minecraftUUID);

                preparedStatement.execute();
            }

            // If we get here, they need a (new) link code. Generate one, add it to the database, and return it.

            // Generates a random uppercase-and-numbers string. By using a radix of 32, we use 5 bits per character,
            // which means that 30 bits results in a 6-character code.
            String linkCode = new BigInteger(30, random).toString(32).toUpperCase();

            // Add the link code to the database
            preparedStatement = connection.prepareStatement("UPDATE discord SET discordId = ? WHERE minecraftUser = ? AND minecraftUUID = ?");
            preparedStatement.setString(1, "LINK " + linkCode); // Prefix with "LINK" to identify this as a link code, rather than a Discord Snowflake ID
            preparedStatement.setString(2, minecraftUser);
            preparedStatement.setString(3, minecraftUUID);
            preparedStatement.execute();

            // Return the link code, so it can be shown to the client.
            return linkCode;
        } catch (SQLException e) {
            logger.error("Unable to verify if player is linked! Minecraft user: '{}', Minecraft UUID: '{}'. SQLException message: '{}'\n\tException: {}", minecraftUser, minecraftUUID, e.getMessage(), Arrays.toString(e.getStackTrace()));
            return "Error generating link code. Contact server administrator.";
        }
    }

    // Attempts to link accounts
    // Returns a String describing the result in a human-readable format (to be displayed to the user)
    String linkDiscordAccountWithCode(String discordId, String linkCode) {
        // Check if the Discord account is already linked
        UUID account = getAccountFromDiscord(discordId);
        if (account != null) {
            return plugin.discordAccountAlreadyLinkedMessage.replace("{username}", getMinecraftNicknameFor(account));
        }

        // Check if the link code is valid
        if (getAccountFromDiscord("LINK "+linkCode) == null) {
            return plugin.invalidLinkCodeMessage.replace("{code}", linkCode);
        }

        // Link the new account
        updateColumnFor(DatabaseColumns.discordId, "LINK " + linkCode, DatabaseColumns.discordId, discordId);

        // Get the username of the Minecraft account we've linked to
        account = getAccountFromDiscord(discordId);
        if (account != null) {
            return plugin.discordAccountLinkedSuccessfullyMessage.replace("{username}", getMinecraftNicknameFor(account));
        }

        // If we get here, we tried to update the account and didn't encounter any SQLException(s), yet it didn't update.
        logger.error("Unable to link Discord account to Minecraft account! Discord Snowflake ID: '{}', link code: '{}'. Didn't encounter any SQL exceptions, yet SQL 'UPDATE' didn't update the database. ", discordId, linkCode);
        return "Unknown error while linking your account. Please contact the server administrator.";
    }

    void updateColumnFor(DatabaseColumns searchColumn, Object searchValue, DatabaseColumns targetColumn, Object targetValue) {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement("UPDATE discord SET " + targetColumn + " = ? WHERE " + searchColumn + " = ?");
            preparedStatement.setObject(1, targetValue);
            preparedStatement.setObject(2, searchValue);
            preparedStatement.execute();
        } catch (SQLException e) {
            logger.error("Unable to get value for column '{}' given column '{}' = '{}'! SQLException message: '{}'\n\tException: {}", targetColumn, searchColumn, searchValue, e.getMessage(), Arrays.toString(e.getStackTrace()));
            throw new RuntimeException(e);
        }
    }

    Object getColumnFrom(DatabaseColumns searchColumn, Object searchValue, DatabaseColumns targetColumn) {
        try {
            // Even though we have to inject the column names directly, we still should use a PreparedStatement as it:
            // 1) Can speed up repeated executions via server-side caching
            // 2) Sanitizes column values (which are exposed to the user)
            // Column names are never loaded from user input and, even if they were to be, are passed via an Enum so that
            // we aren't vulnerable to SQL injection.
            PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT " + targetColumn + " FROM discord WHERE " + searchColumn + " = ?;"
            );
            preparedStatement.setObject(1, searchValue);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet != null) {
                return resultSet.getObject(1);
            }
            return null;
        } catch (SQLException e) {
            logger.error("Unable to get value for column '{}' given column '{}' = '{}'! SQLException message: '{}'\n\tException: {}", targetColumn, searchColumn, searchValue, e.getMessage(), Arrays.toString(e.getStackTrace()));
            throw new RuntimeException(e);
        }
    }
/*
    // Below are the helper methods to perform various actions
    public List<String> getAllUsersWithOfflineMessaging() {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT minecraftUser FROM discord WHERE offlineDiscordDMs = 1;"
            );
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet != null) {
                return resultSet.getObject(1);
            }
            return null;
        } catch (SQLException e) {
            logger.error("Unable to get value for column '{}' given column '{}' = '{}'! SQLException message: '{}'\n\tException: {}", targetColumn, searchColumn, searchValue, e.getMessage(), Arrays.toString(e.getStackTrace()));
            throw new RuntimeException(e);
        }
    }
*/
    // Methods to get a Minecraft UUID, given other information
    public UUID getAccountFromDiscord(@Nonnull String discordId) {
        String result = (String) getColumnFrom(DatabaseColumns.discordId, discordId, DatabaseColumns.minecraftUUID);
        if (result == null) return null;
        return UUID.fromString(result);
    }

    public UUID getUUIDFromName(@Nonnull String name) {
        String result = (String) getColumnFrom(DatabaseColumns.minecraftName, name, DatabaseColumns.minecraftUUID);
        if (result == null) {
            // If there's no account with that nickname, look for one with that username
            result = (String) getColumnFrom(DatabaseColumns.minecraftUser, name, DatabaseColumns.minecraftUUID);
            if (result == null) return null;
        }
        return UUID.fromString(result);
    }


    // Methods to get other information, given a Minecraft UUID
    public String getUsernameFromMinecraftUUID(@Nonnull UUID account) {
        return (String) getColumnFrom(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.minecraftUser);
    }

    public String getDiscordIDFor(@Nonnull UUID account) {
        String discordId = (String) getColumnFrom(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.discordId);
        // If the Discord ID is actually a linking code, act as if there is no associated ID
        if (discordId == null || discordId.startsWith("LINK ")) return null;
        return discordId;
    }

    public String getMinecraftNicknameFor(@Nonnull UUID account) {
        return (String) getColumnFrom(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.minecraftName);
    }

    public boolean getOfflineDiscordDMs(@Nonnull UUID account) {
        return (int) getColumnFrom(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.offlineDiscordDMs) == 1;
    }

    public boolean getOnlineDiscordDMs(@Nonnull UUID account) {
        return (int) getColumnFrom(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.onlineDiscordDMs) == 1;
    }

    public String getMessageReplyUsername(@Nonnull UUID account) {
        return (String) getColumnFrom(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.msgReplyUser);
    }

    // Methods to update data
    public void updateMinecraftUsername(@Nonnull UUID mcUUID, String mcName) {
        updateColumnFor(DatabaseColumns.minecraftUUID, mcUUID.toString(), DatabaseColumns.minecraftUser, mcName);
    }

    public UUID getMinecraftIDFromNickname(@Nonnull String minecraftName) {
        String uuidString = (String) getColumnFrom(DatabaseColumns.minecraftName, minecraftName, DatabaseColumns.minecraftUUID);
        if (uuidString == null) return null;
        return UUID.fromString(uuidString);
    }

    public boolean updateMinecraftNickname(@Nonnull UUID account, @Nonnull String nickname) {
        // Check if any other players have the requested name
        if (getColumnFrom(DatabaseColumns.minecraftUser, nickname, DatabaseColumns.minecraftUser) != null) return false;
        if (getColumnFrom(DatabaseColumns.minecraftName, nickname, DatabaseColumns.minecraftName) != null) return false;

        // Update the account nickname
        updateColumnFor(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.minecraftName, nickname);
        return true;
    }

    public void setOnlineDiscordDMs(@Nonnull UUID account, boolean value) {
        updateColumnFor(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.onlineDiscordDMs, value ? 1 : 0);
    }

    public void setOfflineDiscordDMs(@Nonnull UUID account, boolean value) {
        updateColumnFor(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.offlineDiscordDMs, value ? 1 : 0);
    }

    public void setMessageReplyUsername(@Nonnull UUID account, @Nonnull String destination) {
        updateColumnFor(DatabaseColumns.minecraftUUID, account.toString(), DatabaseColumns.msgReplyUser, destination);
        logger.info("Set message reply username for {} to {}.", account, destination);
    }
}