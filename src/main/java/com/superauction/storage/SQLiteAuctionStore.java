package com.superauction.storage;

import com.superauction.auction.Listing;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * SQLite listing store. Each save replaces the table contents inside one SQL transaction,
 * so a process crash cannot leave a half-written listing snapshot.
 */
public final class SQLiteAuctionStore implements AuctionStore {
    private final JavaPlugin plugin;
    private final Path databaseFile;
    private final List<Listing> listings = new ArrayList<>();

    public SQLiteAuctionStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = plugin.getDataFolder().toPath().resolve("auctions.db");
    }

    @Override
    public synchronized void load() throws IOException {
        try {
            Files.createDirectories(databaseFile.getParent());
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS auction_listings (
                        id TEXT PRIMARY KEY,
                        seller_uuid TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        item_data TEXT NOT NULL,
                        price REAL NOT NULL CHECK(price > 0),
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        status TEXT NOT NULL
                    )
                    """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_status_created ON auction_listings(status, created_at DESC)");
                List<Listing> loaded = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery("SELECT id, seller_uuid, seller_name, item_data, price, created_at, expires_at, status FROM auction_listings")) {
                    while (rows.next()) {
                        try {
                            ItemStack item = deserializeItem(rows.getString("item_data"));
                            if (item.getType().isAir() || !Double.isFinite(rows.getDouble("price"))
                                    || rows.getDouble("price") <= 0 || rows.getLong("created_at") < 0
                                    || rows.getLong("expires_at") < rows.getLong("created_at")
                                    || rows.getString("seller_name").isBlank()) {
                                throw new IllegalArgumentException("invalid listing fields");
                            }
                            loaded.add(new Listing(
                                    UUID.fromString(rows.getString("id")),
                                    UUID.fromString(rows.getString("seller_uuid")),
                                    rows.getString("seller_name"),
                                    item,
                                    rows.getDouble("price"),
                                    rows.getLong("created_at"),
                                    rows.getLong("expires_at"),
                                    Listing.Status.valueOf(rows.getString("status"))));
                        } catch (RuntimeException exception) {
                            throw new IOException("SQLite auction data contains an invalid listing. The database was not modified.", exception);
                        }
                    }
                }
                listings.clear();
                listings.addAll(loaded);
            }
        } catch (SQLException exception) {
            throw new IOException("Could not load SQLite auction database. Existing data was preserved.", exception);
        }
    }

    @Override
    public synchronized List<Listing> all() {
        return new ArrayList<>(listings);
    }

    @Override
    public synchronized void add(Listing listing) {
        listings.add(listing);
    }

    @Override
    public synchronized boolean remove(UUID id) {
        return listings.removeIf(listing -> listing.id().equals(id));
    }

    @Override
    public synchronized Listing find(UUID id) {
        return listings.stream().filter(listing -> listing.id().equals(id)).findFirst().orElse(null);
    }

    @Override
    public synchronized void save() throws IOException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM auction_listings");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO auction_listings
                        (id, seller_uuid, seller_name, item_data, price, created_at, expires_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (Listing listing : listings) {
                        insert.setString(1, listing.id().toString());
                        insert.setString(2, listing.sellerId().toString());
                        insert.setString(3, listing.sellerName());
                        insert.setString(4, serializeItem(listing.item()));
                        insert.setDouble(5, listing.price());
                        insert.setLong(6, listing.createdAt());
                        insert.setLong(7, listing.expiresAt());
                        insert.setString(8, listing.status().name());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                if (exception instanceof IOException ioException) throw ioException;
                throw new IOException("SQLite transaction failed; previous database contents were rolled back.", exception);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // Connection is closing; the transaction outcome was already handled above.
                }
            }
        } catch (SQLException exception) {
            throw new IOException("Could not save SQLite auction database. Inspect server logs and keep a backup.", exception);
        }
    }

    private Connection connect() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC driver is missing from the plugin JAR.", exception);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
        }
        return connection;
    }

    private static String serializeItem(ItemStack item) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(item);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static ItemStack deserializeItem(String encoded) throws IOException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid item data encoding.", exception);
        }
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object object = input.readObject();
            if (!(object instanceof ItemStack item)) throw new IOException("Stored escrow object is not an ItemStack.");
            return item;
        } catch (ClassNotFoundException exception) {
            throw new IOException("Could not decode stored escrow item.", exception);
        }
    }
}
