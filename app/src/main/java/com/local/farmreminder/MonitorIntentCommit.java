package com.local.farmreminder;

import java.io.IOException;
import java.util.Arrays;

/** Sync before publish, then verify the bytes actually visible through the atomic reader. */
final class MonitorIntentCommit {
    interface Transaction {
        void start() throws IOException;
        void write(byte[] bytes) throws IOException;
        void sync() throws IOException;
        void finish() throws IOException;
        void rollback() throws IOException;
        byte[] readPublished() throws IOException;
    }
    private MonitorIntentCommit() { }
    static void commit(Transaction transaction, byte[] expected) throws IOException {
        boolean active = false;
        try {
            transaction.start(); active = true;
            transaction.write(expected);
            transaction.sync();
            // finishWrite may close/publish even when it later throws: never roll back after entry.
            active = false;
            transaction.finish();
        } catch (IOException | RuntimeException failure) {
            if (active) {
                try { transaction.rollback(); }
                catch (IOException | RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            }
            throw new IOException("intent commit failed", failure);
        }
        final byte[] actual;
        try { actual = transaction.readPublished(); }
        catch (IOException | RuntimeException failure) { throw new IOException("published record unreadable", failure); }
        if (!Arrays.equals(expected, actual)) throw new IOException("published record mismatch");
    }
}
