package com.superauction.gui;

import com.superauction.util.GuiText;

import com.superauction.SuperAuction;
import com.superauction.auction.AuctionService;
import com.superauction.auction.Listing;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;

import java.text.DecimalFormat;
import java.util.*;

/** Server-side tagged inventory menus. All actions are resolved from trusted listing IDs, never item lore. */
public final class AuctionMenu implements Listener {
    private final SuperAuction plugin;
    private final AuctionService service;
    private final NamespacedKey actionKey;
    private final NamespacedKey listingKey;
    private final NamespacedKey pageKey;
    private final Map<UUID, PendingListing> pendingListings = new HashMap<>();

    public AuctionMenu(SuperAuction plugin, AuctionService service) {
        this.plugin = plugin;
        this.service = service;
        actionKey = new NamespacedKey(plugin, "menu_action");
        listingKey = new NamespacedKey(plugin, "listing_id");
        pageKey = new NamespacedKey(plugin, "page");
    }

    public void openBrowse(Player player, String query, int page) {
        List<Listing> listings = service.activeListings(query);
        YamlConfiguration gui = gui();
        int inventorySize = menuSize(gui, "browse", 54);
        int configuredContentSlots = gui.getInt("menus.browse.content-slots", 45);
        int pageSize = Math.max(1, Math.min(Math.min(45, inventorySize - 9),
                Math.min(configuredContentSlots, plugin.getConfig().getInt("search.results-per-page", 45))));
        int pages = Math.max(1, (int)Math.ceil(listings.size() / (double)pageSize));
        int safePage = Math.max(0, Math.min(page, pages - 1));
        Inventory inventory = Bukkit.createInventory(new MenuHolder("browse", query, safePage),
                inventorySize, GuiText.component(menuTitle(gui, "browse", "&0SuperAuction &8• &7%page%/%pages%")
                .replace("%page%", String.valueOf(safePage + 1)).replace("%pages%", String.valueOf(pages))));
        int start = safePage * pageSize;
        for (int i = 0; i < pageSize && start + i < listings.size(); i++) {
            Listing listing = listings.get(start + i);
            ItemStack icon = listing.item();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                List<String> configuredLore = gui.getStringList("menus.browse.listing-lore");
                if (configuredLore.isEmpty()) configuredLore = List.of("&8&m----------------", "&7Seller: &f%seller%", "&7Price: &a$%price%", "&7Expires: &f%expires%", "&eClick to inspect");
                for (String line : configuredLore) lore.add(color(line.replace("%seller%", listing.sellerName())
                        .replace("%price%", format(listing.price())).replace("%expires%", remaining(listing.expiresAt()))));
                meta.lore(lore.stream().map(GuiText::component).toList());
                meta.getPersistentDataContainer().set(listingKey, PersistentDataType.STRING, listing.id().toString());
                icon.setItemMeta(meta);
            }
            if (i < inventory.getSize() - 9) inventory.setItem(i, icon);
        }
        button(inventory, inventory.getSize()-9, Material.ARROW, "&ePrevious page", "previous", null, safePage);
        button(inventory, inventory.getSize()-6, Material.COMPASS, "&bSearch", "search", null, safePage);
        button(inventory, inventory.getSize()-5, Material.HOPPER, "&bRefresh", "refresh", null, safePage);
        button(inventory, inventory.getSize()-4, Material.CHEST, "&bMy listings", "mine", null, safePage);
        button(inventory, inventory.getSize()-3, Material.BOOK, "&bHistory", "history", null, safePage);
        button(inventory, inventory.getSize()-1, Material.ARROW, "&eNext page", "next", null, safePage);
        player.openInventory(inventory);
    }

    public void openListingConfirmation(Player player, double price) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { player.sendMessage(color("&cHold the item you want to sell.")); return; }
        pendingListings.put(player.getUniqueId(), new PendingListing(hand.clone(), price));
        YamlConfiguration gui = gui();
        Inventory inv = Bukkit.createInventory(new MenuHolder("confirm-listing", "", 0), menuSize(gui, "confirm-listing", 27),
                color(menuTitle(gui, "confirm-listing", "&0Confirm Listing")));
        ItemStack preview = hand.clone();
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            Map<String,String> values = Map.of("%quantity%", String.valueOf(hand.getAmount()),
                    "%price%", format(price), "%duration%", String.valueOf(plugin.getConfig().getLong("listings.default-duration-hours", 24)));
            meta.setLore(renderLore(gui, "confirm-listing.preview-lore", List.of("&7Quantity: &f%quantity%", "&7Asking price: &a$%price%",
                    "&7Seller fee: &fDisabled (0%)", "&7Net proceeds: &a$%price%", "&7Duration: &f%duration% hours"), values));
            preview.setItemMeta(meta);
        }
        inv.setItem(menuSlot(gui, "confirm-listing.preview-slot", 13, inv.getSize()), preview);
        button(inv, 11, Material.LIME_CONCRETE, "&aConfirm Listing", "confirm-listing", null, 0);
        button(inv, 15, Material.RED_CONCRETE, "&cCancel", "cancel-listing-confirmation", null, 0);
        player.openInventory(inv);
    }

    public void openMine(Player player, int page) {
        List<Listing> listings = service.mine(player.getUniqueId()).stream()
                .filter(l -> l.status() == Listing.Status.ACTIVE || l.status() == Listing.Status.RETURN_PENDING || l.status() == Listing.Status.RETURN_DELIVERY_STARTED || l.status() == Listing.Status.FAILED_REQUIRES_REVIEW)
                .toList();
        YamlConfiguration gui = gui();
        int size = menuSize(gui, "mine", 54);
        int pageSize = Math.max(1, Math.min(Math.min(45, size - 9), gui.getInt("menus.mine.content-slots", 45)));
        int maxPage = Math.max(0, (listings.size() - 1) / pageSize);
        int safePage = Math.max(0, Math.min(page, maxPage));
        Inventory inventory = Bukkit.createInventory(new MenuHolder("mine", "", safePage), size,
                color(menuTitle(gui, "mine", "&0My Auction Listings")));
        for (int i=0; i<pageSize && safePage*pageSize+i<listings.size(); i++) {
            Listing listing=listings.get(safePage*pageSize+i);
            ItemStack icon=listing.item();
            ItemMeta meta=icon.getItemMeta();
            if(meta!=null){
                List<String> lore=meta.hasLore()&&meta.getLore()!=null?new ArrayList<>(meta.getLore()):new ArrayList<>();
                lore.addAll(renderLore(gui, "mine.listing-lore", List.of("&7Price: &a$%price%", "&7Status: &f%status%", "&eClick to manage"),
                        Map.of("%price%", format(listing.price()), "%status%", String.valueOf(listing.status()))));
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(listingKey,PersistentDataType.STRING,listing.id().toString());
                icon.setItemMeta(meta);
            }
            if (i < inventory.getSize() - 9) inventory.setItem(i,icon);
        }
        button(inventory,inventory.getSize()-9,Material.ARROW,"&ePrevious","previous",null,safePage);
        button(inventory,inventory.getSize()-5,Material.SPECTRAL_ARROW,"&bBack to Auction House","browse",null,0);
        button(inventory,inventory.getSize()-1,Material.ARROW,"&eNext","next",null,safePage);
        player.openInventory(inventory);
    }

    private void openInspect(Player player, UUID id) {
        Listing listing = service.find(id);
        if (listing == null || listing.status() != Listing.Status.ACTIVE || listing.expiresAt() <= System.currentTimeMillis()) {
            player.sendMessage(color("&cThis listing is no longer available."));
            openBrowse(player, "", 0);
            return;
        }
        YamlConfiguration gui = gui();
        Inventory inventory = Bukkit.createInventory(new MenuHolder("inspect", "", 0, id), menuSize(gui, "inspect", 27),
                color(menuTitle(gui, "inspect", "&0Listing Details")));
        ItemStack icon=listing.item();
        ItemMeta meta=icon.getItemMeta();
        if(meta!=null){
            List<String> lore=meta.hasLore()&&meta.getLore()!=null?new ArrayList<>(meta.getLore()):new ArrayList<>();
            lore.addAll(renderLore(gui, "inspect.listing-lore", List.of("&7Seller: &f%seller%", "&7Price: &a$%price%", "&7Expires: &f%expires%"),
                    Map.of("%seller%", listing.sellerName(), "%price%", format(listing.price()), "%expires%", remaining(listing.expiresAt()))));
            meta.setLore(lore); icon.setItemMeta(meta);
        }
        inventory.setItem(menuSlot(gui, "inspect.preview-slot", 13, inventory.getSize()),icon);
        button(inventory,11,Material.LIME_CONCRETE,"&aPurchase","purchase",id.toString(),0);
        button(inventory,15,Material.ARROW,"&eBack","browse","",0);
        player.openInventory(inventory);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked=event.getCurrentItem();
        if(clicked==null || clicked.getType().isAir()) return;
        ItemMeta meta=clicked.getItemMeta();
        if(meta==null) return;
        String action=meta.getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);
        if(action==null && meta.getPersistentDataContainer().has(listingKey,PersistentDataType.STRING)) {
            try {
                UUID id=UUID.fromString(meta.getPersistentDataContainer().get(listingKey,PersistentDataType.STRING));
                if(holder.type.equals("browse")) {
                    Listing listing = service.find(id);
                    if (event.isRightClick() && listing != null && isShulker(listing.item())) openShulkerPreview(player, listing.item());
                    else openInspect(player,id);
                } else if(holder.type.equals("mine")) {
                    Listing listing=service.find(id);
                    if(listing!=null && listing.sellerId().equals(player.getUniqueId())) openManage(player,id);
                }
            } catch (IllegalArgumentException ignored) { player.sendMessage(color("&cInvalid listing reference.")); }
            return;
        }
        if(action==null) return;
        switch(action) {
            case "previous" -> { if(holder.type.equals("mine")) openMine(player,holder.page-1); else openBrowse(player,holder.query,holder.page-1); }
            case "next" -> { if(holder.type.equals("mine")) openMine(player,holder.page+1); else openBrowse(player,holder.query,holder.page+1); }
            case "mine" -> openMine(player,0);
            case "browse","refresh" -> openBrowse(player,holder.query,0);
            case "search" -> {
                player.closeInventory();
                player.sendMessage(color("&bType your search in chat within 30 seconds. Type cancel to stop."));
                UUID id=player.getUniqueId();
                plugin.getServer().getScheduler().runTaskLater(plugin,()->SearchWaiter.arm(plugin,id,q->openBrowse(player,q,0)),1L);
            }
            case "history" -> {
                if (!player.hasPermission("superauction.history")) {
                    player.sendMessage(color("&cYou don't have permission."));
                    return;
                }
                var history = plugin.getTransactionJournal().all().stream()
                        .filter(t -> t.buyerId().equals(player.getUniqueId()) || t.sellerId().equals(player.getUniqueId()))
                        .limit(10)
                        .toList();
                player.sendMessage(color("&b&lYour Auction Transaction History"));
                if (history.isEmpty()) {
                    player.sendMessage(color("&7No recorded transactions yet."));
                } else {
                    history.forEach(t -> player.sendMessage(color("&7" + t.id().toString().substring(0, 8)
                            + " &f" + t.amount() + " &7" + t.stage()
                            + (t.buyerId().equals(player.getUniqueId()) ? " &8(buyer)" : " &8(seller)"))));
                }
            }
            case "purchase" -> {
                if(holder.listingId!=null) player.sendMessage(color("&ePurchase processing is disabled until durable economy reconciliation is implemented. No money was taken."));
            }
            case "confirm-listing" -> {
                PendingListing pending = pendingListings.remove(player.getUniqueId());
                if (pending == null) { player.sendMessage(color("&cListing confirmation expired. Try again.")); openBrowse(player, "", 0); return; }
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir() || hand.getAmount() != pending.item.getAmount() || !hand.isSimilar(pending.item)) {
                    player.sendMessage(color("&cYour held item changed. Nothing was listed."));
                    return;
                }
                try {
                    service.createListing(player, pending.price);
                    player.sendMessage(color("&aListing created."));
                    openBrowse(player, "", 0);
                } catch (Exception ex) {
                    player.sendMessage(color("&cCould not safely create listing: " + ex.getMessage()));
                }
            }
            case "cancel-listing-confirmation" -> {
                pendingListings.remove(player.getUniqueId());
                player.sendMessage(color("&7Listing cancelled; your item was not changed."));
                openBrowse(player, "", 0);
            }
            case "cancel-listing" -> {
                if(holder.listingId!=null) {
                    try { service.cancelListing(player,holder.listingId); player.sendMessage(color("&aListing cancelled; item returned or queued for recovery.")); openMine(player,0); }
                    catch(Exception ex){player.sendMessage(color("&cCould not safely cancel: "+ex.getMessage()));}
                }
            }
            case "close" -> player.closeInventory();
        }
    }

    private static boolean isShulker(ItemStack item) {
        return item.getType().name().endsWith("_SHULKER_BOX");
    }

    private void openShulkerPreview(Player player, ItemStack shulkerItem) {
        if (!(shulkerItem.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            player.sendMessage(color("&cThis shulker box could not be previewed safely."));
            return;
        }
        YamlConfiguration gui = gui();
        Inventory preview = Bukkit.createInventory(new MenuHolder("shulker-preview", "", 0), menuSize(gui, "shulker-preview", 54),
                color(menuTitle(gui, "shulker-preview", "&0Shulker Contents")));
        ItemStack[] contents = box.getInventory().getContents();
        for (int slot = 0; slot < Math.min(preview.getSize(), Math.min(27, contents.length)); slot++) {
            ItemStack content = contents[slot];
            if (content != null && !content.getType().isAir()) preview.setItem(slot, content.clone());
        }
        button(preview, 49, Material.BARRIER, "&cClose", "close", null, 0);
        player.openInventory(preview);
    }

    private void openManage(Player player, UUID id) {
        Listing listing=service.find(id);
        if(listing==null) {openMine(player,0);return;}
        YamlConfiguration gui = gui();
        Inventory inv=Bukkit.createInventory(new MenuHolder("manage","",0,id),menuSize(gui,"manage",27),color(menuTitle(gui,"manage","&0Manage Listing")));
        inv.setItem(menuSlot(gui, "manage.preview-slot", 13, inv.getSize()),listing.item());
        button(inv,11,Material.RED_CONCRETE,"&cCancel Listing","cancel-listing",id.toString(),0);
        button(inv,15,Material.ARROW,"&eBack","mine",null,0);
        player.openInventory(inv);
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            if(event.getRawSlots().stream().anyMatch(slot->slot<event.getView().getTopInventory().getSize())) event.setCancelled(true);
        }
    }

    private YamlConfiguration gui() {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "gui.yml"));
    }
    private String menuTitle(YamlConfiguration gui, String menu, String fallback) {
        return gui.getString("menus." + menu + ".title", fallback);
    }
    private int menuSize(YamlConfiguration gui, String menu, int fallback) {
        int size = gui.getInt("menus." + menu + ".size", fallback);
        return size >= 18 && size <= 54 && size % 9 == 0 ? size : fallback;
    }
    private List<String> renderLore(YamlConfiguration gui, String path, List<String> fallback, Map<String,String> replacements) {
        List<String> lines = gui.contains(path) ? gui.getStringList(path) : fallback;
        List<String> rendered = new ArrayList<>();
        for (String line : lines) {
            for (var entry : replacements.entrySet()) line = line.replace(entry.getKey(), entry.getValue());
            rendered.add(color(line));
        }
        return rendered;
    }
    private int menuSlot(YamlConfiguration gui, String path, int fallback, int size) {
        int slot = gui.getInt(path, fallback);
        return slot >= 0 && slot < size ? slot : fallback;
    }
    private void button(Inventory inv,int slot,Material material,String name,String action,String listing,int page) {
        YamlConfiguration gui = gui();
        String menu = inv.getHolder() instanceof MenuHolder holder ? holder.type : "";
        String path = "menus." + menu + ".buttons." + action;
        int fallbackSlot = Math.max(0, Math.min(slot, inv.getSize() - 1));
        int configuredSlot = gui.getInt(path + ".slot", fallbackSlot);
        if (configuredSlot < 0 || configuredSlot >= inv.getSize()) configuredSlot = fallbackSlot;
        String configuredMaterial = gui.getString(path + ".material", material.name());
        Material parsed = configuredMaterial == null ? null : Material.matchMaterial(configuredMaterial.trim());
        if (parsed == null || !parsed.isItem()) {
            plugin.getLogger().warning("Invalid GUI material at gui.yml " + path + ": " + configuredMaterial + ". Using " + material.name() + ".");
            parsed = material;
        }
        ItemStack item = new ItemStack(parsed);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GuiText.component(gui.getString(path + ".name", name)));
        if (gui.contains(path + ".lore")) meta.lore(gui.getStringList(path + ".lore").stream().map(GuiText::component).toList());
        meta.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action);
        if(listing!=null) meta.getPersistentDataContainer().set(listingKey,PersistentDataType.STRING,listing);
        meta.getPersistentDataContainer().set(pageKey,PersistentDataType.INTEGER,page);
        item.setItemMeta(meta);
        inv.setItem(configuredSlot,item);
    }
    private static String format(double price){return new DecimalFormat("#,##0.##").format(price);}
    private static String remaining(long end){long seconds=Math.max(0,(end-System.currentTimeMillis())/1000);return seconds/3600+"h "+(seconds%3600)/60+"m";}
    private static String color(String text){return text == null ? "" : text;}
    private record PendingListing(ItemStack item, double price) { }
    private static final class MenuHolder implements InventoryHolder {
        final String type,query; final int page; final UUID listingId;
        MenuHolder(String type,String query,int page){this(type,query,page,null);}
        MenuHolder(String type,String query,int page,UUID listingId){this.type=type;this.query=query;this.page=page;this.listingId=listingId;}
        @Override public Inventory getInventory(){return null;}
    }
}