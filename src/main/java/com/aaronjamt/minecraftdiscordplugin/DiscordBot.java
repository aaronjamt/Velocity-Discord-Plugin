package com.aaronjamt.minecraftdiscordplugin;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.velocitypowered.api.proxy.Player;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
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
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class DiscordBot extends ListenerAdapter {
    private final MinecraftDiscordPlugin plugin;
    private final Logger logger;
    private final JDA jda;
    private final Config config;
    private TextChannel chatChannel;
    private String chatWebhookUrl;
    private Guild guild;

    private Consumer<ChatMessage> chatMessageCallback;

    DiscordBot(MinecraftDiscordPlugin plugin, Logger logger, Config config) {
        this.plugin = plugin;
        this.logger = logger;
        this.config = config;

        logger.info("Logging into Discord...");
        jda = JDABuilder.create(config.discordBotToken, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES)
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

//            logger.info("Replacing mention: '{}'", mention);
            // Find a Discord user with that username
            boolean foundMention = false;
            for (User user : jda.getUsers()) {
                // If we find a user with that username, replace with the <@ID> format and end the loop early
//                logger.info("Found user with e='{}', n='{}', g='{}' (i='{}')...", user.getEffectiveName(), user.getName(), user.getGlobalName(), user.getId());
                if (user.getEffectiveName().equalsIgnoreCase(mention) || user.getName().equalsIgnoreCase(mention)) {
                    logger.info("Is a match!");
                    mention = String.format("<@%s>", user.getId());
                    foundMention = true;
                    break;
                }// else logger.info("No match.");
            }
            // If we didn't find a mention, add the @ we removed before back to it
            if (!foundMention) mention = "@" + mention;

            message = message.substring(0, index - 1) + mention + message.substring(endIndex);

            // The replacement mention includes an @ so skip past the mention so that we don't try to replace it as well
            index = index + mention.length();
        }
        return message;
    }

    void chatWebhookSendMessage(String username, String avatarUrl, String embedUsername, String embedAvatarUrl, String content) {
        if (chatWebhookUrl == null || chatWebhookUrl.isEmpty()) {
            logger.error("WARNING: Attempt to send send webhook message before chatWebhookUrl set!");
            return;
        }

        WebhookEmbed embed = new WebhookEmbedBuilder()
                .setDescription(content)
                .setAuthor(new WebhookEmbed.EmbedAuthor(embedUsername, embedAvatarUrl, null))
                .build();

        webhookSendEmbed(embed, username, avatarUrl);
    }

    void webhookSendEmbed(WebhookEmbed embed, String username, String avatarUrl) {
        try (WebhookClient chatWebhook = WebhookClient.withUrl(chatWebhookUrl)) {
            chatWebhook.send(new WebhookMessageBuilder()
                    .setUsername(username)
                    .setAvatarUrl(avatarUrl)
                    .addEmbeds(embed)
                    .build()
            );
        }
    }

    // Wrapper for main webhookSendEmbed method that uses the bot's effective name and avatar
    void webhookSendEmbed(WebhookEmbed embed) {
        webhookSendEmbed(embed, jda.getSelfUser().getEffectiveName(), jda.getSelfUser().getEffectiveAvatarUrl());
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        // Get appropriate guild & channel
        guild = Objects.requireNonNull(jda.getGuildById(config.discordBotGuild));
        chatChannel = guild.getChannelById(TextChannel.class, config.discordBotChannel);

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

//            event.editButton(event.getButton().withStyle(ButtonStyle.SECONDARY)).queue();
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

            }
        }
    }



    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignore webhook messages
        if (event.isWebhookMessage() || event.getMember() == null) return;
        // Ignore messages from us
        if (event.getAuthor() == jda.getSelfUser()) return;
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
        chatChannel.sendMessageEmbeds(new EmbedBuilder()
                .setDescription(message)
                .build()
        )
                .addActionRow(
                        Button.primary("link", "Link Discord Account")
                ).queue();
    }

    public void sendAnnouncement(Color highlightColor, String message, String playerName, String playerIcon) {
        chatChannel.sendMessageEmbeds(new EmbedBuilder()
                .setColor(highlightColor.getRGB())
                .setAuthor(playerName, null, playerIcon)
                .setTimestamp(Instant.now())
                .setDescription(message)
                .build()).queue();
    }

    public void sendAnnouncement(Color highlightColor, String message) {
        chatChannel.sendMessageEmbeds(new EmbedBuilder()
                .setColor(highlightColor.getRGB())
                .setDescription(message)
                .build()).queue();
    }

    public void sendAnnouncement(String message) {
        chatChannel.sendMessageEmbeds(new EmbedBuilder()
                .setDescription(message)
                .build()).queue();
    }

    public void sendPrivateMessage(String recipient, String message) {
        guild.retrieveMember(UserSnowflake.fromId(recipient)).queue(member ->
            member.getUser().openPrivateChannel().queue((channel) ->
                    channel.sendMessage(message).queue()
            )
        );
    }

    public boolean isMemberInServer(String discordID) {
        return guild.isMember(UserSnowflake.fromId(discordID));
    }
}