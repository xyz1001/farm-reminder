package com.local.farmreminder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business transaction; the adapter must atomically persist the complete state. */
final class FrameTransaction {
    static final class Receipt {
        final String code, reminderId;
        Receipt(String code, String reminderId) { this.code = code; this.reminderId = reminderId; }
    }
    static final class State {
        final List<Reminder> items = new ArrayList<>();
        final Map<String, Receipt> receipts = new LinkedHashMap<>();
        FrameCommitPolicy.Checkpoint checkpoint;
    }
    interface Adapter {
        boolean repair();
        State read() throws Exception;
        boolean write(State state) throws Exception;
    }
    static final class Result {
        final boolean committed, replay, confirmed, added;
        final Reminder reminder;
        final String code;
        Result(boolean committed, boolean replay, boolean confirmed, boolean added, Reminder reminder, String code) {
            this.committed = committed; this.replay = replay; this.confirmed = confirmed;
            this.added = added; this.reminder = reminder; this.code = code;
        }
    }
    private static Result failed() { return new Result(false, false, false, false, null, "STORAGE_FAILED"); }

    static Result commit(Adapter adapter, String id, String session, int window, long sequence, long uptime,
                         long wall, String friend, Long countdown, int leadSeconds) {
        try {
            if (!adapter.repair() || id == null || id.isEmpty()) return failed();
            State state = adapter.read();
            Receipt receipt = state.receipts.get(id);
            if (receipt != null) {
                Reminder found = null;
                for (Reminder r : state.items) if (r.id.equals(receipt.reminderId)) { found = r; break; }
                return new Result(true, true, false, false, found, "REPLAY");
            }
            FrameCommitPolicy.Checkpoint previous = state.checkpoint;
            FrameCommitPolicy.Checkpoint next = FrameCommitPolicy.reduce(previous, id, session, window, sequence, uptime, wall, friend, countdown);
            boolean confirmed = next.count >= 2, added = false;
            Reminder reminder = null; String code = next.count == 0 ? "NO_MATCH" : "CANDIDATE";
            if (confirmed) {
                for (Reminder r : state.items) if (RecognitionLogic.isDuplicate(java.util.Collections.singletonList(r), next.friend, next.maturity)) { reminder = r; break; }
                if (reminder == null) {
                    reminder = new Reminder(); reminder.id = UUID.randomUUID().toString(); reminder.friend = next.friend; reminder.crop = "";
                    reminder.matureAt = next.maturity; reminder.triggerAt = next.maturity - Math.max(0, leadSeconds) * 1000L;
                    state.items.add(reminder); added = true; code = "ADDED";
                } else code = "DUPLICATE";
            }
            state.receipts.put(id, new Receipt(code, reminder == null ? "" : reminder.id));
            state.checkpoint = previous != null && previous.sessionId.equals(next.sessionId) && next.sequence <= previous.sequence ? previous : next;
            if (!adapter.write(state)) return failed();
            return new Result(true, false, confirmed, added, reminder, code);
        } catch (Exception e) { return failed(); }
    }
    /** Caller must first durably ACK the frame in the disk store. */
    static boolean forgetAfterAck(Adapter adapter, String id) {
        try {
            if (!adapter.repair() || id == null) return false;
            State state = adapter.read();
            if (!state.receipts.containsKey(id)) return true;
            state.receipts.remove(id); return adapter.write(state);
        } catch (Exception e) { return false; }
    }
}
