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
    private final Context context;
    private final SharedPreferences prefs;
    public ReminderStore(Context context) { this.context = context.getApplicationContext(); prefs = this.context.getSharedPreferences("reminders", Context.MODE_PRIVATE); }
    public List<Reminder> list() { synchronized (LOCK) { return read(); } }
    private List<Reminder> read() {
        List<Reminder> result = new ArrayList<>();
        try { readInto(result); } catch (Exception ignored) { }
        return result;
    }
    private void readInto(List<Reminder> result) throws Exception {
        JSONArray a = new JSONArray(prefs.getString("items", "[]"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i); Reminder r = new Reminder();
            r.id = o.getString("id"); r.friend = o.optString("friend", ""); r.crop = o.optString("crop", "");
            r.matureAt = o.getLong("matureAt"); r.triggerAt = o.getLong("triggerAt"); r.fired = o.optBoolean("fired"); result.add(r);
        }
    }
    private boolean write(List<Reminder> items) {
        JSONArray a = new JSONArray();
        for (Reminder r : items) try {
            JSONObject o = new JSONObject(); o.put("id", r.id); o.put("friend", r.friend); o.put("crop", r.crop);
            o.put("matureAt", r.matureAt); o.put("triggerAt", r.triggerAt); o.put("fired", r.fired); a.put(o);
        } catch (Exception ignored) { return false; }
        return prefs.edit().putString("items", a.toString()).commit();
    }
    public void delete(String id) { synchronized (LOCK) { List<Reminder> items = read(); items.removeIf(r -> r.id.equals(id)); write(items); new AlarmScheduler(context).cancel(id); } }
    public void clearFired() { synchronized (LOCK) { List<Reminder> items = read(); for (Reminder r : items) if (r.fired) new AlarmScheduler(context).cancel(r.id); items.removeIf(r -> r.fired); write(items); } }
    static final class AddResult {
        final SaveOutcome outcome; final Reminder reminder;
        AddResult(SaveOutcome outcome, Reminder reminder) { this.outcome = outcome; this.reminder = reminder; }
    }
    AddResult addIfNew(String friend, long matureAt, int leadSeconds) {
        synchronized (LOCK) {
            List<Reminder> items = new ArrayList<>();
            try { readInto(items); } catch (Exception e) { return new AddResult(SaveOutcome.FAILED, null); }
            if (RecognitionLogic.isDuplicate(items, friend, matureAt)) return new AddResult(SaveOutcome.DUPLICATE, null);
            Reminder r = new Reminder(); r.id = UUID.randomUUID().toString(); r.friend = friend; r.crop = ""; r.matureAt = matureAt;
            r.triggerAt = matureAt - Math.max(0, leadSeconds) * 1000L; r.fired = false; items.add(r);
            try {
                SaveOutcome outcome = SaveOutcome.fromCommit(write(items));
                return new AddResult(outcome, outcome == SaveOutcome.ADDED ? r : null);
            } catch (RuntimeException e) { return new AddResult(SaveOutcome.FAILED, null); }
        }
    }
    Reminder markFired(String id) { synchronized (LOCK) { List<Reminder> items = read(); for (Reminder r : items) if (r.id.equals(id) && !r.fired) { r.fired = true; write(items); return r; } return null; } }
}
