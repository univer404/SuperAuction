package com.superauction.auction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionJournalSafetyTest {
    @TempDir Path directory;

    @Test
    void acceptsOnlyTheExpectedForwardPath() throws IOException {
        TransactionJournal journal = new TransactionJournal(directory.resolve("transactions.log"));
        journal.load();
        var transaction = journal.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 50.0);

        journal.advance(transaction.id(), TransactionJournal.Stage.BUYER_CHARGED);
        journal.advance(transaction.id(), TransactionJournal.Stage.ITEM_DELIVERED);
        journal.advance(transaction.id(), TransactionJournal.Stage.SELLER_PAID);
        journal.advance(transaction.id(), TransactionJournal.Stage.COMPLETE);

        assertEquals(TransactionJournal.Stage.COMPLETE, journal.find(transaction.id()).stage());
        assertThrows(IllegalStateException.class,
                () -> journal.advance(transaction.id(), TransactionJournal.Stage.NEEDS_REVIEW));
    }

    @Test
    void interruptedTransactionsAreMarkedForReviewAndNeverReplayed() throws IOException {
        Path file = directory.resolve("interrupted.log");
        TransactionJournal journal = new TransactionJournal(file);
        journal.load();
        var transaction = journal.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 42.0);
        journal.advance(transaction.id(), TransactionJournal.Stage.BUYER_CHARGED);

        assertEquals(1, journal.markIncompleteForReview());
        assertEquals(TransactionJournal.Stage.NEEDS_REVIEW, journal.find(transaction.id()).stage());
        assertEquals(0, journal.markIncompleteForReview());
        assertThrows(IllegalStateException.class,
                () -> journal.advance(transaction.id(), TransactionJournal.Stage.ITEM_DELIVERED));

        TransactionJournal reloaded = new TransactionJournal(file);
        reloaded.load();
        assertEquals(TransactionJournal.Stage.NEEDS_REVIEW, reloaded.find(transaction.id()).stage());
    }

    @Test
    void allowsOnlyPreparedTransactionsToBeAborted() throws IOException {
        TransactionJournal journal = new TransactionJournal(directory.resolve("abort.log"));
        journal.load();
        var transaction = journal.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 12.0);
        journal.advance(transaction.id(), TransactionJournal.Stage.ABORTED);
        assertEquals(TransactionJournal.Stage.ABORTED, journal.find(transaction.id()).stage());
        assertThrows(IllegalStateException.class,
                () -> journal.advance(transaction.id(), TransactionJournal.Stage.BUYER_CHARGED));
    }

    @Test
    void refusesInvalidAmountsBeforeWritingAnything() throws IOException {
        Path file = directory.resolve("transactions.log");
        TransactionJournal journal = new TransactionJournal(file);
        journal.load();

        assertThrows(IllegalArgumentException.class,
                () -> journal.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> journal.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> journal.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0));

        assertFalse(java.nio.file.Files.exists(file));
        assertTrue(journal.all().isEmpty());
    }

    @Test
    void refusesAnyTransitionOutOfManualReview() throws IOException {
        TransactionJournal journal = new TransactionJournal(directory.resolve("transactions.log"));
        journal.load();
        var transaction = journal.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 25.0);
        journal.advance(transaction.id(), TransactionJournal.Stage.NEEDS_REVIEW);

        assertThrows(IllegalStateException.class,
                () -> journal.advance(transaction.id(), TransactionJournal.Stage.BUYER_CHARGED));
        assertThrows(IllegalStateException.class,
                () -> journal.advance(transaction.id(), TransactionJournal.Stage.COMPLETE));
    }
}
