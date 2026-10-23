package com.superauction.auction;

import com.superauction.storage.YamlAuctionStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
        if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException("Price must be a positive finite number.");
        var hand = seller.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) throw new IllegalArgumentException("Hold the item you want to sell in your main hand.");
        if (plugin.getConfig().getStringList("listings.blacklist").contains(hand.getType().name())) {
            throw new IllegalArgumentException("That item cannot be listed.");
        }
        long activeCount = store.all().stream().filter(l -> l.sellerId().equals(seller.getUniqueId()) && l.status() == Listing.Status.ACTIVE).count();
        if (activeCount >= plugin.getConfig().getInt("listings.default-limit", 10)) throw new IllegalArgumentException("You have reached your listing limit.");

        var securedItem = hand.clone();
        long now = System.currentTimeMillis();
        Listing listing = new Listing(UUID.randomUUID(), seller.getUniqueId(), seller.getName(), securedItem,
                price, now, now + plugin.getConfig().getLong("listings.default-duration-hours", 24) * 3_600_000L, Listing.Status.PENDING);
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
            store.remove(listing.id());
            store.save();
            throw new IllegalArgumentException("Your held item changed before listing. Nothing was removed.");
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
        var leftovers = seller.getInventory().addItem(listing.item());
        if (leftovers.isEmpty()) {
            listing.setStatus(Listing.Status.RETURNED);
            store.save();
        } else {
            // Keep the escrow record and tell the player; recovery must be performed by an administrator.
            seller.sendMessage("§eYour inventory is full. The item remains in a pending return record; do not remove auction data.");
        }
    }

    public List<Listing> mine(UUID playerId) {
        return store.all().stream().filter(l -> l.sellerId().equals(playerId)).toList();
    }

    /** Marks expired listings as pending returns; item data stays in escrow until safely recovered. */
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