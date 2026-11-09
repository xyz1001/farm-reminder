package com.local.farmreminder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ReminderStore {
    static final Object LOCK = new Object();
    private static final FrameCommitPolicy.Durability DURABILITY = new FrameCommitPolicy.Durability();
    // Guarded by LOCK; bound repeated tick failures independently for each fixed stage.
    private static final long[] RETENTION_LOG_AT = {-1, -1, -1};
    private static final String RECEIPTS = "frameReceipts", CHECKPOINT = "frameCheckpoint";
    private final Context context;
    private final SharedPreferences prefs;
    public ReminderStore(Context context) { this.context = context.getApplicationContext(); prefs = this.context.getSharedPreferences("reminders", Context.MODE_PRIVATE); }

    private boolean commit(SharedPreferences.Editor editor) {
        // Force a disk generation even when re-submitting an in-memory failed write.
        editor.putString("frameCommitGeneration", UUID.randomUUID().toString());
        return DURABILITY.commit(editor::commit);
    }
    private boolean repairIfDirty() { return !DURABILITY.dirty || commit(prefs.edit()); }
    public List<Reminder> list() { synchronized (LOCK) { try { return readStrict(); } catch (Exception e) { return new ArrayList<>(); } } }
    List<Reminder> readStrict() throws Exception {
        List<Reminder> result = new ArrayList<>();
        JSONArray a = new JSONArray(prefs.getString("items", "[]"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i); Reminder r = new Reminder();
            r.id = o.getString("id"); r.friend = o.optString("friend", ""); r.crop = o.optString("crop", "");
            r.avatarRef = AvatarRefPolicy.normalize(o.optString("avatarRef", ""));
            r.plateRef = NameplateRefPolicy.normalize(o.optString("plateRef", ""));
            r.sourceFrameRef = SourceFrameRefPolicy.normalize(o.optString("sourceFrameRef", ""));
            r.matureAt = o.getLong("matureAt"); r.triggerAt = o.getLong("triggerAt"); r.fired = o.optBoolean("fired"); result.add(r);
        }
        return result;
    }
    private String serialize(List<Reminder> items) throws Exception {
        JSONArray a = new JSONArray();
        for (Reminder r : items) {
            JSONObject o = new JSONObject(); o.put("id", r.id); o.put("friend", r.friend); o.put("crop", r.crop);
            o.put("matureAt", r.matureAt); o.put("triggerAt", r.triggerAt); o.put("fired", r.fired); o.put("avatarRef", AvatarRefPolicy.normalize(r.avatarRef));
            o.put("sourceFrameRef", SourceFrameRefPolicy.normalize(r.sourceFrameRef));
            o.put("plateRef", NameplateRefPolicy.normalize(r.plateRef)); a.put(o);
        }
        return a.toString();
    }
    private boolean write(List<Reminder> items) throws Exception { return commit(prefs.edit().putString("items", serialize(items))); }
    boolean updateLead(int seconds) { synchronized (LOCK) {
        if (!ReminderLeadPolicy.valid(seconds)) return false;
        SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String previousItems = null;
        int previousLead = settings.getInt("leadSeconds", 60);
        boolean settingsAttempted = false;
        try {
            if (!repairIfDirty()) return false;
            List<Reminder> items = readStrict();
            previousItems = prefs.getString("items", "[]");
            ReminderLeadPolicy.update(items, seconds);
            if (!write(items)) {
                commit(prefs.edit().putString("items", previousItems));
                return false;
            }
            settingsAttempted = true;
            if (!settings.edit().putInt("leadSeconds", seconds).commit()) {
                settings.edit().putInt("leadSeconds", previousLead).commit();
                commit(prefs.edit().putString("items", previousItems));
                return false;
            }
            try { new AlarmScheduler(context).rescheduleAll(); } catch (RuntimeException e) {
                Log.w("FarmReminder", "Lead updated but alarm reconciliation failed", e);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w("FarmReminder", "Lead update failed", e);
            try {
                if (settingsAttempted) settings.edit().putInt("leadSeconds", previousLead).commit();
                if (previousItems != null) commit(prefs.edit().putString("items", previousItems));
            } catch (RuntimeException rollback) { Log.w("FarmReminder", "Lead rollback failed", rollback); }
            return false;
        }
    } }
    public int pruneExpired(long now) { synchronized (LOCK) {
        return ReminderRetentionPolicy.prune(new ReminderRetentionPolicy.Storage() {
            public boolean repair() { return repairIfDirty(); }
            public List<Reminder> read() throws Exception { return readStrict(); }
            public boolean write(List<Reminder> items) throws Exception { return ReminderStore.this.write(items); }
            public void failure(String stage, Throwable cause) {
                int index = stage.equals("REPAIR_FAILED") ? 0 : stage.equals("READ_FAILED") ? 1 : 2;
                long now = SystemClock.elapsedRealtime();
                if (RETENTION_LOG_AT[index] >= 0 && now - RETENTION_LOG_AT[index] < 60_000L) return;
                RETENTION_LOG_AT[index] = now;
                Log.w("FarmRetention", stage + " " + (cause == null ? "none" : cause.getClass().getName()));
            }
        }, now);
    } }
    public void delete(String id) { synchronized (LOCK) { try {
        if (!repairIfDirty()) return; List<Reminder> items = readStrict(); items.removeIf(r -> r.id.equals(id));
        if (write(items)) { new AlarmScheduler(context).cancel(id); new AlarmScheduler(context).rescheduleAll(); }
    } catch (Exception ignored) { } } }
    public void clearFired() { synchronized (LOCK) { try {
        if (!repairIfDirty()) return; List<Reminder> items = readStrict(); List<String> ids = new ArrayList<>();
        for (Reminder r : items) if (r.fired) ids.add(r.id); items.removeIf(r -> r.fired);
        if (write(items)) { for (String id : ids) new AlarmScheduler(context).cancel(id); new AlarmScheduler(context).rescheduleAll(); }
    } catch (Exception ignored) { } } }
    static final class AddResult {
        final SaveOutcome outcome; final Reminder reminder;
        AddResult(SaveOutcome outcome, Reminder reminder) { this.outcome = outcome; this.reminder = reminder; }
    }
    AddResult addIfNew(String friend, long matureAt, int leadSeconds) {
        synchronized (LOCK) { try {
            if (!repairIfDirty()) return new AddResult(SaveOutcome.FAILED, null);
            List<Reminder> items = readStrict();
            if (RecognitionLogic.isDuplicate(items, friend, matureAt)) return new AddResult(SaveOutcome.DUPLICATE, null);
            Reminder r = makeReminder("好友", matureAt, leadSeconds); items.add(r);
            boolean ok = write(items);
            if (ok) try { new AlarmScheduler(context).rescheduleAll(); } catch (RuntimeException ignored) { }
            return new AddResult(SaveOutcome.fromCommit(ok), ok ? r : null);
        } catch (Exception e) { return new AddResult(SaveOutcome.FAILED, null); } }
    }
    Reminder markFired(String id) { synchronized (LOCK) { try {
        if (!repairIfDirty()) return null; List<Reminder> items = readStrict();
        for (Reminder r : items) if (r.id.equals(id) && !r.fired) { r.fired = true; return write(items) ? r : null; }
    } catch (Exception ignored) { } return null; } }

    boolean markGroupFired(List<Reminder> group) { synchronized (LOCK) { try {
        if (!repairIfDirty()) return false;
        List<Reminder> items = readStrict();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Reminder r : group) ids.add(r.id);
        for (Reminder r : items) if (ids.contains(r.id)) r.fired = true;
        return write(items);
    } catch (Exception ignored) { return false; } } }

    boolean ready() { synchronized (LOCK) { try { if (!repairIfDirty()) return false; readStrict(); return true; } catch (Exception e) { return false; } } }

    static final class FrameOutcome {
        final boolean committed, replay, confirmed, added;
        final Reminder reminder; final List<Reminder> reminders; final String code;
        FrameOutcome(boolean committed, boolean replay, boolean confirmed, boolean added, List<Reminder> reminders, String code) {
            this.committed = committed; this.replay = replay; this.confirmed = confirmed; this.added = added;
            this.reminders = java.util.Collections.unmodifiableList(new ArrayList<>(reminders));
            this.reminder = reminders.isEmpty() ? null : reminders.get(0); this.code = code;
        }
    }
    private static Reminder makeReminder(String friend, long maturity, int leadSeconds) {
        Reminder r = new Reminder(); r.id = UUID.randomUUID().toString(); r.friend = friend; r.crop = "";
        r.matureAt = maturity; r.triggerAt = maturity - Math.max(0, leadSeconds) * 1000L; return r;
    }
    private JSONObject readReceipts() throws Exception {
        JSONObject receipts = new JSONObject(prefs.getString(RECEIPTS, "{}"));
        java.util.Iterator<String> keys = receipts.keys();
        while (keys.hasNext()) {
            String key = keys.next(); JSONObject receipt = receipts.getJSONObject(key);
            String code = receipt.getString("code"), id = receipt.getString("reminderId");
            if (key.isEmpty() || !(code.equals("NO_MATCH") || code.equals("CANDIDATE") || code.equals("ADDED") || code.equals("DUPLICATE"))
                    || ((code.equals("ADDED") || code.equals("DUPLICATE")) != !id.isEmpty())) throw new IllegalArgumentException("invalid receipt");
        }
        return receipts;
    }
    private FrameTransaction.Adapter frameAdapter() {
        return new FrameTransaction.Adapter() {
            public boolean repair() { return repairIfDirty(); }
            public FrameTransaction.State read() throws Exception {
                FrameTransaction.State state = new FrameTransaction.State(); state.items.addAll(readStrict());
                JSONObject receipts = readReceipts(); java.util.Iterator<String> keys = receipts.keys();
                while (keys.hasNext()) { String id = keys.next(); JSONObject r = receipts.getJSONObject(id);
                    JSONArray ids = r.optJSONArray("reminderIds");
                    if (ids == null) state.receipts.put(id, new FrameTransaction.Receipt(r.getString("code"), r.getString("reminderId")));
                    else { List<String> values = new ArrayList<>(); for (int i = 0; i < ids.length(); i++) values.add(ids.getString(i));
                        state.receipts.put(id, new FrameTransaction.Receipt(r.getString("code"), values)); }
                }
                // A stale or corrupt legacy checkpoint must not prevent a new frame from committing.
                try { state.checkpoint = FrameCommitPolicy.Checkpoint.decode(prefs.getString(CHECKPOINT, null)); } catch (Exception ignored) { }
                return state;
            }
            public boolean write(FrameTransaction.State state) throws Exception {
                JSONObject receipts = new JSONObject();
                for (java.util.Map.Entry<String, FrameTransaction.Receipt> entry : state.receipts.entrySet()) {
                    JSONObject value = new JSONObject(); value.put("code", entry.getValue().code); value.put("reminderId", entry.getValue().reminderId);
                    value.put("reminderIds", new JSONArray(entry.getValue().reminderIds));
                    receipts.put(entry.getKey(), value);
                }
                String items = serialize(state.items), checkpoint = state.checkpoint == null ? null : state.checkpoint.encode();
                SharedPreferences.Editor editor = prefs.edit().putString("items", items).putString(RECEIPTS, receipts.toString());
                if (checkpoint == null) editor.remove(CHECKPOINT); else editor.putString(CHECKPOINT, checkpoint);
                return commit(editor);
            }
        };
    }
    FrameOutcome commitFrame(DiskFrameStore.Frame frame, String friend, Long countdownMs, int leadSeconds) {
        return commitFrame(frame, friend, countdownMs == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(countdownMs), leadSeconds);
    }
    FrameOutcome commitFrame(DiskFrameStore.Frame frame, String friend, List<Long> countdownMs, int leadSeconds) {
        return commitFrame(frame, countdownMs, leadSeconds, "");
    }
    boolean mayAddFrame(DiskFrameStore.Frame frame, List<Long> countdowns) { synchronized (LOCK) {
        try {
            if (frame == null || !repairIfDirty() || readReceipts().has(frame.id)) return false;
            MaturityIdentity identity = new MaturityIdentity(readStrict());
            for (long maturity : MaturityIdentity.maturities(frame.captureWallMs, countdowns)) if (identity.match(maturity) == null) return true;
        } catch (Exception ignored) { }
        return false;
    } }
    FrameOutcome commitFrame(DiskFrameStore.Frame frame, List<Long> countdownMs, int leadSeconds, String avatarRef) {
        return commitFrame(frame, countdownMs, leadSeconds, avatarRef, "");
    }
    FrameOutcome commitFrame(DiskFrameStore.Frame frame, List<Long> countdownMs, int leadSeconds, String avatarRef, String plateRef) {
        synchronized (LOCK) {
            if (frame == null) return new FrameOutcome(false, false, false, false, java.util.Collections.emptyList(), "STORAGE_FAILED");
            int currentLead = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("leadSeconds", 60);
            if (!ReminderLeadPolicy.valid(currentLead)) currentLead = 60;
            FrameTransaction.Result r = FrameTransaction.commit(frameAdapter(), frame.id, frame.sessionId, frame.windowId,
                    frame.captureSequence, frame.captureUptimeMs, frame.captureWallMs, countdownMs, currentLead, avatarRef, plateRef);
            if (r.committed && r.added) try { new AlarmScheduler(context).rescheduleAll(); } catch (RuntimeException ignored) { }
            return new FrameOutcome(r.committed, r.replay, r.confirmed, r.added, r.reminders, r.code);
        }
    }
    boolean forgetFrameReceipt(String frameId) { synchronized (LOCK) {
        return FrameTransaction.forgetAfterAck(frameAdapter(), frameId);
    } }
}
