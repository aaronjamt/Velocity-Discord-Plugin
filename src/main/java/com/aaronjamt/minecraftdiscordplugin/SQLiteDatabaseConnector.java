package com.aaronjamt.minecraftdiscordplugin;

import org.slf4j.Logger;

import java.io.File;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.sqlite.SQLiteDataSource;

import javax.annotation.Nonnull;

public class SQLiteDatabaseConnector {
    private final Logger logger;
    private final Config config;
    private final Connection connection;

    private final SecureRandom random = new SecureRandom();

    enum DatabaseColumns {
        minecraftUUID,
        minecraftUser,
        minecraftName,
        discordId,
        onlineDiscordDMs,
        offlineDiscordDMs,
        msgReplyUser
    }

    SQLiteDatabaseConnector(Logger logger, Config config) throws SQLException {
        this.logger = logger;
        this.config = config;

        // Gets the sqliteDatabasePath as a child of the dataDirectoryPath
        File databaseFile = new File(config.dataDirectoryPath.toFile(), config.sqliteDatabasePath);

        final SQLiteDataSource dc = new SQLiteDataSource();
        dc.setUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        connection = dc.getConnection();

        // Create the database tables if they don't exist
        Statement statement = connection.createStatement();

        statement.execute(
              "CREATE TABLE IF NOT EXISTS accounts ("
                + "minecraftUUID TEXT PRIMARY KEY," // Minecraft UUID
                + "minecraftUser TEXT,"             // Account username
                + "minecraftName TEXT,"             // Account nickname
                + "discordId TEXT,"                 // Discord Snowflake ID
                // User preference flags
                + "onlineDiscordDMs INTEGER,"       // Whether to send Discord DMs for private messages while the user is online
                + "offlineDiscordDMs INTEGER,"      // Whether to send Discord DMs for private messages while the user is offline
                + "msgReplyUser TEXT"               // The username to reply to for /r, /reply
                + ");"
        );

        statement.execute(
              "CREATE UNIQUE INDEX IF NOT EXISTS idx_minecraftUser "
                + "ON accounts(minecraftUser);"
        );

        statement.execute(
              "CREATE UNIQUE INDEX IF NOT EXISTS idx_minecraftName "
                + "ON accounts(minecraftName);"
        );

        statement.execute(
              "CREATE UNIQUE INDEX IF NOT EXISTS idx_discordId "
                + "ON accounts(discordId);"
        );

        statement.execute(
              "CREATE TABLE IF NOT EXISTS discordDMs ("
                + "messageID TEXT PRIMARY KEY,"
                + "senderID TEXT,"
                + "recipientID TEXT"
                + ");"
        );
    }

    // Checks the database to make sure the user is allowed to connect.
    // If they are, returns null
    // If not, returns a link code they must enter to link their account
    // If the user is not in the database, adds them for the account linking process
    String checkAllowedToConnect(String minecraftUser, String minecraftUUID) {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT discordId FROM accounts WHERE minecraftUser = ? AND minecraftUUID = ?");

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
                        "INSERT INTO accounts (minecraftUser, minecraftName, minecraftUUID) " +
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
            preparedStatement = connection.prepareStatement("UPDATE accounts SET discordId = ? WHERE minecraftUser = ? AND minecraftUUID = ?");
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
            return config.discordAccountAlreadyLinkedMessage.replace("{username}", getMinecraftNicknameFor(account));
        }

        // Check if the link code is valid
        if (getAccountFromDiscord("LINK "+linkCode) == null) {
            return config.invalidLinkCodeMessage.replace("{code}", linkCode);
        }

        // Link the new account
        updateColumnFor(DatabaseColumns.discordId, "LINK " + linkCode, DatabaseColumns.discordId, discordId);

        // Get the username of the Minecraft account we've linked to
        account = getAccountFromDiscord(discordId);
        if (account != null) {
            return config.discordAccountLinkedSuccessfullyMessage.replace("{username}", getMinecraftNicknameFor(account));
        }

        // If we get here, we tried to update the account and didn't encounter any SQLException(s), yet it didn't update.
        logger.error("Unable to link Discord account to Minecraft account! Discord Snowflake ID: '{}', link code: '{}'. Didn't encounter any SQL exceptions, yet SQL 'UPDATE' didn't update the database. ", discordId, linkCode);
        return "Unknown error while linking your account. Please contact the server administrator.";
    }

    void updateColumnFor(DatabaseColumns searchColumn, Object searchValue, DatabaseColumns targetColumn, Object targetValue) {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement("UPDATE accounts SET " + targetColumn + " = ? WHERE " + searchColumn + " = ?");
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
                    "SELECT " + targetColumn + " FROM accounts WHERE " + searchColumn + " = ?;"
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

    // Below are the helper methods to perform various actions
    public List<String> getAllUsersWithOfflineMessaging() {
        List<String> result = new ArrayList<>();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT minecraftUser FROM accounts WHERE offlineDiscordDMs = 1;"
            );
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
            return result;
        } catch (SQLException e) {
            logger.error("Unable to get users with offline messaging! SQLException message: '{}'\n\tException: {}", e.getMessage(), Arrays.toString(e.getStackTrace()));
            throw new RuntimeException(e);
        }
    }

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

    // Methods for Discord DMs table
    public void addDiscordDM(@Nonnull String messageID, @Nonnull String senderID, @Nonnull String recipientID) {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    "INSERT INTO discordDMs (messageID, senderID, recipientID) " +
                            "VALUES (?, ?, ?)"
            );
            // Use the account username for both the username and display name fields
            preparedStatement.setString(1, messageID);
            preparedStatement.setString(2, senderID);
            preparedStatement.setString(3, recipientID);

            preparedStatement.execute();
        } catch (SQLException e) {
            logger.error("Unable to add Discord DM to table! Message ID='{}', sender ID='{}', recipient ID='{}'. SQLException message: '{}'\n\tException: {}", messageID, senderID, recipientID, e.getMessage(), Arrays.toString(e.getStackTrace()));
            throw new RuntimeException(e);
        }
    }

    public String getDiscordDMSender(@Nonnull String messageID) {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT senderID FROM discordDMs WHERE messageID = ?;"
            );
            // Use the account username for both the username and display name fields
            preparedStatement.setString(1, messageID);

            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet != null) {
                return resultSet.getString(1);
            }
        } catch (SQLException e) {
            logger.error("Unable to get Discord DM sender! Message ID='{}'. SQLException message: '{}'\n\tException: {}", messageID, e.getMessage(), Arrays.toString(e.getStackTrace()));
            throw new RuntimeException(e);
        }
        return null;
    }
}
