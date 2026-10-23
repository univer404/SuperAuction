package com.superauction;

import com.superauction.auction.AuctionService;
import com.superauction.auction.TransactionJournal;
import com.superauction.command.AuctionCommand;
import com.superauction.gui.AuctionMenu;
import com.superauction.gui.SearchWaiter;
import com.superauction.storage.YamlAuctionStore;
import com.superauction.placeholder.SuperAuctionExpansion;
import org.bukkit.command.PluginCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;

public final class SuperAuction extends JavaPlugin {
    private AuctionService auctionService;
    private TransactionJournal transactionJournal;
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            YamlAuctionStore store = new YamlAuctionStore(this);
            store.load();
            transactionJournal = new TransactionJournal(getDataFolder().toPath().resolve("transactions.log"));
            transactionJournal.load();
            auctionService = new AuctionService(this, store);
        } catch (IOException exception) {
            getLogger().severe("Could not safely load auction data. Plugin will disable to protect existing data.");
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        setupEconomy();
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SuperAuctionExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered: %superauction_...%");
        } else {
            getLogger().info("PlaceholderAPI not found; placeholders are unavailable.");
        }
        PluginCommand command = Objects.requireNonNull(getCommand("ah"), "ah command missing from plugin.yml");
        AuctionCommand executor = new AuctionCommand(this, auctionService);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(auctionService, this);
        getServer().getPluginManager().registerEvents(new SearchWaiter(this), this);
        long intervalSeconds = Math.max(15L, getConfig().getLong("expiration.check-interval-seconds", 60L));
        getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                int expired = auctionService.expireListings();
                if (expired > 0) getLogger().info("Marked " + expired + " expired auction(s) as pending returns.");
            } catch (IOException exception) {
                getLogger().severe("Could not persist auction expiration changes. Existing escrow records were retained.");
                exception.printStackTrace();
            }
        }, 20L, intervalSeconds * 20L);
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

    public TransactionJournal getTransactionJournal() {
        return transactionJournal;
    }

    /**
     * Resolve Vault's active economy provider. Purchase processing remains disabled until
     * the journal and item-delivery reconciliation workflow is completed.
     */
    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault was not found. Auction browsing and listing remain available; purchases are disabled.");
            return;
        }
        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            getLogger().warning("Vault has no economy provider. Purchases remain disabled.");
            return;
        }
        economy = registration.getProvider();
        getLogger().info("Vault economy provider detected: " + economy.getName());
    }

    public Economy getEconomy() {
        return economy;
    }
}