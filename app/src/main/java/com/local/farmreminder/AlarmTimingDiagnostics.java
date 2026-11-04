package com.local.farmreminder;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** All file access, including initial history loading, is confined to the writer thread. */
final class AlarmTimingDiagnostics {
    private static final String TOKEN = "diag_alarm_token", REQUEST = "diag_alarm_request", WALL = "diag_alarm_wall", ELAPSED = "diag_alarm_elapsed";
    private static AlarmTimingDiagnostics instance;
    private final Context context;
    private final AlarmTimingHistory history = new AlarmTimingHistory();
    private final LifecycleWriteGate gate = new LifecycleWriteGate();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private AtomicFile file;
    private boolean initialized;
    private AlarmTimingDiagnostics(Context context) { this.context = context.getApplicationContext(); }
    private static synchronized AlarmTimingDiagnostics get(Context context) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
        if (instance == null) instance = new AlarmTimingDiagnostics(context);
        return instance;
    }
    static final class Sample {
        final long token, requested, wall, elapsed;
        Sample(long token, long requested, long wall, long elapsed) { this.token = token; this.requested = requested; this.wall = wall; this.elapsed = elapsed; }
    }
    static Sample prepare(Context context, long requested) {
        try { if (get(context) != null) return new Sample(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), requested, System.currentTimeMillis(), SystemClock.elapsedRealtime()); }
        catch (RuntimeException ignored) { }
        return new Sample(0, 0, 0, 0);
    }
    static void put(Intent intent, Sample s) {
        try { if (s.token != 0) intent.putExtra(TOKEN, s.token).putExtra(REQUEST, s.requested).putExtra(WALL, s.wall).putExtra(ELAPSED, s.elapsed); }
        catch (RuntimeException ignored) { }
    }
    static Sample from(Intent intent) {
        try {
            long token = AlarmTimingHistory.validToken(intent.getLongExtra(TOKEN, 0));
            if (token != 0) {
                long requested = intent.getLongExtra(REQUEST, 0), wall = intent.getLongExtra(WALL, 0), elapsed = intent.getLongExtra(ELAPSED, -1);
                if (requested > 0 && wall > 0 && elapsed >= 0) return new Sample(token, requested, wall, elapsed);
            }
        } catch (RuntimeException ignored) { }
        return new Sample(0, 0, 0, 0);
    }
    static void record(Context context, Sample sample, AlarmTimingHistory.Event event, AlarmTimingHistory.Mode mode,
                       int exact, int success, long duration, Throwable error, long wall, long elapsed) {
        try {
            AlarmTimingDiagnostics d = get(context); if (d == null) return;
            synchronized (d) {
                d.history.add(sample.token, sample.requested, sample.wall, sample.elapsed, wall, elapsed, event, mode, exact, success, duration, error);
                if (d.gate.offer(d.history.serialize())) d.writer.execute(d::flush);
            }
            Log.i("FarmAlarmTiming", "event=" + event + " mode=" + mode + " success=" + success);
        } catch (RuntimeException ignored) { }
    }
    private void flush() {
        if (!initialized) {
            String previous = null;
            try {
                file = new AtomicFile(new File(context.getNoBackupFilesDir(), "alarm-timing-v1.txt"));
                if (file.getBaseFile().length() <= 32768) previous = new String(file.readFully(), StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
            synchronized (this) {
                history.prepend(previous);
                while (gate.next() != null) { }
                gate.offer(history.serialize());
                initialized = true;
            }
        }
        String snapshot;
        while ((snapshot = gate.next()) != null) {
            if (file == null) continue;
            FileOutputStream out = null;
            try { out = file.startWrite(); out.write(snapshot.getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
            catch (Exception ignored) { if (out != null) file.failWrite(out); }
        }
    }
}
