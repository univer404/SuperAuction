package com.superauction.auction;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

public final class Listing {
    public enum Status { PENDING, ACTIVE, SOLD, RETURN_PENDING, RETURNED, EXPIRED, CANCELLED, FAILED_REQUIRES_REVIEW }

    private final UUID id;
    private final UUID sellerId;
    private final String sellerName;
    private final ItemStack item;
    private final double price;
    private final long createdAt;
    private final long expiresAt;
    private Status status;

    public Listing(UUID id, UUID sellerId, String sellerName, ItemStack item, double price,
                   long createdAt, long expiresAt, Status status) {
        this.id = Objects.requireNonNull(id);
        this.sellerId = Objects.requireNonNull(sellerId);
        this.sellerName = Objects.requireNonNull(sellerName);
        this.item = Objects.requireNonNull(item).clone();
        this.price = price;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = Objects.requireNonNull(status);
    }

    public UUID id() { return id; }
    public UUID sellerId() { return sellerId; }
    public String sellerName() { return sellerName; }
    public ItemStack item() { return item.clone(); }
    public double price() { return price; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }
    public Status status() { return status; }
    public void setStatus(Status status) { this.status = Objects.requireNonNull(status); }
}