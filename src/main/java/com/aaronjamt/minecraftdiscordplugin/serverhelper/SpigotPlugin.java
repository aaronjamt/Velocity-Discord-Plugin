package com.aaronjamt.minecraftdiscordplugin.serverhelper;

import com.aaronjamt.minecraftdiscordplugin.Constants;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.croabeast.lib.advancement.AdvancementInfo;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class SpigotPlugin extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        // Register server events
        getServer().getPluginManager().registerEvents(this, this);

        // Register so we can send messages to the Velocity proxy via the BungeeCord channel
        getServer().getMessenger().registerOutgoingPluginChannel(this, Constants.COMMUNICATION_CHANNEL);
    }

    @Override
    public void onDisable() {
        // Unregister the registered channel (in case of a reload)
        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();

        String deathMessage = e.getDeathMessage();
        if (deathMessage == null) deathMessage = String.format("%s died!", player.getDisplayName());

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerDeath");
        out.writeUTF(deathMessage);

        player.sendPluginMessage(this, Constants.COMMUNICATION_CHANNEL, out.toByteArray());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAdvancementDone(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        AdvancementInfo info = AdvancementInfo.from(event.getAdvancement());
        if (info == null) return;

        String advancementType = switch (info.getFrame()) {
            case UNKNOWN, TASK -> "Advancement Made!";
            case GOAL -> "Goal Reached!";
            case CHALLENGE -> "Challenge Complete!";
        };

        ItemStack icon = info.getIcon();
        if (icon != null)
            player.sendMessage(icon.getType().toString());

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerAdvancement");
        out.writeUTF(advancementType);
        out.writeBoolean(info.getFrame() == AdvancementInfo.Frame.CHALLENGE); // For purple color
        out.writeUTF(info.getTitle());
        out.writeUTF(info.getDescription());

        player.sendPluginMessage(this, Constants.COMMUNICATION_CHANNEL, out.toByteArray());
    }
}