package com.aaronjamt.minecraftdiscordplugin;

import com.velocitypowered.api.proxy.Player;
import net.lax1dude.eaglercraft.v1_8.plugin.gateway_velocity.EaglerXVelocity;
import net.lax1dude.eaglercraft.v1_8.plugin.gateway_velocity.api.EaglerXVelocityAPIHelper;
import org.geysermc.api.util.BedrockPlatform;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.util.DeviceOs;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.slf4j.Logger;

import java.util.UUID;

public class PlayerPlatform {
    private final Logger logger;
    private boolean hasGeyser = false;
    private boolean hasFloodgate = false;
    private boolean hasEaglerCraft = false;

    PlayerPlatform(Logger logger) {
        this.logger = logger;

        // Check if each cross-platform plugin exists by trying to access its main class. If it throws a NoClassDefFoundError, we assume it doesn't.
        try {
            Class<GeyserApi> ignored = GeyserApi.class;
            hasGeyser = true;
            logger.info("Geyser detected, enabling Bedrock platform detection!");
        } catch (NoClassDefFoundError ignored) {}

        try {
            Class<FloodgateApi> ignored = FloodgateApi.class;
            hasFloodgate = true;
            logger.info("Floodgate detected, enabling Bedrock platform detection!");
        } catch (NoClassDefFoundError ignored) {}

    try {
            Class<EaglerXVelocity> ignored = EaglerXVelocity.class;
            hasEaglerCraft = true;
            logger.info("EaglerCraft detected, enabling Web platform detection!");
        } catch (NoClassDefFoundError ignored) {}
    }

    public Platform getPlayerPlatform(Player player) {
        UUID playerID = player.getUniqueId();

        if (hasGeyser) {
            if (GeyserApi.api().connectionByUuid(playerID) != null) {
                logger.info("Player {} is a Geyser player!", playerID);
                GeyserConnection connection = GeyserApi.api().connectionByUuid(playerID);
                if (connection != null) {
                    BedrockPlatform platform = connection.platform();
                    if (platform == BedrockPlatform.UNKNOWN) {
                        logger.warn("Player {} is a Geyser player, but was unable to find exact player platform.", playerID);
                        return Platform.BEDROCK_GENERIC;
                    } else
                        return Platform.PlatformByBedrockName(platform.toString());
                }
            } else logger.info("Player {} is not a Geyser player.", playerID);
        } else logger.info("Not checking whether player {} is a Geyser player: Geyser not installed!", playerID);

        if (hasFloodgate) {
            if (FloodgateApi.getInstance().isFloodgatePlayer(playerID)) {
                logger.info("Player {} is a Floodgate player!", playerID);
                DeviceOs platform = FloodgateApi.getInstance().getPlayer(playerID).getDeviceOs();

                if (platform == DeviceOs.UNKNOWN) {
                    logger.warn("Player {} is a Floodgate player, but was unable to find exact player platform.", playerID);
                    return Platform.BEDROCK_GENERIC;
                } else
                    return Platform.PlatformByBedrockName(platform.toString());
            } else logger.info("Player {} is not a Floodgate player.", playerID);
        } else logger.info("Not checking whether player {} is a Floodgate player: Floodgate not installed!", playerID);

        if (hasEaglerCraft) {
            if (EaglerXVelocityAPIHelper.getEaglerHandle(player) != null) {
                logger.info("Player {} is an EaglerCraft player!", playerID);
                return Platform.EAGLER;
            } else logger.info("Player {} is not a EaglerCraft player.", playerID);
        } else logger.info("Not checking whether player {} is a EaglerCraft player: EaglerCraft not installed!", playerID);

        // If the player isn't any of the above, assume they're on Java
        // Add client version and branding as well, since we can access that information
        // (EaglerCraft is stuck on 1.8.8 and Geyser always uses the latest protocol, so
        // neither is very useful, and Geyser/Floodgate isn't necessarily accurate)
        Platform result = Platform.JAVA_OR_UNKNOWN;

        // Get Minecraft version
        String minVersion = player.getProtocolVersion().getVersionIntroducedIn();
        String maxVersion = player.getProtocolVersion().getMostRecentSupportedVersion();
        String version;
        if (minVersion.equals(maxVersion)) {
            // Only one possible version, just give that version
            version = minVersion;
        } else {
            // Multiple possible versions, give the range
            version = String.format("%s - %s", minVersion, maxVersion);
        }

        // Get client brand (i.e. vanilla, forge, fabric)
        String clientBrand = player.getClientBrand();
        if (clientBrand == null) clientBrand = "";
        else clientBrand = String.format("(%s)", clientBrand);

        // Update the display name and return
        result.displayName = String.format("Java %s %s", version, clientBrand);
        return result;
    }

