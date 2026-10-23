package com.superauction.placeholder;

import com.superauction.SuperAuction;
import com.superauction.auction.Listing;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/** Optional PlaceholderAPI integration; the class is only loaded when PlaceholderAPI is present. */
public final class SuperAuctionExpansion extends PlaceholderExpansion {
    private final SuperAuction plugin;

    public SuperAuctionExpansion(SuperAuction plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "superauction";
    }

    @Override
    public String getAuthor() {
        return "SuperAuction";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return "";
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "active_listings" -> Integer.toString(plugin.getAuctionService().activeListings("").size());
            case "total_records" -> Integer.toString(plugin.getAuctionService().allListings().size());
            case "player_listings" -> player == null ? "0" : Long.toString(
                    plugin.getAuctionService().mine(player.getUniqueId()).stream()
                            .filter(listing -> listing.status() == Listing.Status.ACTIVE).count());
            case "player_transactions" -> player == null ? "0" : Long.toString(
                    plugin.getTransactionJournal().all().stream()
                            .filter(transaction -> transaction.buyerId().equals(player.getUniqueId())
                                    || transaction.sellerId().equals(player.getUniqueId()))
                            .count());
            default -> null;
        };
    }
}
