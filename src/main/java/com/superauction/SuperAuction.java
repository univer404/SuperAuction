package com.superauction;

import com.superauction.auction.AuctionService;
import com.superauction.command.AuctionCommand;
import com.superauction.storage.YamlAuctionStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;

public final class SuperAuction extends JavaPlugin {
    private AuctionService auctionService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            YamlAuctionStore store = new YamlAuctionStore(this);
            store.load();
            auctionService = new AuctionService(this, store);
        } catch (IOException exception) {
            getLogger().severe("Could not safely load auction data. Plugin will disable to protect existing data.");
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand command = Objects.requireNonNull(getCommand("ah"), "ah command missing from plugin.yml");
        AuctionCommand executor = new AuctionCommand(this, auctionService);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(auctionService, this);
        getLogger().info("SuperAuction enabled. YAML storage is active.");
    }

    @Override
    public void onDisable() {
        if (auctionService != null) {
            try {
                auctionService.save();
            } catch (IOException exception) {
                getLogger().severe("Failed to persist auction data during shutdown. Inspect the data directory before restarting.");
                exception.printStackTrace();
            }
        }
    }

    public AuctionService getAuctionService() {
        return auctionService;
    }
}