    // TODO: These emojis don't seem to render properly, at least in my limited testing of "trying it once and giving up". Need to fix!
    /*
    public static String getPlatformIcon(Platform platform) {
        return switch (platform) {
            case ANDROID, IOS, WINDOWS_PHONE -> "\uD83D\uDCF1"; // Mobile phone emoji
            case PS4, XBOX, NX -> "\uD83C\uDFAE"; // Game controller emoji
            case UWP, WIN32 -> "\uD83E\uDE9F"; // Window emoji
            case OSX -> "\uD83C\uDF4E"; // Apple emoji
            case TVOS -> "\uD83D\uDCFA"; // Television emoji

            case AMAZON -> "\uD83D\uDD25"; // Fire emoji because Amazon Fire... TODO: might need to change this
            case GEARVR, HOLOLENS -> "?"; // TODO: Need an emoji for VR platforms
            case BEDROCK_GENERIC, DEDICATED -> "?"; // TODO: Need an emoji for generic "Bedrock" platforms

            case EAGLER -> "\uD83C\uDF10"; // Globe emoji
            case JAVA_OR_UNKNOWN -> "♨"; // Hot Spring emoji (looks like the Java logo)
            default -> "❓"; // Question mark emoji
        };
    }
     */

    public enum Platform {
        // TODO: Find a better way to store the
        BEDROCK_GENERIC("Unknown", "Bedrock", "bedrock"),
        ANDROID("Android", null, "android"),
        IOS("iOS", null, "ios"),
        OSX("macOS", null, "macos"),
        AMAZON("Amazon", "Amazon Fire ??", "amazon_fire"),
        GEARVR("Gear VR", null, "gear_vr"),
        HOLOLENS("Hololens", "Microsoft Hololens", "hololens"),
        UWP("Windows", "Windows (Microsoft Store)", "windows"),
        WIN32("Windows x86", "Windows (EXE))", "windows"),
        DEDICATED("Dedicated", "Dedicated ??", "unknown"),
        TVOS("Apple TV", null, "apple_tv"),
        PS4("PS4", "PlayStation", "playstation"),
        NX("Switch", "Nintendo Switch", "nx"),
        XBOX("Xbox One", "Xbox", "xbox"),
        WINDOWS_PHONE("Windows Phone", null, "windows_phone"),
        EAGLER(null, "EaglerCraft", "eagler"),
        JAVA_OR_UNKNOWN(null, "Java", "java"),
        UNKNOWN(null, "Unknown", "unknown");

        private static final Platform[] ALL_PLATFORMS = values();

        private final String bedrockName;
        private String displayName;
        private final String iconName;

        Platform(String bedrockName, String displayName, String iconName) {
            this.bedrockName = bedrockName;
            this.displayName = displayName == null ? bedrockName : displayName;
            this.iconName = iconName;
        }

        public static Platform PlatformByBedrockName(String bedrockName) {
            for (Platform platform : ALL_PLATFORMS) {
                if (platform.bedrockName.equals(bedrockName)) return platform;
            }
            return BEDROCK_GENERIC;
        }

        public String getIconPath() {
            return String.format("/platform-icons/%s.png", iconName);
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
