package com.superauction.auction;

import com.superauction.storage.YamlAuctionStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AuctionService implements Listener {
    private final JavaPlugin plugin;
    private final YamlAuctionStore store;

    public AuctionService(JavaPlugin plugin, YamlAuctionStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public synchronized Listing createListing(Player seller, double price) throws IOException {
        double minimumPrice = plugin.getConfig().getDouble("listings.minimum-price", 1.0);
        double maximumPrice = plugin.getConfig().getDouble("listings.maximum-price", 1_000_000_000.0);
        if (!Double.isFinite(price) || price <= 0 || price < minimumPrice || price > maximumPrice) {
            throw new IllegalArgumentException("Price must be between " + minimumPrice + " and " + maximumPrice + ".");
        }
        var hand = seller.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) throw new IllegalArgumentException("Hold the item you want to sell in your main hand.");
        if (plugin.getConfig().getStringList("listings.blacklist").contains(hand.getType().name())) {
            throw new IllegalArgumentException("That item cannot be listed.");
        }
        List<Listing> existing = store.all();
        long activeCount = existing.stream()
                 .filter(l -> l.sellerId().equals(seller.getUniqueId())\n                        && (l.status() == Listing.Status.ACTIVE || l.status() == Listing.Status.PENDING))
                .count();
        if (activeCount >= Math.max(0, plugin.getConfig().getInt("listings.default-limit", 10))) {
            throw new IllegalArgumentException("You have reached your listing limit.");
        }
        long serverLimit = Math.max(1, plugin.getConfig().getLong("listings.maximum-server-listings", 10_000));
        long serverActiveCount = existing.stream()
                .filter(l -> l.status() == Listing.Status.ACTIVE || l.status() == Listing.Status.PENDING).count();
        if (serverActiveCount >= serverLimit) {
            throw new IllegalArgumentException("The auction house has reached its server-wide listing limit.");
        }
        long durationHours = plugin.getConfig().getLong("listings.default-duration-hours", 24);
        if (durationHours < 1 || durationHours > 8_760) {
            throw new IllegalArgumentException("Listing duration configuration must be between 1 and 8760 hours.");
        }

        var securedItem = hand.clone();
        long now = System.currentTimeMillis();
        Listing listing = new Listing(UUID.randomUUID(), seller.getUniqueId(), seller.getName(), securedItem,
                price, now, now + durationHours * 3_600_000L, Listing.Status.PENDING);
        // Persist escrow as PENDING first: a crash cannot expose this listing for purchase.
        store.add(listing);
        try {
            store.save();
        } catch (IOException exception) {
            store.remove(listing.id());
            throw exception;
        }

        var currentHand = seller.getInventory().getItemInMainHand();
        if (!currentHand.isSimilar(securedItem) || currentHand.getAmount() != securedItem.getAmount()) {
            // Keep the already-persisted escrow record non-purchasable for manual review.
            // Removing it here could lose the only durable trace if the follow-up write failed.
            listing.setStatus(Listing.Status.FAILED_REQUIRES_REVIEW);
            try {
                store.save();
            } catch (IOException exception) {
                throw new IOException("Your held item changed and the escrow record needs admin review.", exception);
            }
            throw new IllegalArgumentException("Your held item changed before listing. The record was flagged for admin review; no item was removed.");
        }

        seller.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.AIR));
        listing.setStatus(Listing.Status.ACTIVE);
        try {
            store.save();
        } catch (IOException exception) {
            // Leave the durable escrow record non-purchasable if final activation cannot be saved.
            listing.setStatus(Listing.Status.PENDING);
            throw exception;
        }
        seller.sendMessage("§aListed §f" + securedItem.getAmount() + "x " + niceName(securedItem.getType()) + " §afor §e" + price + "§a.");
        return listing;
    }

    public List<Listing> activeListings(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return store.all().stream()
                .filter(l -> l.status() == Listing.Status.ACTIVE && l.expiresAt() > System.currentTimeMillis())
                .filter(l -> normalized.isBlank() || l.sellerName().toLowerCase(Locale.ROOT).contains(normalized)
                        || niceName(l.item().getType()).toLowerCase(Locale.ROOT).contains(normalized)
                        || l.item().getType().name().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparingLong(Listing::createdAt).reversed()).toList();
    }

    public Listing find(UUID id) {
        return store.find(id);
    }

    public List<Listing> allListings() {
        return store.all();
    }

    public synchronized void cancelListing(Player seller, UUID id) throws IOException {
        Listing listing = store.find(id);
        if (listing == null || !listing.sellerId().equals(seller.getUniqueId())) {
            throw new IllegalArgumentException("Listing not found for your account.");
        }
        if (listing.status() != Listing.Status.ACTIVE) {
            throw new IllegalArgumentException("Only active listings can be cancelled.");
        }
        // Persist a non-purchasable return state before attempting delivery.
        listing.setStatus(Listing.Status.RETURN_PENDING);
        store.save();
        ItemStack returnedItem = listing.item();
        if (!canFit(seller, returnedItem)) {
            // Do not partially deliver: the escrow record remains the single complete copy.
            seller.sendMessage("§eYour inventory cannot fit this item. It remains in a pending return record.");
            return;
        }
        seller.getInventory().addItem(returnedItem);
        listing.setStatus(Listing.Status.RETURNED);
        try {
            store.save();
        } catch (IOException exception) {
            // The write outcome is ambiguous after inventory mutation. Do not attempt another automatic delivery.
            listing.setStatus(Listing.Status.FAILED_REQUIRES_REVIEW);
            throw exception;
        }
    }

    public List<Listing> mine(UUID playerId) {
        return store.all().stream().filter(l -> l.sellerId().equals(playerId)).toList();
    }

    /** Marks expired listings as pending returns; item data stays in escrow until safely recovered. */
    private static boolean canFit(Player player, org.bukkit.inventory.ItemStack item) {
        int remaining = item.getAmount();
        int maxStack = item.getMaxStackSize();
        for (org.bukkit.inventory.ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= maxStack;
            } else if (existing.isSimilar(item)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return remaining <= 0;
    }

    public synchronized int expireListings() throws IOException {
        int expired = 0;
        long now = System.currentTimeMillis();
        for (Listing listing : store.all()) {
            if (listing.status() == Listing.Status.ACTIVE && listing.expiresAt() <= now) {
                listing.setStatus(Listing.Status.RETURN_PENDING);
                expired++;
            }
        }
        if (expired > 0) store.save();
        return expired;
    }

    public void save() throws IOException { store.save(); }

    public static String niceName(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }
}