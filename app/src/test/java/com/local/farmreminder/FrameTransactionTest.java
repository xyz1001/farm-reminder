package com.local.farmreminder;

import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** In-memory adapter models Android's failed-commit cache semantics, not disk durability. */
public class FrameTransactionTest {
    static final class Shared {
        FrameTransaction.State memory = new FrameTransaction.State(), disk = new FrameTransaction.State();
        final FrameCommitPolicy.Durability durability = new FrameCommitPolicy.Durability();
        boolean failWrite, failRead;
        int writes;
        String unrelated = "preserve";
    }
    static FrameTransaction.State copy(FrameTransaction.State source) throws Exception {
        FrameTransaction.State target = new FrameTransaction.State();
        for (Reminder r : source.items) {
            Reminder n = new Reminder(); n.id = r.id; n.friend = r.friend; n.crop = r.crop;
            n.matureAt = r.matureAt; n.triggerAt = r.triggerAt; n.fired = r.fired; target.items.add(n);
        }
        for (Map.Entry<String, FrameTransaction.Receipt> e : source.receipts.entrySet()) target.receipts.put(e.getKey(), e.getValue());
        if (source.checkpoint != null) target.checkpoint = FrameCommitPolicy.Checkpoint.decode(source.checkpoint.encode());
        return target;
    }
    static final class Adapter implements FrameTransaction.Adapter {
        final Shared shared;
        Adapter(Shared shared) { this.shared = shared; }
        public boolean repair() {
            if (!shared.durability.dirty) return true;
            return persist(shared.memory);
        }
        public FrameTransaction.State read() throws Exception {
            if (shared.failRead) throw new IllegalStateException("corrupt state");
            return copy(shared.memory);
        }
        public boolean write(FrameTransaction.State state) { return persist(state); }
        private boolean persist(FrameTransaction.State state) {
            try {
                shared.memory = copy(state); shared.writes++;
                return shared.durability.commit(() -> {
                    if (shared.failWrite) return false;
                    try { shared.disk = copy(shared.memory); return true; } catch (Exception e) { throw new IllegalStateException(e); }
                });
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
    static FrameTransaction.Result frame(Adapter adapter, String id, long seq, String friend, Long countdown) {
        return FrameTransaction.commit(adapter, id, "session", 7, seq, seq * 100, 1000 + seq * 100, friend, countdown, 10);
    }
    static void reopen(Shared s) throws Exception {
        s.memory = copy(s.disk); s.durability.dirty = false;
    }

    @Test public void checkpointReopenReplayDeleteAndAck() throws Exception {
        Shared shared = new Shared(); Adapter first = new Adapter(shared);
        FrameTransaction.Result candidate = frame(first, "one", 1, "Alice", 10000L);
        assertTrue(candidate.committed); assertTrue(candidate.confirmed); assertTrue(candidate.added); assertEquals("ADDED", candidate.code);
        reopen(shared); Adapter reopened = new Adapter(shared);
        FrameTransaction.Result added = frame(reopened, "two", 2, "Alice", 9900L);
        assertTrue(added.committed); assertTrue(added.confirmed); assertFalse(added.added);
        assertEquals(11100, added.reminder.matureAt);
        String id = added.reminder.id;
        reopen(shared); Adapter afterCrash = new Adapter(shared);
        int writes = shared.writes;
        FrameTransaction.Result replay = frame(afterCrash, "two", 99, "different", 900000L);
        assertTrue(replay.committed); assertTrue(replay.replay); assertFalse(replay.added); assertFalse(replay.confirmed);
        assertEquals(id, replay.reminder.id); assertEquals(writes, shared.writes);
        // Model the user's separately committed deletion without touching the receipt.
        FrameTransaction.State deleted = afterCrash.read(); deleted.items.clear(); assertTrue(afterCrash.write(deleted));
        reopen(shared); Adapter afterDelete = new Adapter(shared);
        FrameTransaction.Result deletedReplay = frame(afterDelete, "two", 2, "Alice", 9900L);
        assertTrue(deletedReplay.committed); assertTrue(deletedReplay.replay); assertNull(deletedReplay.reminder);
        assertTrue(shared.memory.items.isEmpty()); assertTrue(shared.memory.receipts.containsKey("two"));
        // Only the caller that durably ACKed the disk frame may invoke this operation.
        assertTrue(FrameTransaction.forgetAfterAck(afterDelete, "two"));
        assertFalse(shared.memory.receipts.containsKey("two"));
        assertTrue(shared.memory.receipts.containsKey("one"));
    }

    @Test public void failedCachedReceiptRequiresSharedFullRecommitAcrossAdapters() throws Exception {
        Shared shared = new Shared(); Adapter a = new Adapter(shared);
        assertTrue(frame(a, "one", 1, "Alice", 10000L).committed);
        shared.failWrite = true;
        FrameTransaction.Result failed = frame(a, "two", 2, "Alice", 9900L);
        assertFalse(failed.committed); assertFalse(failed.added); assertTrue(shared.memory.receipts.containsKey("two"));
        assertFalse(shared.disk.receipts.containsKey("two")); assertTrue(shared.durability.dirty);
        Adapter b = new Adapter(shared); // Mirrors another ReminderStore sharing process state.
        FrameTransaction.Result stillFailed = frame(b, "two", 2, "Alice", 9900L);
        assertFalse(stillFailed.committed); assertFalse(stillFailed.replay); assertTrue(shared.durability.dirty);
        shared.failWrite = false;
        FrameTransaction.Result recovered = frame(b, "two", 2, "Alice", 9900L);
        assertTrue(recovered.committed); assertTrue(recovered.replay); assertFalse(recovered.added);
        assertFalse(shared.durability.dirty); assertTrue(shared.disk.receipts.containsKey("two"));
        assertEquals(1, shared.disk.items.size()); assertEquals("preserve", shared.unrelated);
    }

    @Test public void strictReadFailureDoesNotWriteOrEraseState() throws Exception {
        Shared shared = new Shared(); Adapter adapter = new Adapter(shared);
        assertTrue(frame(adapter, "one", 1, "Alice", 10000L).committed);
        assertTrue(frame(adapter, "two", 2, "Alice", 9900L).committed);
        int writes = shared.writes;
        shared.failRead = true;
        assertFalse(frame(adapter, "three", 3, "Alice", 9800L).committed);
        assertFalse(FrameTransaction.forgetAfterAck(adapter, "two"));
        assertEquals(writes, shared.writes); assertEquals(1, shared.memory.items.size());
        assertTrue(shared.memory.receipts.containsKey("two"));
        assertEquals("preserve", shared.unrelated);
    }

    @Test public void batchIsAtomicAndReplayRetainsEveryReminder() throws Exception {
        Shared shared = new Shared(); Adapter adapter = new Adapter(shared);
        FrameTransaction.Result first = FrameTransaction.commit(adapter, "batch", "s", 1, 1, 1, 1000,
                "Alice", Arrays.asList(10000L, 14000L), 10);
        assertTrue(first.confirmed); assertTrue(first.added); assertEquals(2, first.reminders.size());
        assertEquals(2, shared.disk.items.size());
        reopen(shared);
        FrameTransaction.Result replay = FrameTransaction.commit(adapter, "batch", "s", 1, 1, 1, 1000,
                "Alice", Arrays.asList(10000L, 14000L), 10);
        assertTrue(replay.replay); assertFalse(replay.added); assertFalse(replay.confirmed);
        assertEquals(2, replay.reminders.size()); assertEquals(2, shared.disk.items.size());
    }

    @Test public void existingFirstAndNewSecondAndZeroTime() {
        Shared shared = new Shared(); Adapter adapter = new Adapter(shared);
        assertTrue(FrameTransaction.commit(adapter, "first", "s", 1, 1, 1, 1000,
                "Alice", 10000L, 10).added);
        FrameTransaction.Result batch = FrameTransaction.commit(adapter, "batch", "other", 9, 99, 21000, 1000,
                "Alice", Arrays.asList(10000L, 30000L), 10);
        assertTrue(batch.added); assertEquals(2, batch.reminders.size()); assertEquals(2, shared.disk.items.size());
        assertFalse(FrameTransaction.commit(adapter, "bad", "s", 1, 2, 2, 1000,
                null, Collections.singletonList(0L), 10).confirmed);
        FrameTransaction.Result zero = FrameTransaction.commit(adapter, "zero", "s", 1, 3, 3, 1000,
                "Bob", Collections.singletonList(0L), 10);
        assertTrue(zero.added); assertEquals(1000, zero.reminder.matureAt);
        assertEquals(2001, Reminder.scheduledTime(zero.reminder, 1001));
    }

    @Test public void failedBatchRepairPersistsAllBeforeReplay() {
        Shared shared = new Shared(); Adapter adapter = new Adapter(shared); shared.failWrite = true;
        assertFalse(FrameTransaction.commit(adapter, "batch", "s", 1, 1, 1, 1000,
                "Alice", Arrays.asList(10000L, 14000L), 10).committed);
        assertEquals(0, shared.disk.items.size()); assertEquals(2, shared.memory.items.size());
        shared.failWrite = false;
        FrameTransaction.Result replay = FrameTransaction.commit(adapter, "batch", "s", 1, 1, 1, 1000,
                "Alice", Arrays.asList(10000L, 14000L), 10);
        assertTrue(replay.replay); assertEquals(2, replay.reminders.size()); assertEquals(2, shared.disk.items.size());
    }
}
