package com.superauction.storage;

import com.superauction.auction.Listing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** YAML store with temporary-file writes; never replaces the data file until the new file is complete. */
public final class YamlAuctionStore {
    private final JavaPlugin plugin;
    private final Path file;
    private final Path tempFile;
    private final List<Listing> listings = new ArrayList<>();

    public YamlAuctionStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("auctions.yml");
        this.tempFile = plugin.getDataFolder().toPath().resolve("auctions.yml.tmp");
    }

    public synchronized void load() throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        List<Listing> loaded = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("listings");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    ConfigurationSection row = section.getConfigurationSection(key);
                    if (row == null) continue;
                    ItemStack item = row.getItemStack("item");
                    if (item == null || item.getType().isAir()) {
                        plugin.getLogger().severe("Skipping invalid listing " + key + "; manual recovery may be required.");
                        continue;
                    }
                    loaded.add(new Listing(
                            UUID.fromString(key),
                            UUID.fromString(row.getString("seller-uuid", "")),
                            row.getString("seller-name", "Unknown"),
                            item,
                            row.getDouble("price"),
                            row.getLong("created-at"),
                            row.getLong("expires-at"),
                            Listing.Status.valueOf(row.getString("status", "ACTIVE"))));
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe("Invalid auction record " + key + ". It was not loaded; preserve the file for recovery.");
                }
            }
        }
        listings.clear();
        listings.addAll(loaded);
    }

    public synchronized List<Listing> all() {
        return new ArrayList<>(listings);
    }

    public synchronized void add(Listing listing) {
        listings.add(listing);
    }

    public synchronized boolean remove(UUID id) {
        return listings.removeIf(listing -> listing.id().equals(id));
    }

    public synchronized Listing find(UUID id) {
        return listings.stream().filter(listing -> listing.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        for (Listing listing : listings) {
            String path = "listings." + listing.id();
            yaml.set(path + ".seller-uuid", listing.sellerId().toString());
            yaml.set(path + ".seller-name", listing.sellerName());
            yaml.set(path + ".item", listing.item());
            yaml.set(path + ".price", listing.price());
            yaml.set(path + ".created-at", listing.createdAt());
            yaml.set(path + ".expires-at", listing.expiresAt());
            yaml.set(path + ".status", listing.status().name());
        }
        yaml.save(tempFile.toFile());
        try {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException exception) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}