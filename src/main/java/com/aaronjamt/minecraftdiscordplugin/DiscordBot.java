package com.aaronjamt.minecraftdiscordplugin;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.velocitypowered.api.proxy.Player;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class DiscordBot extends ListenerAdapter {
    private final MinecraftDiscordPlugin plugin;
    private final Logger logger;
    private final JDA jda;
    private final Config config;
    private Role accountLinkedRole;
    private TextChannel chatChannel;
    private TextChannel accountLinkingChannel;
    private String chatWebhookUrl;
    private Guild guild;

    private Consumer<ChatMessage> chatMessageCallback;

    DiscordBot(MinecraftDiscordPlugin plugin, Logger logger, Config config) {
        this.plugin = plugin;
        this.logger = logger;
        this.config = config;

        logger.info("Logging into Discord...");
        jda = JDABuilder.create(config.discordBotToken, GatewayIntent.DIRECT_MESSAGES,      GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES)
                .addEventListeners(this)
                .disableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .setActivity(Activity.playing("Minecraft"))
                .setEnableShutdownHook(false)
                .build();

        jda.getRestPing().queue(ping ->
                // shows ping in milliseconds
                // TODO: Log this somewhere more useful (or maybe get rid of it idk)
                logger.info("Logged in with ping: {}", ping)
        );

        try {
            jda.awaitReady();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void shutdown() {
        jda.shutdown();
    }

    public String replaceMentions(String message) {
        // TODO: Is there a better way to do this?
        // Start at the beginning
        int index = 0;
        while (true) {
            // Find the next @mention, if one exists
            index = message.indexOf("@", index) + 1;
            if (index == 0) break; // No more @mentions, we're done
            int endIndex = message.indexOf(" ", index);
            if (endIndex == -1) endIndex = message.length();
            String mention = message.substring(index, endIndex);

            // Find a Discord user with that username
            boolean foundMention = false;
            for (User user : jda.getUsers()) {
                // If we find a user with that username, replace with the <@ID> format and end the loop early
                if (user.getEffectiveName().equalsIgnoreCase(mention) || user.getName().equalsIgnoreCase(mention)) {
                    logger.info("Is a match!");
                    mention = String.format("<@%s>", user.getId());
                    foundMention = true;
                    break;
                }
            }
            // If we didn't find a mention, add the @ we removed before back to it
            if (!foundMention) mention = "@" + mention;

            message = message.substring(0, index - 1) + mention + message.substring(endIndex);

            // The replacement mention includes an @ so skip past the mention so that we don't try to replace it as well
            index = index + mention.length();
        }
        return message;
    }

    void chatWebhookSendMessage(String username, String avatarUrl, String embedUsername, String embedAvatarUrl, String embedFooterText, String embedFooterIcon, String title, String content, PlayerPlatform.Platform platform, Color highlightColor) {
        if (chatWebhookUrl == null || chatWebhookUrl.isEmpty()) {
            logger.error("WARNING: Attempt to send send webhook message before chatWebhookUrl set!");
            return;
        }

        // Since we upload the footer icon as an attachment, use an attachment:// URL here and upload with the same name later
        WebhookEmbedBuilder embedBuilder = new WebhookEmbedBuilder()
                .setDescription(content)
                .setColor(highlightColor == null ? null : highlightColor.getRGB())
                .setAuthor(embedUsername == null ? null :
                        new WebhookEmbed.EmbedAuthor(embedUsername, embedAvatarUrl, null)
                )
                .setTitle(title == null ? null :
                        new WebhookEmbed.EmbedTitle(title, null)
                )
                .setFooter(embedFooterText == null ? null :
                        new WebhookEmbed.EmbedFooter(embedFooterText, embedFooterIcon)
                );

        try (WebhookClient chatWebhook = WebhookClient.withUrl(chatWebhookUrl)) {
            WebhookMessageBuilder messageBuilder = new WebhookMessageBuilder()
                    .setUsername(username)
                    .setAvatarUrl(avatarUrl);

            messageBuilder = addFooterToWebhookMessage(messageBuilder, embedBuilder, platform);

            // Now that we've finalized the message, build & attach embed, then send it
            messageBuilder.addEmbeds(embedBuilder.build());
            chatWebhook.send(messageBuilder.build());
        }
    }

    private WebhookMessageBuilder addFooterToWebhookMessage(WebhookMessageBuilder messageBuilder, WebhookEmbedBuilder embedBuilder, PlayerPlatform.Platform platform) {
        WebhookMessageBuilder originalMessageBuilder = messageBuilder;

        if (platform != null) {
            try (InputStream platformIconInputStream = getClass().getResourceAsStream(platform.getIconPath())) {
                if (platformIconInputStream == null) throw new IOException(); // Causes us to re-send without the footer

                // Now that we've got an InputStream for the platform icon, create and add the footer

                // Attach the footer icon to the message
                messageBuilder = messageBuilder.addFile("footericon.png", platformIconInputStream);
                // Add the attached image to the footer
                embedBuilder.setFooter(new WebhookEmbed.EmbedFooter(
                        "Currently playing on " + platform, "attachment://footericon.png"
                ));
            } catch (IOException ignored) {
                // If we can't read the footer image, just return the original,
                // unmodified WebhookMessageBuilder
                return originalMessageBuilder;
            }
        }
        return messageBuilder;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        // Get appropriate guild & channel
        guild = Objects.requireNonNull(jda.getGuildById(config.discordBotGuild));
        chatChannel = guild.getChannelById(TextChannel.class, config.discordBotChannel);
        accountLinkingChannel = guild.getChannelById(TextChannel.class, config.accountLinkingChannel);

        // We can't do much without a valid Discord channel
        if (chatChannel == null) {
            logger.error("Error: No such Discord channel with ID: {}. " +
                    "Please make sure you set the ID of the channel in the config file," +
                            " and that the bot has access to it.",
                    config.discordBotChannel
            );
            throw new RuntimeException("Invalid Discord channel ID");
        }

        // Set up the webhook for the channel
        chatChannel.createWebhook("Chat Relay Webhook").queue(newWebhook -> {
            chatWebhookUrl = newWebhook.getUrl();

            // Remove any leftover webhooks we've previously created
            Objects.requireNonNull(chatChannel).retrieveWebhooks().queue(webhooks ->
                webhooks.forEach(webhook -> {
                    // We only want to delete INCOMING webhooks
                    if (webhook.getType() != WebhookType.INCOMING) return;
                    // We only want to delete webhooks we can identify the owner of
                    if (webhook.getOwner() == null) return;
                    // We DON'T want to delete the webhook we just created
                    if (webhook.getId().equals(newWebhook.getId())) return;

                    // Otherwise, if it's a webhook we created, delete it
                    if (jda.getSelfUser().getId().equals(webhook.getOwner().getId())) {
                        webhook.delete().queue();
                    }
                })
            );

            // Send the announcement that we're online
            // TODO: Is there a better way to do this? Maybe queue sent Discord messages until we're online, then just send this from the main plugin class?
            sendAnnouncement(config.serverStartedMessage);
        });

        jda.retrieveCommands().queue(commands ->
                commands.forEach(command ->
                        command.delete().queue()
                )
        );

        // Find the "account linked" role, if set
        String roleId = config.discordAccountLinkedRole.strip();
        if (!roleId.isEmpty()) {
            try {
                accountLinkedRole = jda.getRoleById(config.discordAccountLinkedRole);
                if (accountLinkedRole == null) {
                    logger.warn("No Discord role found for ID: "+roleId);
                }
            } catch (Exception ex) {
                logger.warn("Unable to find Discord account linked role: "+ex);
            }
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getComponentId().equals("link")) {
            // Create a modal for the user to enter their link code
            TextInput codeField = TextInput.create("code", "Link Code", TextInputStyle.SHORT)
                    .setPlaceholder("Enter your Minecraft Link Code here")
                    .setMinLength(6)
                    .setMaxLength(6)
                    .setPlaceholder("ABC123")
                    .setRequired(true)
                    .build();

            Modal modal = Modal.create("link", "Discord Account Linking")
                    .addComponents(ActionRow.of(codeField))
                    .build();

            event.replyModal(modal).queue();
        }
    }

    @Override
    public void onModalInteraction(@Nonnull ModalInteractionEvent event) {
        if (event.getModalId().equals("link")) {
            String userID = event.getUser().getId();
            ModalMapping linkCode = event.getValue("code");
            if (linkCode == null) {
                event.reply("Please provide a link code!").setEphemeral(true).queue();
                return;
            }
            // Convert to uppercase as all link codes are uppercase-only
            String code = linkCode.getAsString().toUpperCase();

            event.deferReply(true).queue(); // Tell the user we're working on it
            String response = plugin.database.linkDiscordAccountWithCode(userID, code);
            event.getHook().sendMessage(response).queue(); // Give actual response once done

            // Verify linking was successful
            UUID account = plugin.database.getAccountFromDiscord(userID);
            if (account != null) {
                // Remove button from message and replace text with post-linking message
                Message message = event.getMessage();
                if (message == null) {
                    // This should never happen, but not the end of the world if it does
                    logger.warn("Linking completed, but unable to find linking message to edit. Discord Snowflake ID: '{}', link code: '{}'.", userID, linkCode);
                    return;
                }
                logger.info("Removing button...");
                // Remove button
                message.editMessageComponents().queue();
                // Replace embed text
                logger.info("Updating message...");
                message.editMessageEmbeds(new EmbedBuilder()
                        .setDescription(
                                config.minecraftNewPlayerMessage.replace("{username}", plugin.database.getMinecraftNicknameFor(account))
                        )
                        .build()
                ).queue();
                logger.info("Link updates done.");

                // Give the Discord user the linked account role (if set)
                if (accountLinkedRole != null) {
                    Member member = event.getMember();
                    if (member == null) {
                        logger.warn("Tried to give newly-linked account the role, but `event.getMember()` is null?");
                    } else {
                        guild.addRoleToMember(event.getMember(), accountLinkedRole).queue();
                    }
                }
            }
        }
    }



    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignore messages from us
        if (event.getAuthor() == jda.getSelfUser()) return;
        // If the message is from a private channel, handle it separately
        if (event.getChannel().getType() == ChannelType.PRIVATE) {
            onPrivateMessageReceived(event);
            return;
        }
        // Ignore webhook messages
        if (event.isWebhookMessage() || event.getMember() == null) return;
        // Ignore messages to a different channel
        if (!event.getChannel().getId().equals(config.discordBotChannel)) return;

        logger.info("{} ({}) [@{} # {}] in {}: {}",
                event.getMember().getEffectiveName(),
                event.getAuthor().getGlobalName(),
                event.getAuthor().getName(),
                event.getAuthor().getId(),
                event.getMessage().getChannel().getName(),
                event.getMessage().getContentDisplay()
        );

        // If there's any attachments, add that information to the message
        int numAttachments = event.getMessage().getAttachments().size();
        String attachmentsMsg = "";
        if (numAttachments > 0) {
            // If there's an actual message, add a space to separate the attachments suffix from the message
            if (!event.getMessage().getContentDisplay().isEmpty()) attachmentsMsg = " ";
            attachmentsMsg += "[" + numAttachments + " attachment" + (numAttachments == 1 ? "" : "s") + "]";
        }

        chatMessageCallback.accept(new ChatMessage(
                event.getAuthor().getId(),
                event.getMessage().getContentDisplay() + attachmentsMsg,
                event.getChannel().getName(),
                true
        ));
    }

    @Override
    public void onGuildMemberRoleRemove(@NotNull GuildMemberRoleRemoveEvent event) {
        super.onGuildMemberRoleRemove(event);

        // Check if this ID corresponds to a linked Discord account for the server
        // If not, we don't need to do anything about it
        UUID minecraftID = plugin.database.getAccountFromDiscord(event.getUser().getId());
        if (minecraftID == null) return;

        // Get the Minecraft player object
        Optional<Player> player = plugin.server.getPlayer(minecraftID);
        if (player.isEmpty()) return;

        // We only care about the account linked role
        if (!event.getRoles().contains(accountLinkedRole)) return;

        // If the user lost their "account linked" role, kick them from the server
        player.get().disconnect(Component.text(config.discordUserLeftServerMessage));
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        String removedUserID = event.getUser().getId();

        // Check if this ID corresponds to a linked Discord account for the server
        // If not, we don't need to do anything about it
        UUID minecraftID = plugin.database.getAccountFromDiscord(removedUserID);
        if (minecraftID == null) return;

        // Get the Minecraft player object
        Optional<Player> player = plugin.server.getPlayer(minecraftID);
        if (player.isEmpty()) return;

        // Kick the player
        player.get().disconnect(Component.text(config.discordUserLeftServerMessage));
    }

    private void onPrivateMessageReceived(MessageReceivedEvent event) {
        User sourceUser = event.getAuthor();
        Message message = event.getMessage();
        String messageContent = message.getContentDisplay();

        // Get the UUID of the user that is sending the reply
        UUID sourceAccount = plugin.database.getAccountFromDiscord(sourceUser.getId());

        if (message.getType() == MessageType.INLINE_REPLY) {
            // Message Reference is the message that is being replied o
            MessageReference messageReference = message.getMessageReference();
            if (messageReference == null) return; // Should never be possible, since we checked the message type
            messageReference.resolve().queue(repliedMessage -> {
                // Get the ID of the message the user replied to, then find the Discord ID of its sender
                String repliedId = repliedMessage.getId();
                String discordID = plugin.database.getDiscordDMSender(repliedId);
                // Use that discord ID to find the Minecraft username the user is replying to
                UUID recipientAccount = plugin.database.getAccountFromDiscord(discordID);
                plugin.sendPrivateMessage(sourceAccount, recipientAccount, messageContent);
            });
            return;
        }

        message.reply("Reply to an existing message in order to respond!").queue();
        // TODO: Possibly allow replying to last person who messaged? Maybe alternate ways to specify destination?
    }

    void setChatMessageCallback(Consumer<ChatMessage> callback) {
        chatMessageCallback = callback;
    }

    public Member getMemberFromID(String userID) {
        Member member = guild.getMember(UserSnowflake.fromId(userID));
        if (member == null) {
            logger.error("Unable to find Discord member for ID '{}'!", userID);
            return null;
        }
        logger.info("Get member from ID {}: {} ({}) [@{} # {}]",
                userID,
                member.getEffectiveName(),
                member.getUser().getGlobalName(),
                member.getUser().getName(),
                member.getId()
        );
        return member;
    }

    public String getUsernameFromID(String userID) {
        return getMemberFromID(userID).getEffectiveName();
    }

    public String getUserIconFromID(String userID) {
        return getMemberFromID(userID).getEffectiveAvatarUrl();
    }

    public void sendLinkAnnouncement(String message) {
        accountLinkingChannel.sendMessageEmbeds(new EmbedBuilder()
                .setDescription(message)
                .build()
        )
                .addActionRow(
                        Button.primary("link", "Link Discord Account")
                ).queue();
    }

    public void sendAnnouncement(Color highlightColor, String title, String message, String playerName, String playerIcon, String footerText, String footerIcon, PlayerPlatform.Platform platform) {
        SelfUser botUser = jda.getSelfUser();
        chatWebhookSendMessage(botUser.getEffectiveName(), botUser.getAvatarUrl(), playerName, playerIcon, footerText, footerIcon, title, message, platform, highlightColor);
    }

    public void sendAnnouncement(Color highlightColor, String message, String playerName, String playerIcon, PlayerPlatform.Platform platform) {
        sendAnnouncement(highlightColor, message, null,  playerName, playerIcon, null, null, platform);
}

    public void sendAnnouncement(String message) {
        sendAnnouncement(null, message, null, null, null);
    }

    public void sendAnnouncementSync(String message) {
            chatChannel.sendMessageEmbeds(new EmbedBuilder()
            .setTitle(message)
            .build()).complete();
    }

    public void sendPrivateMessage(String sender, String recipient, String message) {
        // Get the name and icon for the sender to build the embed
        UUID senderAccount = plugin.database.getAccountFromDiscord(sender);
        String senderName = plugin.database.getMinecraftNicknameFor(senderAccount);
        String senderIcon = String.format(config.minecraftHeadURL, senderAccount.toString().replaceAll("-",""), senderName);
        MessageEmbed embed = new EmbedBuilder()
                .setAuthor(senderName, null, senderIcon)
                .setDescription(message)
                .setColor(Color.cyan)
                .setFooter("This is a private message.")
                .build();

        // Get the Discord member for the recipient
        guild.retrieveMember(UserSnowflake.fromId(recipient)).queue(member ->
            // Get our DMs with them
            member.getUser().openPrivateChannel().queue((channel) ->
                // Send the message to them
                channel.sendMessageEmbeds(embed).queue(sentMessage ->
                        // Add the message to the database
                        plugin.database.addDiscordDM(sentMessage.getId(), sender, recipient)
                )
            )
        );
    }

    public boolean isMemberLinkedInServer(String discordID) {
        Member discordMember = guild.getMember(UserSnowflake.fromId(discordID));
        // Check if they're in the server
        if (discordMember == null) return false;
        // Check if they have the "account linked" role
        return discordMember.getRoles().contains(accountLinkedRole);
    }
}