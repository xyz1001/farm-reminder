package com.local.farmreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.android.gms.tasks.Task;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide durable capture and OCR pipeline. All repository operations run on disk. */
public final class DurableFrameEngine {
    public interface Listener {
        void onProgress(OcrProgress progress);
        void onFrameResult(DiskFrameStore.Frame frame, List<OcrLine> lines, ReminderStore.FrameOutcome outcome, long processingEpoch);
        void onStatus(String status);
        void onFatal(String status);
        void onFrameFailure(String anonymousCode, long processingEpoch);
        default void onFrameFailure(DiskFrameStore.Frame frame, String anonymousCode, long processingEpoch) {
            onFrameFailure(anonymousCode, processingEpoch);
        }
        default void onMaturityRecognized(DiskFrameStore.Frame frame, long processingEpoch) { }
    }
    private static final long RESERVE = 128L * 1024 * 1024, TIMEOUT_MS = 12000;
    private static DurableFrameEngine instance;
    public static synchronized DurableFrameEngine get(Context context) {
        if (instance == null) instance = new DurableFrameEngine(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final File root;
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final DurableFramePump pump = new DurableFramePump(disk);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DurableFrameFence fence = new DurableFrameFence();
    private final Object captureLock = new Object();
    private final Object listenerLock = new Object();
    private volatile Listener listener;
    private volatile long listenerEpoch = -1;
    private volatile OcrProgress progress = new OcrProgress(0, 0, 0, 0, false, 0, 0, 0, false, "STOPPED");
    private volatile boolean lowSpace, writeFailed, writing, storageFailed = true;
    private Bitmap unsaved;
    private DiskFrameStore.Frame unsavedMetadata;
    private long unsavedEpoch;
    private DiskFrameStore store;
    private TextRecognizer recognizer;
    private long processed, captured;
    private volatile long diagnosticAttempt;
    private DiskFrameStore.CountSnapshot snapshot;
    static final PipelineTimings timings = new PipelineTimings();

    private DurableFrameEngine(Context context) {
        this.context = context;
        root = new File(context.getNoBackupFilesDir(), "frames-v1");
        disk.execute(() -> {
            initializeStore();
        });
    }
    private static void syncDirectory(File directory) throws IOException {
        FileDescriptor fd = null;
        try {
            if (!directory.isDirectory()) throw new IOException("directory sync target is not a directory");
            fd = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            Os.fsync(fd);
        }
        catch (ErrnoException e) { throw new IOException("directory sync failed", e); }
        finally { if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { } }
    }
    public void refresh() { disk.execute(() -> { try { scan(); } catch (Exception e) { storageFailed = true; update(); } }); }
    public void exportDiagnostics(Uri destination, DiagnosticsExportCallback callback) {
        disk.execute(() -> {
            try {
                if (store == null || storageFailed) throw new IOException("本地截图存储不可用");
                DiskFrameStore.Snapshot current = store.scan();
                boolean hasUnsaved;
                synchronized (captureLock) { hasUnsaved = unsaved != null; }
                String version;
                try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
                catch (Exception e) { version = "unknown"; }
                OutputStream out = context.getContentResolver().openOutputStream(destination, "w");
                if (out == null) throw new IOException("无法打开导出文件");
                int count;
                try (OutputStream owned = out) { count = DiagnosticsExporter.export(store, current, owned, version == null ? "unknown" : version, hasUnsaved); }
                main.post(() -> callback.onSuccess(count));
            } catch (Exception e) { main.post(() -> callback.onFailure("导出失败，请检查存储位置或稍后重试")); }
        });
    }
    public boolean start(Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("start must run on main thread");
        final long token;
        int rejectedState = 0;
        synchronized (listenerLock) {
            token = fence.start();
            if (token < 0) rejectedState = fence.diagnosticState();
            else { this.listener = listener; listenerEpoch = token; }
        }
        if (token < 0) {
            LifecycleDiagnostics.engineReject(context, LifecycleDiagnostics.current(context), rejectedState);
            return false;
        }
        diagnosticAttempt = LifecycleDiagnostics.current(context);
        disk.execute(() -> {
            update();
            if (storageFailed) { fatal(token, "STORAGE_FAILED"); return; }
            if (!fence.valid(token)) return;
            try { new AlarmScheduler(context).rescheduleAll(); } catch (RuntimeException ignored) { }
            schedulePump();
        });
        return true;
    }
    public void stop() {
        synchronized (listenerLock) { fence.stop(); }
        disk.execute(this::update);
    }
    public void detach(Listener expected, long expectedEpoch) {
        synchronized (listenerLock) {
            if (listener != expected || listenerEpoch != expectedEpoch) return;
            if (fence.valid(expectedEpoch)) fence.stop();
            listener = null; listenerEpoch = -1;
        }
        disk.execute(this::update);
    }
    public boolean isRunning() { return fence.running(); }
    public boolean canCapture() { synchronized (captureLock) { return fence.running() && !lowSpace && !storageFailed && unsaved == null; } }
    public String capturePauseReason() {
        if (!fence.running()) return "STOPPED";
        if (storageFailed || writeFailed) return "WRITE_FAILED";
        if (lowSpace) return "LOW_SPACE";
        synchronized (captureLock) { return unsaved != null ? "WRITE_FAILED" : "NONE"; }
    }
    public long epoch() { return fence.epoch(); }
    public OcrProgress progress() { return progress; }

    /** Caller must only submit when canCapture() is true. Ownership transfers on acceptance. */
    public void accept(Bitmap image, DiskFrameStore.Frame metadata) {
        if (image == null || metadata == null) throw new IllegalArgumentException("missing capture");
        synchronized (captureLock) {
            if (unsaved != null) throw new IllegalStateException("capture slot occupied; ownership not transferred");
            boolean keepScreenshot = ScreenshotRetentionPolicy.enabled(context);
            unsaved = image; unsavedMetadata = copyMetadata(metadata);
            unsavedMetadata.keepScreenshot = keepScreenshot;
            unsavedEpoch = fence.epoch();
            unsavedMetadata.imageFileName = ImageEncodingPolicy.fileName(Build.VERSION.SDK_INT);
            unsavedMetadata.imageHasAlpha = image.hasAlpha(); writing = true;
        }
        timings.record(PipelineTimings.Event.ENQUEUE, metadata.captureSequence, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), 0);
        disk.execute(() -> { timings.record(PipelineTimings.Event.DISK_ARRIVAL, metadata.captureSequence, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), 0); update(); writeCapture(); });
    }
    private static DiskFrameStore.Frame copyMetadata(DiskFrameStore.Frame f) {
        DiskFrameStore.Frame c = new DiskFrameStore.Frame();
        c.sessionId = f.sessionId; c.captureMethod = f.captureMethod; c.captureSequence = f.captureSequence;
        c.captureWallMs = f.captureWallMs; c.captureUptimeMs = f.captureUptimeMs; c.windowId = f.windowId;
        c.width = f.width; c.height = f.height; c.contextCertain = f.contextCertain;
        c.imageFileName = f.imageFileName; c.imageHasAlpha = f.imageHasAlpha; c.keepScreenshot = f.keepScreenshot; return c;
    }
    private void writeCapture() {
        Bitmap bitmap; DiskFrameStore.Frame frame; long token;
        synchronized (captureLock) { bitmap = unsaved; frame = unsavedMetadata; token = unsavedEpoch; }
        if (bitmap == null) return;
        try {
            if (store == null) throw new IOException("store unavailable");
            if (!store.reconcilePublication(frame)) store.publish(frame, out -> bitmap.compress(
                    Build.VERSION.SDK_INT >= 30 && "image.webp".equals(frame.imageFileName) ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.PNG,
                    ImageEncodingPolicy.quality(Build.VERSION.SDK_INT), out));
            timings.record(PipelineTimings.Event.PUBLISH_COMPLETE, frame.captureSequence, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), 0);
            synchronized (captureLock) { unsaved = null; unsavedMetadata = null; writing = false; writeFailed = false; }
            bitmap.recycle(); captured++;
        } catch (Exception e) { writing = false; writeFailed = true; notifyFrameFailure(frame, "WRITE_FAILED", token); }
        try { scan(); } catch (Exception e) { storageFailed = true; update(); }
        schedulePump();
    }
    public void retryFailed() {
        disk.execute(() -> {
            try {
                if (!fence.canRecover()) { status("RETRY_BUSY"); return; }
                if (storageFailed) { status("STORAGE_FAILED"); return; }
                if (store == null) throw new IOException("store unavailable");
                synchronized (captureLock) { writing = unsaved != null; }
                if (writing) { update(); writeCapture(); }
                if (writeFailed) return;
                store.retryFailed();
                scan();
                if (fence.running()) schedulePump();
            } catch (Exception e) { storageFailed = true; update(); }
        });
    }
    private void initializeStore() {
        try {
            store = new DiskFrameStore(root, DurableFrameEngine::syncDirectory, DiskFrameStore.DEFAULT_OPS);
            storageFailed = false;
            scan();
        } catch (Exception e) { storageFailed = true; update(); }
    }
    private void status(String value) { main.post(() -> { Listener l = listener; if (l != null) l.onStatus(value); }); }
    private void scan() throws IOException {
        snapshot = store.counts();
        try { lowSpace = new StatFs(root.getAbsolutePath()).getAvailableBytes() < RESERVE; }
        catch (RuntimeException e) { lowSpace = true; }
        update();
    }
    private void update() {
        DiskFrameStore.CountSnapshot s = snapshot;
        int failed = s == null ? 0 : s.failedArtifacts + s.failedFrames;
        boolean hasUnsavedCapture, captureWriting;
        synchronized (captureLock) { hasUnsavedCapture = unsaved != null; captureWriting = writing; }
        OcrProgress p = new OcrProgress(processed, captured, s == null ? 0 : s.pending, s == null ? 0 : s.pendingBytes,
                fence.modelActive(), failed, s == null ? 0 : s.cleanupPending, s == null ? 0 : s.retainedBytes, captureWriting, capturePauseReason(),
                hasUnsavedCapture, s == null ? 0 : s.archivedCount);
        progress = p;
        Listener expected = listener;
        long expectedEpoch = listenerEpoch;
        main.post(() -> { if (expected != null && expected == listener && listenerEpoch == expectedEpoch && fence.valid(expectedEpoch) && progress == p) expected.onProgress(p); });
    }
    /** Disk-confined, coalesced pump: no recursive failure chain. */
    private void schedulePump() {
        pump.request(() -> drain(fence.epoch()));
    }
    private void drain(long token) {
        if (store == null || storageFailed || writeFailed || !fence.beginFrame(token)) return;
        DiskFrameStore.Frame frame;
        try {
            if (!fence.valid(token)) { finishFrame(); return; }
            frame = store.claimNext();
            if (frame == null) { finishFrame(); return; }
            if (!fence.valid(token)) { returnPending(frame); finishFrame(); return; }
        } catch (Exception e) { storageError(token); finishFrame(); return; }
        final Bitmap bitmap = decode(store.image(frame), frame.imageHasAlpha);
        if (bitmap == null) { fail(frame, "DECODE_FAILED", token, false); finishFrame(); return; }
        if (!fence.beginModel(token)) { bitmap.recycle(); returnPending(frame); finishFrame(); return; }
        final long attempt = diagnosticAttempt;
        final long modelAttempt = fence.modelAttempt();
        timings.record(PipelineTimings.Event.NATIVE_FRAME, modelAttempt, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), frame.enqueueSequence);
        timings.record(PipelineTimings.Event.NATIVE_START, modelAttempt, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), 0);
        final Task<Text> task;
        try {
            if (recognizer == null) recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            task = recognizer.process(InputImage.fromBitmap(bitmap, 0));
            timings.record(PipelineTimings.Event.NATIVE_RETURN, modelAttempt, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), 0);
        } catch (Exception e) {
            fence.completeModel(modelAttempt); bitmap.recycle(); fail(frame, "OCR_FAILED", token, false); finishFrame(); return;
        }
        Runnable watchdog = () -> {
            timings.record(PipelineTimings.Event.NATIVE_WATCHDOG, modelAttempt, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), task.isComplete() ? 1 : 0);
            if (fence.timeout(token, modelAttempt)) {
                LifecycleDiagnostics.event(context, attempt, LifecycleHistory.Event.OCR_WATCHDOG, LifecycleHistory.Reason.OCR_TIMEOUT, null);
                updateOnDisk();
            }
        };
        main.postDelayed(watchdog, TIMEOUT_MS);
        // Direct executor marks native completion immediately, independent of disk backlog.
        // The frame barrier remains held until the disk-side transition is finished.
        task.addOnCompleteListener(Runnable::run, completed -> {
            int completion = fence.completeModel(modelAttempt);
            if (completion < 0) return;
            timings.record(PipelineTimings.Event.NATIVE_CALLBACK, modelAttempt, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), completion);
            main.removeCallbacks(watchdog);
            bitmap.recycle();
            disk.execute(() -> {
                processed++;
                try {
                    DurableFrameFence.CompletionAction action = DurableFrameFence.completionAction(
                            fence.valid(token), completion, completed.isSuccessful() && completed.getResult() != null);
                    switch (action) {
                        case RETURN_PENDING: returnPending(frame); break;
                        case TIMEOUT: fail(frame, "OCR_TIMEOUT", token, false); break;
                        case OCR_FAILED: fail(frame, "OCR_FAILED", token, false); break;
                        case PROCESS: processResult(frame, lines(completed.getResult(), false), lines(completed.getResult(), true), token); break;
                    }
                } catch (RuntimeException e) { fail(frame, "OCR_FAILED", token, false); }
                finally { finishFrame(); }
            });
        });
        update();
    }
    private static Bitmap decode(File file, boolean hasAlpha) {
        try { Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath()); if (bitmap != null) bitmap.setHasAlpha(hasAlpha); return bitmap; }
        catch (RuntimeException e) { return null; }
    }
    private static List<OcrLine> lines(Text text, boolean includeUnlocated) {
        List<OcrLine> result = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            Rect r = line.getBoundingBox();
            if (r != null) result.add(new OcrLine(line.getText(), r.left, r.top, r.right, r.bottom));
            else if (includeUnlocated) result.add(new OcrLine(line.getText(), -1, -1, -1, -1));
        }
        return Collections.unmodifiableList(result);
    }
    private void processResult(DiskFrameStore.Frame frame, List<OcrLine> lines, List<OcrLine> rawLines, long token) {
        if (!fence.valid(token)) { returnPending(frame); return; }
        ReminderStore reminders = new ReminderStore(context);
        boolean admitted = false;
        try {
            store.writeDiagnostic(frame, "ocr-lines.tsv", DiagnosticCodec.lines(rawLines));
            List<Long> countdown = OcrFrameParser.countdowns(lines);
            store.writeDiagnostic(frame, "result.properties", DiagnosticCodec.result(frame, "好友", countdown, null));
            int lead = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("leadSeconds", 60);
            List<Long> businessCountdown = FrameBusinessAdmission.countdowns(frame.captureMethod, countdown);
            boolean mayAdd = !businessCountdown.isEmpty() && reminders.mayAddFrame(frame, businessCountdown);
            String avatar = mayAdd ? AvatarStore.saveFromFrame(context, store.image(frame), lines, frame.id) : "";
            String plate = mayAdd ? NameplateStore.saveFromFrame(context, store.image(frame), lines, frame.id) : "";
            admitted = fence.tryAdmitTransaction(token);
            if (!admitted) { returnPending(frame); return; }
            ReminderStore.FrameOutcome outcome = reminders.commitFrame(frame, businessCountdown, lead, avatar, plate);
            store.writeDiagnostic(frame, "result.properties", DiagnosticCodec.result(frame, "好友", countdown, outcome));
            if (!outcome.committed) { fail(frame, "BUSINESS_FAILED", token, true); return; }
            try { new AlarmScheduler(context).rescheduleAll(); } catch (RuntimeException ignored) { }
            store.acknowledge(frame);
            reminders.forgetFrameReceipt(frame.id);
            store.discardFinalized(frame);
            main.post(() -> { if (fence.valid(token)) { Listener l = listener; if (l != null) l.onFrameResult(frame, lines, outcome, token); } });
        } catch (Exception e) {
            if (frame.state == DiskFrameStore.State.PROCESSING) fail(frame, "STORAGE_FAILED", token, admitted);
            else storageError(token);
        } finally { if (admitted) fence.finishTransaction(); }
    }
    private void fail(DiskFrameStore.Frame frame, String code, long token, boolean admitted) {
        try {
            if (!admitted && !fence.valid(token)) { store.returnPending(frame); return; }
            store.fail(frame, code);
            if ("OCR_FAILED".equals(code) || "OCR_TIMEOUT".equals(code) || "DECODE_FAILED".equals(code))
                store.discardFinalized(frame);
            notifyFrameFailure(frame, code, token);
        } catch (Exception e) { storageError(token); }
    }
    private void notifyFrameFailure(DiskFrameStore.Frame frame, String code, long token) {
        Listener expected = listener;
        main.post(() -> {
            if (expected != null && listener == expected && listenerEpoch == token && fence.shouldNotifyFailure(token))
                expected.onFrameFailure(frame, code, token);
        });
    }
    private void returnPending(DiskFrameStore.Frame frame) {
        try { store.returnPending(frame); }
        catch (Exception e) { storageError(fence.epoch()); }
    }
    private void finishFrame() {
        try { scan(); } catch (Exception e) { storageError(fence.epoch()); }
        fence.finishFrame();
        update();
        if (fence.running() && !storageFailed && snapshot != null && snapshot.pending > 0) schedulePump();
    }
    private void storageError(long token) { storageFailed = true; update(); fatal(token, "STORAGE_FAILED"); }
    private void fatal(long token, String status) {
        long attempt = diagnosticAttempt;
        main.post(() -> { if (fence.valid(token)) { fence.stop(); LifecycleDiagnostics.event(context, attempt, LifecycleHistory.Event.STORAGE_FATAL, LifecycleHistory.Reason.STORAGE_FAILED, null); Listener l = listener; if (l != null) l.onFatal(status); updateOnDisk(); } });
    }
    private void updateOnDisk() { disk.execute(this::update); }
}
