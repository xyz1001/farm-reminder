package com.local.farmreminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AlarmReceiver extends BroadcastReceiver {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "farm-alarm"));
    static { WORKER.allowCoreThreadTimeOut(true); }

    private static final class Prepared {
        final NotificationIdentityBinding.Snapshot snapshot;
        final Bitmap bitmap;
        final NotificationIdentityLabel label;
        Prepared(NotificationIdentityBinding.Snapshot snapshot, Bitmap bitmap, NotificationIdentityLabel label) {
            this.snapshot = snapshot; this.bitmap = bitmap; this.label = label;
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        final long entryWall = System.currentTimeMillis(), entryElapsed = SystemClock.elapsedRealtime();
        AlarmTimingDiagnostics.Sample timing = AlarmTimingDiagnostics.from(intent);
        AlarmTimingDiagnostics.record(context, timing, AlarmTimingHistory.Event.RECEIVE, AlarmTimingHistory.Mode.UNKNOWN,
                -1, -1, -1, null, entryWall, entryElapsed);
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"farmreminder".equals(data.getScheme()) || data.getPathSegments().size() != 1) return;
        String id = data.getLastPathSegment(), kind = data.getAuthority();
        if (id == null || !("alarm".equals(kind) || "group".equals(kind))) return;
        PendingResult pending = goAsync();
        AlarmDeliveryWork.submit(WORKER, SystemClock::elapsedRealtime, entryElapsed,
                new AlarmDeliveryWork.Work<Prepared>() {
                    @Override public Prepared prepare() {
                        if (!"group".equals(kind)) return null;
                        ReminderStore store = new ReminderStore(context);
                        return AlarmDeliveryWork.prepareSnapshot(ReminderStore.LOCK, () -> {
                            if (!store.ready()) return null;
                            for (ReminderGrouping.Group group : ReminderGrouping.plan(store.list())) {
                                if (group.identity.equals(id) && group.due(System.currentTimeMillis()))
                                    return NotificationIdentityBinding.select(group.identity, group.reminders);
                            }
                            return null;
                        }, snapshot -> {
                            Bitmap bitmap = snapshot.avatar.isEmpty() ? null : AvatarStore.load(context, snapshot.avatar, 65536);
                            NotificationIdentityLabel label = NotificationIdentityLabel.EMPTY;
                            if (!snapshot.source.isEmpty() && SystemClock.elapsedRealtime() < entryElapsed + 2250) {
                                try { label = NotificationIdentityOcr.read(context, snapshot.source, entryElapsed + 2250); }
                                catch (RuntimeException | OutOfMemoryError ignored) { }
                            }
                            return new Prepared(snapshot, bitmap, label == null ? NotificationIdentityLabel.EMPTY : label);
                        },
                                SystemClock::elapsedRealtime, entryElapsed);
                    }
                    @Override public Prepared budgetExpired(Prepared prepared) {
                        return prepared == null ? null : new Prepared(prepared.snapshot, prepared.bitmap, NotificationIdentityLabel.EMPTY);
                    }
                    @Override public void deliver(Prepared prepared) {
                        dispatch(context, kind, id, timing, prepared);
                    }
                }, pending::finish);
    }

    private static void dispatch(Context context, String kind, String id,
                                 AlarmTimingDiagnostics.Sample timing, Prepared prepared) {
        if ("alarm".equals(kind)) {
            if ("test".equals(id)) { try { notifyMeasured(context, id, null, null, NotificationIdentityLabel.EMPTY, timing); } catch (RuntimeException ignored) { } }
            else new AlarmScheduler(context).rescheduleAll(); // Legacy broadcasts never dispatch individually.
            return;
        }
        ReminderStore store = new ReminderStore(context);
        synchronized (ReminderStore.LOCK) {
            AlarmScheduler.delivered(id);
            AlarmDispatchPolicy.dispatch(ReminderStore.LOCK, new AlarmDispatchPolicy.Adapter() {
                public boolean ready() { return store.ready(); }
                public List<Reminder> list() { return store.list(); }
                public boolean notifyGroup(ReminderGrouping.Group group) {
                    NotificationIdentityBinding.Snapshot current = NotificationIdentityBinding.select(group.identity, group.reminders);
                    Bitmap bitmap = prepared != null && NotificationIdentityBinding.sameAvatar(prepared.snapshot, current) ? prepared.bitmap : null;
                    NotificationIdentityLabel label = prepared != null && NotificationIdentityBinding.sameSource(prepared.snapshot, current)
                            ? prepared.label : NotificationIdentityLabel.EMPTY;
                    return notifyMeasured(context, group.identity, group.reminders, bitmap, label, timing);
                }
                public boolean markFired(List<Reminder> reminders) { return store.markGroupFired(reminders); }
                public void reconcile() { new AlarmScheduler(context).rescheduleAll(); }
            }, id, System.currentTimeMillis());
        }
    }

    private static boolean notifyMeasured(Context context, String id, List<Reminder> reminders, Bitmap avatar, NotificationIdentityLabel label,
                                          AlarmTimingDiagnostics.Sample timing) {
        long started = SystemClock.elapsedRealtime();
        boolean success = false; RuntimeException failure = null;
        try { success = ReminderNotifier.post(context, id, reminders, avatar, label); return success; }
        catch (RuntimeException e) { failure = e; throw e; }
        finally {
            long elapsed = SystemClock.elapsedRealtime();
            AlarmTimingDiagnostics.record(context, timing, AlarmTimingHistory.Event.NOTIFY, AlarmTimingHistory.Mode.UNKNOWN,
                    -1, success ? 1 : 0, Math.max(0, elapsed - started), failure, System.currentTimeMillis(), elapsed);
        }
    }
}
