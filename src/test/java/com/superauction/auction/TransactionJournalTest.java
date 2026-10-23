package com.superauction.auction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionJournalTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReloadsTransactionStages() throws IOException {
        Path file = temporaryDirectory.resolve("transactions.log");
        TransactionJournal journal = new TransactionJournal(file);
        journal.load();

        TransactionJournal.Transaction created = journal.begin(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 125.50);
        journal.advance(created.id(), TransactionJournal.Stage.BUYER_CHARGED);
        journal.advance(created.id(), TransactionJournal.Stage.NEEDS_REVIEW);

        TransactionJournal reloaded = new TransactionJournal(file);
        reloaded.load();

        assertEquals(TransactionJournal.Stage.NEEDS_REVIEW, reloaded.find(created.id()).stage());
        assertEquals(1, reloaded.all().size());
    }

    @Test
    void rejectsSkippingRequiredStages() throws IOException {
        TransactionJournal journal = new TransactionJournal(temporaryDirectory.resolve("transactions.log"));
        journal.load();
        TransactionJournal.Transaction created = journal.begin(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10.0);

        assertThrows(IllegalStateException.class,
                () -> journal.advance(created.id(), TransactionJournal.Stage.COMPLETE));
    }

    @Test
    void refusesToLoadCorruptedJournal() throws IOException {
        Path file = temporaryDirectory.resolve("transactions.log");
        Files.writeString(file, "this-is-not-a-valid-journal-row\n");

        TransactionJournal journal = new TransactionJournal(file);
        assertThrows(IOException.class, journal::load);
    }

    @Test
    void refusesToChangeTransactionIdentityBetweenStages() throws IOException {
        Path file = temporaryDirectory.resolve("transactions.log");
        TransactionJournal journal = new TransactionJournal(file);
        journal.load();
        TransactionJournal.Transaction created = journal.begin(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 25.0);

        String original = Files.readString(file);
        String[] fields = original.trim().split("\\t");
        fields[4] = "999.0";
        fields[5] = "BUYER_CHARGED";
        fields[6] = Long.toString(System.currentTimeMillis() + 1);
        Files.writeString(file, original + String.join("\t", fields) + System.lineSeparator());

        TransactionJournal reloaded = new TransactionJournal(file);
        assertThrows(IOException.class, reloaded::load);
        assertNotNull(created);
    }
}
