package com.superauction.gui;

import org.bukkit.entity.Player;
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
    public SearchWaiter() {}
    static void arm(JavaPlugin plugin, UUID playerId, Consumer<String> callback) {
        WAITING.put(playerId, callback);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> WAITING.remove(playerId), 20L * 30);
    }
    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        Consumer<String> callback=WAITING.remove(event.getPlayer().getUniqueId());
        if(callback==null)return;
        event.setCancelled(true);
        String message=event.getMessage().trim();
        if(message.equalsIgnoreCase("cancel")) { event.getPlayer().sendMessage("§7Search cancelled."); return; }
        if(message.length()>64) { event.getPlayer().sendMessage("§cSearch is too long (64 characters maximum)."); return; }
        event.getPlayer().getServer().getScheduler().runTask(
                org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(SearchWaiter.class),
                () -> callback.accept(message));
    }
}