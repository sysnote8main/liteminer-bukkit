package com.github.canon.liteminerbukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LiteminerBukkit extends JavaPlugin {

    public static final String C2S_KEYBIND = "liteminer:main/c2sveinminekeybindchange";
    public static final String S2C_SHAPE = "liteminer:main/s2csetshape";
    public static final String AMBER_PING = "amber:internal/pingpacket";
    public static final String AMBER_PONG = "amber:internal/pongpacket";

    // プレイヤーのキーバインド状態を保持するマップ
    private final Map<UUID, LiteminerState> playerStates = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("LiteminerBukkit is starting...");

        // プラグインメッセージの送受信登録
        Messenger messenger = getServer().getMessenger();
        messenger.registerIncomingPluginChannel(this, C2S_KEYBIND, new NetworkListener(this));
        messenger.registerOutgoingPluginChannel(this, C2S_KEYBIND);
        messenger.registerOutgoingPluginChannel(this, S2C_SHAPE);
        messenger.registerOutgoingPluginChannel(this, AMBER_PING);
        messenger.registerOutgoingPluginChannel(this, AMBER_PONG);

        // イベント登録
        getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);

        getLogger().info("LiteminerBukkit enabled successfully!");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getLogger().info("LiteminerBukkit disabled.");
    }

    public void updatePlayerState(Player player, boolean keybindState, int shape) {
        playerStates.put(player.getUniqueId(), new LiteminerState(keybindState, shape));
    }

    public LiteminerState getPlayerState(Player player) {
        return playerStates.getOrDefault(player.getUniqueId(), new LiteminerState(false, 0));
    }

    public record LiteminerState(boolean keybindPressed, int shape) {}
}
