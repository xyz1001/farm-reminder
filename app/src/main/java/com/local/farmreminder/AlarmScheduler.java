package com.local.farmreminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

public class AlarmScheduler {
    private final Context context;
    private final AlarmManager alarms;
    public AlarmScheduler(Context context) { this.context = context.getApplicationContext(); alarms = (AlarmManager) this.context.getSystemService(Context.ALARM_SERVICE); }
    public boolean canScheduleExact() { return Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(); }
    private PendingIntent intent(String id) {
        Intent i = new Intent(context, AlarmReceiver.class).setData(Uri.parse("farmreminder://alarm/" + id)).putExtra("id", id);
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    void cancel(String id) { PendingIntent p = intent(id); alarms.cancel(p); p.cancel(); }
    void schedule(Reminder r) { long time = Reminder.scheduledTime(r, System.currentTimeMillis()); if (time != 0) scheduleAt(r.id, time); }
    private void scheduleAt(String id, long time) {
        PendingIntent p = intent(id);
        try { if (canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); }
        catch (SecurityException e) { try { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p); } catch (SecurityException ignored) { } }
    }
    public void rescheduleAll() { for (Reminder r : new ReminderStore(context).list()) schedule(r); }
    public void scheduleTest(int seconds) { scheduleAt("test", System.currentTimeMillis() + Math.max(1, seconds) * 1000L); }
}
