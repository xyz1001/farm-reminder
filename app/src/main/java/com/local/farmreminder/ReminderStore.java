package com.local.farmreminder;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ReminderStore {
    private static final Object LOCK = new Object();
    private static final FrameCommitPolicy.Durability DURABILITY = new FrameCommitPolicy.Durability();
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
    private List<Reminder> readStrict() throws Exception {
        List<Reminder> result = new ArrayList<>();
        JSONArray a = new JSONArray(prefs.getString("items", "[]"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i); Reminder r = new Reminder();
            r.id = o.getString("id"); r.friend = o.optString("friend", ""); r.crop = o.optString("crop", "");
            r.matureAt = o.getLong("matureAt"); r.triggerAt = o.getLong("triggerAt"); r.fired = o.optBoolean("fired"); result.add(r);
        }
        return result;
    }
    private String serialize(List<Reminder> items) throws Exception {
        JSONArray a = new JSONArray();
        for (Reminder r : items) {
            JSONObject o = new JSONObject(); o.put("id", r.id); o.put("friend", r.friend); o.put("crop", r.crop);
            o.put("matureAt", r.matureAt); o.put("triggerAt", r.triggerAt); o.put("fired", r.fired); a.put(o);
        }
        return a.toString();
    }
    private boolean write(List<Reminder> items) throws Exception { return commit(prefs.edit().putString("items", serialize(items))); }
    public void delete(String id) { synchronized (LOCK) { try {
        if (!repairIfDirty()) return; List<Reminder> items = readStrict(); items.removeIf(r -> r.id.equals(id));
        if (write(items)) new AlarmScheduler(context).cancel(id);
    } catch (Exception ignored) { } } }
    public void clearFired() { synchronized (LOCK) { try {
        if (!repairIfDirty()) return; List<Reminder> items = readStrict(); List<String> ids = new ArrayList<>();
        for (Reminder r : items) if (r.fired) ids.add(r.id); items.removeIf(r -> r.fired);
        if (write(items)) for (String id : ids) new AlarmScheduler(context).cancel(id);
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
            Reminder r = makeReminder(friend, matureAt, leadSeconds); items.add(r);
            boolean ok = write(items); return new AddResult(SaveOutcome.fromCommit(ok), ok ? r : null);
        } catch (Exception e) { return new AddResult(SaveOutcome.FAILED, null); } }
    }
    Reminder markFired(String id) { synchronized (LOCK) { try {
        if (!repairIfDirty()) return null; List<Reminder> items = readStrict();
        for (Reminder r : items) if (r.id.equals(id) && !r.fired) { r.fired = true; return write(items) ? r : null; }
    } catch (Exception ignored) { } return null; } }

    static final class FrameOutcome {
        final boolean committed, replay, confirmed, added;
        final Reminder reminder; final String code;
        FrameOutcome(boolean committed, boolean replay, boolean confirmed, boolean added, Reminder reminder, String code) {
            this.committed = committed; this.replay = replay; this.confirmed = confirmed; this.added = added; this.reminder = reminder; this.code = code;
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
                    state.receipts.put(id, new FrameTransaction.Receipt(r.getString("code"), r.getString("reminderId"))); }
                state.checkpoint = FrameCommitPolicy.Checkpoint.decode(prefs.getString(CHECKPOINT, null));
                return state;
            }
            public boolean write(FrameTransaction.State state) throws Exception {
                JSONObject receipts = new JSONObject();
                for (java.util.Map.Entry<String, FrameTransaction.Receipt> entry : state.receipts.entrySet()) {
                    JSONObject value = new JSONObject(); value.put("code", entry.getValue().code); value.put("reminderId", entry.getValue().reminderId);
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
        synchronized (LOCK) {
            if (frame == null) return new FrameOutcome(false, false, false, false, null, "STORAGE_FAILED");
            FrameTransaction.Result r = FrameTransaction.commit(frameAdapter(), frame.id, frame.sessionId, frame.windowId,
                    frame.captureSequence, frame.captureUptimeMs, frame.captureWallMs, friend, countdownMs, leadSeconds);
            return new FrameOutcome(r.committed, r.replay, r.confirmed, r.added, r.reminder, r.code);
        }
    }
    boolean forgetFrameReceipt(String frameId) { synchronized (LOCK) {
        return FrameTransaction.forgetAfterAck(frameAdapter(), frameId);
    } }
}
