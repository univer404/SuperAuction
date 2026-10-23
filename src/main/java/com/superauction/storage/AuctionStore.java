package com.superauction.storage;

import com.superauction.auction.Listing;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Durable listing storage. Implementations must fail closed when stored escrow is invalid. */
public interface AuctionStore {
    void load() throws IOException;
    List<Listing> all();
    void add(Listing listing);
    boolean remove(UUID id);
    Listing find(UUID id);
    void save() throws IOException;
}
