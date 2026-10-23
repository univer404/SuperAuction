package com.superauction.auction;

import com.superauction.storage.AuctionStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AuctionService implements Listener {
    private final JavaPlugin plugin;
    private final AuctionStore store;

    public AuctionService(JavaPlugin plugin, AuctionStore store) {
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
                 .filter(l -> l.sellerId().equals(seller.getUniqueId())
                        && (l.status() == Listing.Status.ACTIVE || l.status() == Listing.Status.PENDING))
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

    /**
     * Executes the online happy path. Any uncertain outcome is reserved for manual review;
     * the plugin never retries a charge, item delivery, or payout automatically.
     */
    public synchronized void purchase(Player buyer, UUID listingId, Economy economy,
                                      TransactionJournal journal) throws IOException {
        if (economy == null) throw new IllegalStateException("No Vault economy provider is available.");
        Listing listing = store.find(listingId);
        if (listing == null || listing.status() != Listing.Status.ACTIVE
                || listing.expiresAt() <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("This listing is no longer available.");
        }
        if (listing.sellerId().equals(buyer.getUniqueId())
                && !plugin.getConfig().getBoolean("economy.allow-buy-own-listing", false)) {
            throw new IllegalArgumentException("You cannot purchase your own listing.");
        }
        if (!canFit(buyer, listing.item())) {
            throw new IllegalArgumentException("Your inventory does not have enough space for this item.");
        }
        if (!Double.isFinite(listing.price()) || listing.price() <= 0
                || !economy.has(buyer, listing.price())) {
            throw new IllegalArgumentException("You do not have enough money.");
        }

        // Reserve the listing durably before any economy or inventory mutation.
        listing.setStatus(Listing.Status.PENDING_PURCHASE);
        try {
            store.save();
        } catch (IOException exception) {
            listing.setStatus(Listing.Status.ACTIVE);
            throw exception;
        }

        TransactionJournal.Transaction transaction;
        try {
            transaction = journal.begin(buyer.getUniqueId(), listing.sellerId(), listing.id(), listing.price());
        } catch (IOException | RuntimeException exception) {
            listing.setStatus(Listing.Status.ACTIVE);
            try { store.save(); } catch (IOException saveException) { exception.addSuppressed(saveException); }
            throw exception;
        }

        boolean buyerCharged = false;
        boolean paymentDeclinedCleanly = false;
        try {
            EconomyResponse withdrawal = economy.withdrawPlayer(buyer, listing.price());
            if (withdrawal == null || !withdrawal.transactionSuccess()) {
                journal.advance(transaction.id(), TransactionJournal.Stage.ABORTED);
                listing.setStatus(Listing.Status.ACTIVE);
                store.save();
                paymentDeclinedCleanly = true;
                throw new IllegalStateException("The economy provider declined the payment; no item was delivered.");
            }
            buyerCharged = true;
            journal.advance(transaction.id(), TransactionJournal.Stage.BUYER_CHARGED);

            // The inventory capacity was checked before charging; Bukkit executes this on the server thread.
            Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(listing.item());
            if (!leftovers.isEmpty()) {
                throw new IllegalStateException("Item delivery was incomplete; transaction requires admin review.");
            }
            journal.advance(transaction.id(), TransactionJournal.Stage.ITEM_DELIVERED);

            EconomyResponse payout = economy.depositPlayer(Bukkit.getOfflinePlayer(listing.sellerId()), listing.price());
            if (payout == null || !payout.transactionSuccess()) {
                throw new IllegalStateException("Seller payout failed; transaction requires admin review.");
            }
            journal.advance(transaction.id(), TransactionJournal.Stage.SELLER_PAID);

            listing.setStatus(Listing.Status.SOLD);
            store.save();
            journal.advance(transaction.id(), TransactionJournal.Stage.COMPLETE);
            buyer.sendMessage("§aPurchased §f" + listing.item().getAmount() + "x "
                    + niceName(listing.item().getType()) + " §afor §e" + listing.price() + "§a.");
            Player seller = Bukkit.getPlayer(listing.sellerId());
            if (seller != null) seller.sendMessage("§aYour auction sold for §e" + listing.price() + "§a.");
        } catch (IOException | RuntimeException exception) {
            if (buyerCharged) {
                // Never blindly refund or retry: the previous external operation may have succeeded.
                try { journal.advance(transaction.id(), TransactionJournal.Stage.NEEDS_REVIEW); }
                catch (IOException | IllegalStateException journalException) { exception.addSuppressed(journalException); }
                listing.setStatus(Listing.Status.FAILED_REQUIRES_REVIEW);
                try { store.save(); } catch (IOException saveException) { exception.addSuppressed(saveException); }
            } else if (!paymentDeclinedCleanly) {
                // Even before charging, a failed persistence/journal step leaves reservation state uncertain.
                listing.setStatus(Listing.Status.FAILED_REQUIRES_REVIEW);
                try { store.save(); } catch (IOException saveException) { exception.addSuppressed(saveException); }
            }
            throw exception;
        }
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
        // RETURN_PENDING means delivery has not started. Persist a distinct state before
        // touching the inventory so a crash can never be mistaken for an undelivered return.
        listing.setStatus(Listing.Status.RETURN_PENDING);
        store.save();
        ItemStack returnedItem = listing.item();
        if (!canFit(seller, returnedItem)) {
            // Do not partially deliver: the escrow record remains the single complete copy.
            seller.sendMessage("§eYour inventory cannot fit this item. It remains in a pending return record.");
            return;
        }

        // Once this state is durable, recovery must be manual: after a crash we cannot know
        // whether inventory mutation happened, so automatic retry could duplicate the item.
        listing.setStatus(Listing.Status.RETURN_DELIVERY_STARTED);
        store.save();
        seller.getInventory().addItem(returnedItem);
        listing.setStatus(Listing.Status.RETURNED);
        try {
            store.save();
        } catch (IOException exception) {
            // The durable state remains RETURN_DELIVERY_STARTED if this save fails.
            // Never automatically retry an ambiguous delivery.
            listing.setStatus(Listing.Status.FAILED_REQUIRES_REVIEW);
            try {
                store.save();
            } catch (IOException reviewException) {
                exception.addSuppressed(reviewException);
            }
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

    /** Startup recovery is intentionally review-only; it never re-delivers or re-charges. */
    public synchronized int markInterruptedPurchasesForReview() throws IOException {
        int changed = 0;
        for (Listing listing : store.all()) {
            if (listing.status() == Listing.Status.PENDING_PURCHASE) {
                listing.setStatus(Listing.Status.FAILED_REQUIRES_REVIEW);
                changed++;
            }
        }
        if (changed > 0) store.save();
        return changed;
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