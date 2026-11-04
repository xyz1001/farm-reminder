package com.local.farmreminder;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debug-only, private, bounded and coalesced. A last event can be lost on process death. */
final class LifecycleDiagnostics {
    private static LifecycleDiagnostics instance;
    private final LifecycleHistory history;
    private final AtomicFile file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final LifecycleWriteGate gate = new LifecycleWriteGate();
    private long current;
    private LifecycleDiagnostics(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "lifecycle-v1.txt"));
        String previous = null;
        try { if (file.getBaseFile().length() <= 32768) { byte[] bytes = file.readFully(); if (bytes.length <= 32768) previous = new String(bytes, StandardCharsets.UTF_8); } }
        catch (Exception ignored) { }
        history = new LifecycleHistory(previous, new java.security.SecureRandom().nextLong() & Long.MAX_VALUE);
    }
    private static synchronized LifecycleDiagnostics get(Context c) {
        if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
        if (instance == null) instance = new LifecycleDiagnostics(c.getApplicationContext());
        return instance;
    }
    static long click(Context c) { LifecycleDiagnostics d = get(c); return d == null ? 0 : d.history.nextAttempt(); }
    static void setCurrent(Context c, long id) { LifecycleDiagnostics d = get(c); if (d != null) synchronized (d) { d.current = id; } }
    static long current(Context c) { LifecycleDiagnostics d = get(c); if (d == null) return 0; synchronized (d) { return d.current; } }
    static long generation(Context c) { LifecycleDiagnostics d = get(c); return d == null ? 0 : d.history.generation(); }
    static void event(Context c, long id, LifecycleHistory.Event e, LifecycleHistory.Reason r, Throwable t) {
        LifecycleDiagnostics d = get(c); if (d == null) return;
        synchronized (d) {
            d.history.add(id, SystemClock.elapsedRealtime(), System.currentTimeMillis(), e, r,
                    FarmAccessibilityService.isConnected, FarmAccessibilityService.isRunning, MonitorForegroundService.isActive, false, t);
            if (d.gate.offer(d.history.serialize())) d.writer.execute(d::flush);
        }
        Log.i("FarmLifecycle", "attempt=" + id + " event=" + e + " reason=" + r);
    }
    static void captureReleased(Context c, long id, boolean timedOut, boolean released) {
        LifecycleHistory.Event event = LifecycleHistory.captureReleaseEvent(timedOut, released);
        if (event != null) event(c, id, event, LifecycleHistory.Reason.WATCHDOG, null);
    }
    static void engineReject(Context c, long id, int state) {
        LifecycleDiagnostics d = get(c); if (d == null) return;
        synchronized (d) {
            d.history.add(id, SystemClock.elapsedRealtime(), System.currentTimeMillis(), LifecycleHistory.Event.ENGINE_REJECT,
                    LifecycleHistory.Reason.ENGINE_BUSY, (state & 1) != 0, (state & 2) != 0, (state & 4) != 0, (state & 8) != 0, null);
            if (d.gate.offer(d.history.serialize())) d.writer.execute(d::flush);
        }
        Log.i("FarmLifecycle", "attempt=" + id + " event=ENGINE_REJECT reason=ENGINE_BUSY state=" + state);
    }
    private void flush() {
        while (true) {
            String snapshot;
            snapshot = gate.next();
            if (snapshot == null) return;
            FileOutputStream out = null;
            try { out = file.startWrite(); out.write(snapshot.getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
            catch (Exception ignored) { if (out != null) file.failWrite(out); }
        }
    }
    static String snapshot(Context c) { LifecycleDiagnostics d = get(c); return d == null ? "" : d.history.outcome(SystemClock.elapsedRealtime()) + "\n" + d.history.serialize(); }
}
