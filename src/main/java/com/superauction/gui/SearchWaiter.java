package com.superauction.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** One-shot chat search capture; chat is cancelled only for players actively searching. */
public final class SearchWaiter implements Listener {
    private static final Map<UUID, Consumer<String>> WAITING = new ConcurrentHashMap<>();
    private final JavaPlugin plugin;

    public SearchWaiter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    static void arm(JavaPlugin plugin, UUID playerId, Consumer<String> callback) {
        WAITING.put(playerId, callback);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> WAITING.remove(playerId, callback), 20L * 30);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Consumer<String> callback = WAITING.remove(playerId);
        if (callback == null) return;

        event.setCancelled(true);
        String message = event.getMessage().trim();
        if (message.equalsIgnoreCase("cancel")) {
            event.getPlayer().sendMessage("§7Search cancelled.");
            return;
        }

        int maxLength = Math.max(1, Math.min(256,
                plugin.getConfig().getInt("search.maximum-query-length", 64)));
        if (message.length() > maxLength) {
            event.getPlayer().sendMessage("§cSearch is too long (" + maxLength + " characters maximum).");
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(message));
    }
}
