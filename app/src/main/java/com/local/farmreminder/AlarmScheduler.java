package com.local.farmreminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AlarmScheduler {
    private static final AlarmPlan PLAN = new AlarmPlan();
    private final Context context;
    private final AlarmManager alarms;
    public AlarmScheduler(Context context) { this.context = context.getApplicationContext(); alarms = (AlarmManager) this.context.getSystemService(Context.ALARM_SERVICE); }
    public boolean canScheduleExact() { return Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(); }
    private PendingIntent intent(String kind, String id) {
        return intent(kind, id, null);
    }
    private PendingIntent intent(String kind, String id, AlarmTimingDiagnostics.Sample sample) {
        Intent i = new Intent(context, AlarmReceiver.class).setData(new Uri.Builder().scheme("farmreminder").authority(kind).appendPath(id).build()).putExtra("id", id);
        if (sample != null) AlarmTimingDiagnostics.put(i, sample);
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    void cancel(String id) { cancel("alarm", id); }
    private void cancel(String kind, String id) { PendingIntent p = intent(kind, id); alarms.cancel(p); p.cancel(); }
    private void clearFailedSample(String kind, String id, AlarmTimingDiagnostics.Sample sample) {
        RuntimeException resetFailure = AlarmDiagnosticReset.afterFailure(true, sample.token != 0, () -> intent(kind, id, null));
        if (resetFailure != null) AlarmTimingDiagnostics.record(context, sample, AlarmTimingHistory.Event.SCHEDULE,
                AlarmTimingHistory.Mode.RESET_FAILED, -1, 0, -1, resetFailure, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime());
    }
    private boolean scheduleAt(String kind, String id, long time) {
        AlarmTimingDiagnostics.Sample sample = AlarmTimingDiagnostics.prepare(context, time);
        PendingIntent p;
        try { p = intent(kind, id, sample); }
        catch (RuntimeException e) {
            AlarmTimingDiagnostics.record(context, sample, AlarmTimingHistory.Event.SCHEDULE, AlarmTimingHistory.Mode.FAILED,
                    -1, 0, -1, e, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime());
            clearFailedSample(kind, id, sample);
            throw e;
        }
        AlarmTimingHistory.Mode mode = AlarmTimingHistory.Mode.FAILED;
        int exact = -1; Throwable failure = null;
        try {
            boolean allowed = canScheduleExact(); exact = allowed ? 1 : 0;
            if (allowed) { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); mode = AlarmTimingHistory.Mode.EXACT; }
            else { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); mode = AlarmTimingHistory.Mode.INEXACT; }
            return true;
        } catch (SecurityException e) {
            failure = e;
            try { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); mode = AlarmTimingHistory.Mode.SECURITY_FALLBACK; return true; }
            catch (RuntimeException fallback) { failure = fallback; return false; }
        } catch (RuntimeException e) { failure = e; return false; }
        finally {
            AlarmTimingDiagnostics.record(context, sample, AlarmTimingHistory.Event.SCHEDULE, mode, exact,
                    mode == AlarmTimingHistory.Mode.FAILED ? 0 : 1, -1, failure, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime());
            if (mode == AlarmTimingHistory.Mode.FAILED) clearFailedSample(kind, id, sample);
        }
    }
    static void delivered(String id) { synchronized (ReminderStore.LOCK) { PLAN.delivered(id); } }
    public void rescheduleAll() { rescheduleAll(false); }
    void rescheduleAfterExactGrant() { if (canScheduleExact()) rescheduleAll(true); }
    private void rescheduleAll(boolean forceGrant) { synchronized (ReminderStore.LOCK) {
        ReminderStore store = new ReminderStore(context);
        if (store.pruneExpired(System.currentTimeMillis()) < 0) return;
        List<Reminder> items;
        try { items = store.readStrict(); } catch (Exception e) { return; }
        PLAN.capability(canScheduleExact(), forceGrant);
        List<ReminderGrouping.Group> groups = ReminderGrouping.plan(items);
        SharedPreferences prefs = context.getSharedPreferences("group_alarms", Context.MODE_PRIVATE);
        for (Reminder r : items) cancel(r.id);
        PLAN.reconcile(groups, new AlarmPlan.Backend() {
            public Set<String> recorded() { return new HashSet<>(prefs.getStringSet("ids", java.util.Collections.emptySet())); }
            public boolean record(Set<String> ids) { return prefs.edit().putStringSet("ids", ids).commit(); }
            public void cancel(String id) { AlarmScheduler.this.cancel("group", id); }
            public boolean schedule(String id, long at) { return scheduleAt("group", id, at); }
        });
    } }
    public boolean scheduleTest(int seconds) { return scheduleAt("alarm", "test", System.currentTimeMillis() + Math.max(1, seconds) * 1000L); }
}
