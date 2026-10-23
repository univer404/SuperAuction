package com.superauction.storage;

import com.superauction.auction.Listing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
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
public final class YamlAuctionStore implements AuctionStore {
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
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Auction data contains invalid YAML. The original file was left untouched.", exception);
        }

        List<Listing> loaded = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("listings");
        if (section == null && !yaml.getKeys(false).isEmpty()) {
            throw new IOException("Auction data has no listings section. The original file was left untouched.");
        }
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    ConfigurationSection row = section.getConfigurationSection(key);
                    if (row == null) {
                        throw new IllegalArgumentException("listing entry is not a configuration section");
                    }

                    ItemStack item = row.getItemStack("item");
                    double price = row.getDouble("price", Double.NaN);
                    long createdAt = row.getLong("created-at", -1L);
                    long expiresAt = row.getLong("expires-at", -1L);
                    String sellerId = row.getString("seller-uuid", "");
                    String sellerName = row.getString("seller-name", "");
                    String status = row.getString("status", "");

                    if (item == null || item.getType().isAir()) {
                        throw new IllegalArgumentException("missing or empty escrow item");
                    }
                    if (!Double.isFinite(price) || price <= 0) {
                        throw new IllegalArgumentException("invalid price");
                    }
                    if (createdAt < 0 || expiresAt < createdAt) {
                        throw new IllegalArgumentException("invalid timestamps");
                    }
                    if (sellerName.isBlank()) {
                        throw new IllegalArgumentException("missing seller name");
                    }

                    loaded.add(new Listing(
                            UUID.fromString(key),
                            UUID.fromString(sellerId),
                            sellerName,
                            item,
                            price,
                            createdAt,
                            expiresAt,
                            Listing.Status.valueOf(status)));
                } catch (RuntimeException exception) {
                    throw new IOException("Auction record '" + key
                            + "' is invalid. The plugin will stop instead of overwriting potentially recoverable item data.",
                            exception);
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
        if (Files.exists(file)) {
            Path backupFile = file.resolveSibling("auctions.yml.bak");
            Files.copy(file, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException exception) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}