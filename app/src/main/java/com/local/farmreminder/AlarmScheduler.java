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
        Intent i = new Intent(context, AlarmReceiver.class).setData(new Uri.Builder().scheme("farmreminder").authority(kind).appendPath(id).build()).putExtra("id", id);
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    void cancel(String id) { cancel("alarm", id); }
    private void cancel(String kind, String id) { PendingIntent p = intent(kind, id); alarms.cancel(p); p.cancel(); }
    private boolean scheduleAt(String kind, String id, long time) {
        PendingIntent p = intent(kind, id);
        try { if (canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); return true; }
        catch (SecurityException e) { try { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); return true; } catch (RuntimeException ignored) { return false; } }
        catch (RuntimeException e) { return false; }
    }
    static void delivered(String id) { synchronized (ReminderStore.LOCK) { PLAN.delivered(id); } }
    public void rescheduleAll() { synchronized (ReminderStore.LOCK) {
        ReminderStore store = new ReminderStore(context);
        if (!store.ready()) return;
        List<Reminder> items = store.list();
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
    public void scheduleTest(int seconds) { scheduleAt("alarm", "test", System.currentTimeMillis() + Math.max(1, seconds) * 1000L); }
}
