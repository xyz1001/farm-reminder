package com.local.farmreminder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;

/** Business transaction; the adapter must atomically persist the complete state. */
final class FrameTransaction {
    static final class Receipt {
        final String code, reminderId;
        final List<String> reminderIds;
        Receipt(String code, String reminderId) { this(code, reminderId.isEmpty() ? Collections.emptyList() : Collections.singletonList(reminderId)); }
        Receipt(String code, List<String> ids) {
            this.code = code; this.reminderIds = new ArrayList<>(ids);
            this.reminderId = ids.isEmpty() ? "" : ids.get(0);
        }
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
        final List<Reminder> reminders;
        final String code;
        Result(boolean committed, boolean replay, boolean confirmed, boolean added, List<Reminder> reminders, String code) {
            this.committed = committed; this.replay = replay; this.confirmed = confirmed;
            this.added = added; this.reminders = Collections.unmodifiableList(new ArrayList<>(reminders));
            this.reminder = reminders.isEmpty() ? null : reminders.get(0); this.code = code;
        }
    }
    private static Result failed() { return new Result(false, false, false, false, Collections.emptyList(), "STORAGE_FAILED"); }

    static Result commit(Adapter adapter, String id, String session, int window, long sequence, long uptime,
                         long wall, String friend, Long countdown, int leadSeconds) {
        return commit(adapter, id, session, window, sequence, uptime, wall, friend,
                countdown == null ? Collections.emptyList() : Collections.singletonList(countdown), leadSeconds);
    }
    static Result commit(Adapter adapter, String id, String session, int window, long sequence, long uptime,
                         long wall, String friend, List<Long> countdowns, int leadSeconds) {
        try {
            if (!adapter.repair() || id == null || id.isEmpty()) return failed();
            State state = adapter.read();
            Receipt receipt = state.receipts.get(id);
            if (receipt != null) {
                List<Reminder> found = new ArrayList<>();
                for (String reminderId : receipt.reminderIds)
                    for (Reminder r : state.items) if (r.id.equals(reminderId)) { found.add(r); break; }
                return new Result(true, true, false, false, found, "REPLAY");
            }
            boolean confirmed = false, added = false;
            List<Reminder> results = new ArrayList<>(), existing = new ArrayList<>(state.items);
            List<Long> seen = new ArrayList<>();
            if (countdowns != null) for (Long countdown : countdowns) {
                FrameCommitPolicy.Checkpoint next = FrameCommitPolicy.reduce(null, id, session, window, sequence, uptime, wall, friend, countdown);
                if (next.count == 0 || seen.contains(countdown)) continue;
                seen.add(countdown); confirmed = true;
                Reminder reminder = null;
                for (Reminder r : existing) if (RecognitionLogic.isDuplicate(Collections.singletonList(r), next.friend, next.maturity)) { reminder = r; break; }
                if (reminder == null) {
                    reminder = new Reminder(); reminder.id = UUID.randomUUID().toString(); reminder.friend = next.friend; reminder.crop = "";
                    reminder.matureAt = next.maturity; reminder.triggerAt = next.maturity - Math.max(0, leadSeconds) * 1000L;
                    state.items.add(reminder); added = true;
                }
                if (!results.contains(reminder)) results.add(reminder);
            }
            String code = !confirmed ? "NO_MATCH" : added ? "ADDED" : "DUPLICATE";
            List<String> ids = new ArrayList<>(); for (Reminder r : results) ids.add(r.id);
            state.receipts.put(id, new Receipt(code, ids));
            // Old checkpoints may be read but never influence admission.
            if (!adapter.write(state)) return failed();
            return new Result(true, false, confirmed, added, results, code);
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
