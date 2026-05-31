package com.github.canon.liteminerbukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.NonNull;

import java.nio.ByteBuffer;

public class NetworkListener implements PluginMessageListener {

    private final LiteminerBukkit plugin;

    public NetworkListener(LiteminerBukkit plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, @NonNull Player player, byte @NonNull [] message) {
        if (!channel.equals(LiteminerBukkit.C2S_KEYBIND)) return;

        try {
            if (message.length == 5) {
                ByteBuffer buffer = ByteBuffer.wrap(message);
                plugin.updatePlayerState(player, buffer.get() != 0, buffer.getInt());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to decode Liteminer packet: " + e.getMessage());
        }
    }
}
