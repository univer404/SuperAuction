package com.superauction.auction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only durable journal groundwork for auction purchases.
 *
 * This journal is deliberately not wired to the purchase GUI yet: durable logging alone
 * does not make Vault economy calls and Minecraft inventory delivery atomic. A purchase
 * must remain disabled until reconciliation and idempotent delivery are implemented.
 */
public final class TransactionJournal {
    public enum Stage {
        PREPARED,
        BUYER_CHARGED,
        ITEM_DELIVERED,
        SELLER_PAID,
        COMPLETE,
        NEEDS_REVIEW
    }

    public record Transaction(
            UUID id,
            UUID buyerId,
            UUID sellerId,
            UUID listingId,
            double amount,
            Stage stage,
            long updatedAt
    ) { }

    private final Path file;
    private final Map<UUID, Transaction> latest = new LinkedHashMap<>();

    public TransactionJournal(Path file) {
        this.file = file;
    }

    /** Load and validate every journal line. Corruption fails closed instead of resetting history. */
    public synchronized void load() throws IOException {
        latest.clear();
        if (!Files.exists(file)) {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            return;
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isBlank()) continue;
            try {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 7) throw new IllegalArgumentException("expected 7 fields");
                Transaction next = new Transaction(
                        UUID.fromString(fields[0]),
                        UUID.fromString(fields[1]),
                        UUID.fromString(fields[2]),
                        UUID.fromString(fields[3]),
                        Double.parseDouble(fields[4]),
                        Stage.valueOf(fields[5]),
                        Long.parseLong(fields[6]));
                validate(next);
                Transaction previous = latest.get(next.id());
                if (previous == null) {
                    if (next.stage() != Stage.PREPARED) {
                        throw new IllegalArgumentException("first stage must be PREPARED");
                    }
                } else {
                    ensureSameTransaction(previous, next);
                    if (!allowed(previous.stage(), next.stage())) {
                        throw new IllegalArgumentException("invalid stage transition "
                                + previous.stage() + " -> " + next.stage());
                    }
                }
                latest.put(next.id(), next);
            } catch (RuntimeException exception) {
                throw new IOException("Transaction journal is invalid at line " + lineNumber
                        + ". Preserve the file and inspect it before restarting.", exception);
            }
        }
    }

    public synchronized Transaction begin(UUID buyerId, UUID sellerId, UUID listingId, double amount)
            throws IOException {
        if (buyerId == null || sellerId == null || listingId == null
                || !Double.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("Transaction IDs and a positive finite amount are required.");
        }
        Transaction transaction = new Transaction(
                UUID.randomUUID(), buyerId, sellerId, listingId, amount, Stage.PREPARED,
                System.currentTimeMillis());
        append(transaction);
        latest.put(transaction.id(), transaction);
        return transaction;
    }

    public synchronized Transaction advance(UUID transactionId, Stage nextStage) throws IOException {
        Transaction current = latest.get(transactionId);
        if (current == null) throw new IllegalArgumentException("Unknown transaction ID.");
        if (nextStage == null || !allowed(current.stage(), nextStage)) {
            throw new IllegalStateException("Invalid transaction transition from " + current.stage()
                    + " to " + nextStage + ".");
        }
        Transaction next = new Transaction(current.id(), current.buyerId(), current.sellerId(),
                current.listingId(), current.amount(), nextStage, System.currentTimeMillis());
        append(next);
        latest.put(next.id(), next);
        return next;
    }

    public synchronized Transaction find(UUID transactionId) {
        return latest.get(transactionId);
    }

    public synchronized List<Transaction> all() {
        return latest.values().stream()
                .sorted(Comparator.comparingLong(Transaction::updatedAt).reversed())
                .toList();
    }

    private void append(Transaction transaction) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        String line = transaction.id() + "\t" + transaction.buyerId() + "\t"
                + transaction.sellerId() + "\t" + transaction.listingId() + "\t"
                + Double.toString(transaction.amount()) + "\t" + transaction.stage().name()
                + "\t" + transaction.updatedAt() + System.lineSeparator();
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void validate(Transaction transaction) {
        if (transaction.id() == null || transaction.buyerId() == null || transaction.sellerId() == null
                || transaction.listingId() == null || transaction.stage() == null
                || !Double.isFinite(transaction.amount()) || transaction.amount() <= 0
                || transaction.updatedAt() <= 0) {
            throw new IllegalArgumentException("invalid transaction fields");
        }
    }

    private static void ensureSameTransaction(Transaction previous, Transaction next) {
        if (!previous.buyerId().equals(next.buyerId())
                || !previous.sellerId().equals(next.sellerId())
                || !previous.listingId().equals(next.listingId())
                || Double.compare(previous.amount(), next.amount()) != 0) {
            throw new IllegalArgumentException("transaction identity or amount changed");
        }
    }

    private static boolean allowed(Stage current, Stage next) {
        if (current == Stage.COMPLETE || current == Stage.NEEDS_REVIEW) return false;
        if (next == Stage.NEEDS_REVIEW) return true;
        return switch (current) {
            case PREPARED -> next == Stage.BUYER_CHARGED;
            case BUYER_CHARGED -> next == Stage.ITEM_DELIVERED;
            case ITEM_DELIVERED -> next == Stage.SELLER_PAID;
            case SELLER_PAID -> next == Stage.COMPLETE;
            case COMPLETE, NEEDS_REVIEW -> false;
        };
    }
}
