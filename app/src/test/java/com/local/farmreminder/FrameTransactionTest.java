package com.local.farmreminder;

import java.util.Map;
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
        assertTrue(candidate.committed); assertEquals("CANDIDATE", candidate.code);
        reopen(shared); Adapter reopened = new Adapter(shared);
        FrameTransaction.Result added = frame(reopened, "two", 2, "Alice", 9900L);
        assertTrue(added.committed); assertTrue(added.confirmed); assertTrue(added.added);
        assertEquals(11100, added.reminder.matureAt);
        String id = added.reminder.id, checkpointFrame = shared.memory.checkpoint.frameId;
        reopen(shared); Adapter afterCrash = new Adapter(shared);
        int writes = shared.writes;
        FrameTransaction.Result replay = frame(afterCrash, "two", 99, "different", 900000L);
        assertTrue(replay.committed); assertTrue(replay.replay); assertFalse(replay.added); assertFalse(replay.confirmed);
        assertEquals(id, replay.reminder.id); assertEquals(writes, shared.writes);
        assertEquals(checkpointFrame, shared.memory.checkpoint.frameId); assertEquals(2, shared.memory.checkpoint.count);
        // Model the user's separately committed deletion without touching the receipt.
        FrameTransaction.State deleted = afterCrash.read(); deleted.items.clear(); assertTrue(afterCrash.write(deleted));
        reopen(shared); Adapter afterDelete = new Adapter(shared);
        FrameTransaction.Result deletedReplay = frame(afterDelete, "two", 2, "Alice", 9900L);
        assertTrue(deletedReplay.committed); assertTrue(deletedReplay.replay); assertNull(deletedReplay.reminder);
        assertTrue(shared.memory.items.isEmpty()); assertTrue(shared.memory.receipts.containsKey("two"));
        // Only the caller that durably ACKed the disk frame may invoke this operation.
        assertTrue(FrameTransaction.forgetAfterAck(afterDelete, "two"));
        assertFalse(shared.memory.receipts.containsKey("two")); assertEquals(checkpointFrame, shared.memory.checkpoint.frameId);
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
        int writes = shared.writes; String checkpoint = shared.memory.checkpoint.frameId;
        shared.failRead = true;
        assertFalse(frame(adapter, "three", 3, "Alice", 9800L).committed);
        assertFalse(FrameTransaction.forgetAfterAck(adapter, "two"));
        assertEquals(writes, shared.writes); assertEquals(1, shared.memory.items.size());
        assertTrue(shared.memory.receipts.containsKey("two")); assertEquals(checkpoint, shared.memory.checkpoint.frameId);
        assertEquals("preserve", shared.unrelated);
    }
}
