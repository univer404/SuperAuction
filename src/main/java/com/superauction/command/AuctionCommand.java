package com.superauction.command;

import com.superauction.SuperAuction;
import com.superauction.auction.AuctionService;
import com.superauction.auction.Listing;
import com.superauction.gui.AuctionMenu;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class AuctionCommand implements CommandExecutor, TabCompleter {
    private final SuperAuction plugin;
    private final AuctionService auctions;
    private final AuctionMenu menus;

    public AuctionCommand(SuperAuction plugin, AuctionService auctions) {
        this.plugin = plugin;
        this.auctions = auctions;
        this.menus = new AuctionMenu(plugin, auctions);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) { menus.openBrowse(player, "", 0); return true; }
            sender.sendMessage("§b§lSuperAuction §8» §7Use §f/ah help §7to see commands.");
            List<Listing> listings = auctions.activeListings("");
            sender.sendMessage("§7Active listings: §f" + listings.size());
            listings.stream().limit(8).forEach(l -> sender.sendMessage("§8[" + l.id().toString().substring(0, 8) + "] §f"
                    + l.item().getAmount() + "x " + AuctionService.niceName(l.item().getType())
                    + " §7— §a" + l.price() + " §7by §f" + l.sellerName()));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) {
            sender.sendMessage("§b/ah §7Browse listings");
            sender.sendMessage("§b/ah sell <price> §7List your held item");
            sender.sendMessage("§b/ah mine §7Show your listings");
            sender.sendMessage("§b/ah search <text> §7Search listings");
            sender.sendMessage("§b/ah admin reload §7Reload configuration");
            return true;
        }
        if (!(sender instanceof Player player) && !sub.equals("admin")) {
            sender.sendMessage("This command requires a player.");
            return true;
        }
        Player player = sender instanceof Player p ? p : null;
        switch (sub) {
            case "sell" -> {
                if (player == null) return true;
                if (!player.hasPermission("superauction.sell")) { player.sendMessage("§cYou don't have permission."); return true; }
                if (args.length < 2) { player.sendMessage("§cUsage: /ah sell <price>"); return true; }
                try {
                    double price = parsePrice(args[1]);
                    double min = plugin.getConfig().getDouble("listings.minimum-price", 1);
                    double max = plugin.getConfig().getDouble("listings.maximum-price", 1_000_000_000);
                    if (price < min || price > max) throw new IllegalArgumentException("Price must be between " + min + " and " + max + ".");
                    auctions.createListing(player, price);
                } catch (IllegalArgumentException | IOException exception) {
                    player.sendMessage("§c" + (exception instanceof IOException ? "Could not safely save the listing. Your item was not listed." : exception.getMessage()));
                    plugin.getLogger().warning("Listing attempt failed: " + exception.getMessage());
                }
                return true;
            }
            case "mine" -> {
                if (player == null) return true;
                menus.openMine(player, 0);
                var mine = auctions.mine(player.getUniqueId());
                player.sendMessage("§b§lYour Listings §7(" + mine.size() + ")");
                mine.forEach(l -> player.sendMessage("§7" + l.item().getAmount() + "x " + AuctionService.niceName(l.item().getType())
                        + " — §a" + l.price() + " §7[" + l.status() + "]"));
                return true;
            }
            case "search" -> {
                if (player != null && args.length == 1) { menus.openBrowse(player, "", 0); return true; }
                String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                var found = auctions.activeListings(query);
                sender.sendMessage("§bSearch results §7(" + found.size() + ")");
                found.stream().limit(20).forEach(l -> sender.sendMessage("§f" + l.item().getAmount() + "x "
                        + AuctionService.niceName(l.item().getType()) + " §7— §a" + l.price() + " §7by §f" + l.sellerName()));
                return true;
            }
            case "history" -> {
                sender.sendMessage("§cTransaction history is not implemented in this foundation build.");
                return true;
            }
            case "view" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /ah view <player>"); return true; }
                var found = auctions.activeListings(args[1]).stream().filter(l -> l.sellerName().equalsIgnoreCase(args[1])).toList();
                sender.sendMessage("§bListings for " + args[1] + ": §f" + found.size());
                found.forEach(l -> sender.sendMessage("§f" + AuctionService.niceName(l.item().getType()) + " §7— §a" + l.price()));
                return true;
            }
            case "admin" -> {
                if (!sender.hasPermission("superauction.admin")) { sender.sendMessage("§cYou don't have permission."); return true; }
                if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("superauction.admin.reload")) { sender.sendMessage("§cYou don't have permission."); return true; }
                    plugin.reloadConfig();
                    sender.sendMessage("§aConfiguration reloaded.");
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("stats")) {
                    var all = auctions.activeListings("");
                    long pending = all.stream().filter(l -> l.status() == Listing.Status.RETURN_PENDING).count();
                    sender.sendMessage("§bSuperAuction stats");
                    sender.sendMessage("§7Active listings: §f" + all.size());
                    sender.sendMessage("§7Pending returns: §f" + auctions.allListings().stream().filter(l -> l.status() == Listing.Status.RETURN_PENDING).count());
                    sender.sendMessage("§7Storage: §fYAML");
                } else if (args.length >= 3 && args[1].equalsIgnoreCase("inspect")) {
                    if (!sender.hasPermission("superauction.admin.inspect")) { sender.sendMessage("§cYou don't have permission."); return true; }
                    try {
                        Listing listing = auctions.find(java.util.UUID.fromString(args[2]));
                        if (listing == null) { sender.sendMessage("§cListing not found."); return true; }
                        sender.sendMessage("§bListing §f" + listing.id());
                        sender.sendMessage("§7Seller: §f" + listing.sellerName() + " (" + listing.sellerId() + ")");
                        sender.sendMessage("§7Item: §f" + listing.item().getAmount() + "x " + AuctionService.niceName(listing.item().getType()));
                        sender.sendMessage("§7Price: §a" + listing.price() + " §7Status: §f" + listing.status());
                        sender.sendMessage("§7Created: §f" + java.time.Instant.ofEpochMilli(listing.createdAt()));
                        sender.sendMessage("§7Expires: §f" + java.time.Instant.ofEpochMilli(listing.expiresAt()));
                    } catch (IllegalArgumentException exception) { sender.sendMessage("§cInvalid listing UUID."); }
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("recover")) {
                    if (!sender.hasPermission("superauction.admin.recover")) { sender.sendMessage("§cYou don't have permission."); return true; }
                    var pendingRecords = auctions.allListings().stream().filter(l -> l.status() == Listing.Status.PENDING || l.status() == Listing.Status.RETURN_PENDING || l.status() == Listing.Status.FAILED_REQUIRES_REVIEW).toList();
                    sender.sendMessage("§eRecovery review only — this command does not move items or money.");
                    sender.sendMessage("§7Records requiring review: §f" + pendingRecords.size());
                    pendingRecords.stream().limit(20).forEach(l -> sender.sendMessage("§f" + l.id() + " §7" + l.status() + " §f" + l.sellerName() + " §7" + AuctionService.niceName(l.item().getType())));
                } else {
                    sender.sendMessage("§b/ah admin reload §7Reload configuration");
                    sender.sendMessage("§b/ah admin stats §7Show listing count");
                    sender.sendMessage("§b/ah admin inspect <listingId> §7Inspect a listing");
                    sender.sendMessage("§b/ah admin recover §7Review recoverable records");
                }
                return true;
            }
            default -> {
                sender.sendMessage("§cUnknown subcommand. Use /ah help.");
                return true;
            }
        }
    }

    private static double parsePrice(String input) {
        String value = input.toLowerCase(Locale.ROOT).trim();
        double multiplier = 1;
        if (value.endsWith("k")) { multiplier = 1_000; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("m")) { multiplier = 1_000_000; value = value.substring(0, value.length() - 1); }
        double price = Double.parseDouble(value) * multiplier;
        if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException("Price must be a positive number.");
        return price;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("sell", "mine", "history", "search", "view", "admin", "help").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) return List.of("reload", "stats", "inspect", "recover");
        return List.of();
    }
}