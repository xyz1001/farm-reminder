package com.local.farmreminder;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only baseline: original store is read-only; publication is isolated. */
final class PipelineProfileProbe {
    private final Instrumentation instrumentation;
    private final StringBuffer output = new StringBuffer();
    private final long deadline = SystemClock.elapsedRealtime() + 60_000;
    private volatile int mutationAttempts;
    private volatile boolean nativePending;
    private volatile boolean workerFinished;
    private volatile String stage = "starting";
    private File runDir;

    private PipelineProfileProbe(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    static void run(Instrumentation instrumentation) {
        PipelineProfileProbe probe = new PipelineProfileProbe(instrumentation);
        int code = 0;
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pipeline-profile");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> future = executor.submit(() -> {
            try { probe.profile(); }
            finally { probe.workerFinished = true; }
            return null;
        });
        try { future.get(Math.max(1, probe.deadline - now()), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) {
            code = 1;
            probe.output.append("controllerTimeout=true privateCleanupSafe=false\n");
        } catch (ExecutionException e) {
            code = 1;
            Throwable cause = e.getCause();
            probe.output.append("errorClass=").append(cause == null ? "unknown" : cause.getClass().getSimpleName()).append('\n');
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
        } catch (InterruptedException e) {
            code = 1;
            probe.output.append("controllerInterrupted=true privateCleanupSafe=false\n");
            Thread.currentThread().interrupt();
        } finally {
            // Never interrupt a worker that might be inside native OCR or filesystem I/O.
            executor.shutdown();
        }
        probe.output.append("rootReadonlyMutationAttempts=").append(probe.mutationAttempts)
                .append(" nativePending=").append(probe.nativePending)
                .append(" workerFinished=").append(probe.workerFinished).append(" stage=").append(probe.stage).append('\n');
        if (probe.mutationAttempts != 0 || probe.nativePending) code = 1;
        Bundle result = new Bundle();
        result.putString("output", probe.output.toString());
        instrumentation.finish(code, result);
    }

    private void profile() throws Exception {
        output.append("mode=pipeline-profile assets=profile-01..04.webp rounds=2 maxTasks=8 taskAwaitMaxMs=12000 overallBudgetMs=60000\n")
                .append("limits=controller_deadline_no_worker_interrupt; encode_excludes_flush_and_fd_fsync; no_live_screenshot_or_executor_queue; timeout_nonreproduction_not_fix_proof\n");
        stage = "original-constructor";
        File noBackup = instrumentation.getTargetContext().getNoBackupFilesDir().getCanonicalFile();
        File root = new File(noBackup, "frames-v1").getCanonicalFile();
        if (!root.getParentFile().equals(noBackup) || !root.isDirectory()) throw new IOException("original root unavailable");
        DiskFrameStore.FileOps guard = new DiskFrameStore.FileOps() {
            @Override public void move(File a, File b) throws IOException { mutationAttempts++; throw new IOException("readonly"); }
            @Override public void replace(File a, File b) throws IOException { mutationAttempts++; throw new IOException("readonly"); }
            @Override public boolean delete(File path) { mutationAttempts++; throw new IllegalStateException("readonly"); }
        };
        long start = now();
        DiskFrameStore original = new DiskFrameStore(root, dir -> { mutationAttempts++; throw new IOException("readonly"); }, guard,
                image -> { throw new IOException("unexpected image read"); });
        output.append("constructorElapsedMs=").append(now() - start).append('\n');
        for (int i = 0; i < 3; i++) {
            stage = "original-scan-" + (i + 1);
            checkBudget();
            start = now();
            DiskFrameStore.Snapshot s = original.scan();
            output.append("explicitScan=").append(i + 1)
                    .append(" elapsedMs=").append(now() - start).append(" frames=").append(s.frames.size())
                    .append(" pending=").append(s.pending).append(" failedArtifacts=").append(s.failedArtifacts)
                    .append(" cleanupPending=").append(s.cleanupPending).append(" archived=").append(s.archivedCount)
                    .append(" retainedBytes=").append(s.retainedBytes).append(" pendingBytes=").append(s.pendingBytes).append('\n');
            if (i == 0) output.append("originalRootFrameCount=").append(s.frames.size()).append('\n');
        }
        checkBudget();
        stage = "original-hot-counts";
        long countsStartNs = SystemClock.elapsedRealtimeNanos();
        DiskFrameStore.CountSnapshot counts = null;
        for (int i = 0; i < 10_000; i++) counts = original.counts();
        output.append("processLocalCountsCalls=10000 elapsedNs=").append(SystemClock.elapsedRealtimeNanos() - countsStartNs)
                .append(" pending=").append(counts.pending).append(" archived=").append(counts.archivedCount)
                .append(" retainedBytes=").append(counts.retainedBytes).append('\n');
        checkBudget();
        runDir = new File(instrumentation.getTargetContext().getFilesDir(), "pipeline-profile/" + UUID.randomUUID());
        File privateRoot = new File(runDir, "store");
        if (!privateRoot.mkdirs()) throw new IOException("private directory");
        output.append("privateoutputRunDir=files/pipeline-profile/").append(runDir.getName()).append('\n');
        PublishOps ops = new PublishOps();
        DiskFrameStore privateStore = new DiskFrameStore(privateRoot, ops::sync, ops);
        for (int i = 1; i <= 4; i++) {
            stage = "private-publish-" + i;
            checkBudget();
            long decodeStart = now();
            Bitmap bitmap = decode(i);
            long decodeMs = now() - decodeStart;
            try {
                DiskFrameStore.Frame f = new DiskFrameStore.Frame();
                f.sessionId = UUID.randomUUID().toString(); f.captureMethod = "profile"; f.captureSequence = i;
                f.captureWallMs = System.currentTimeMillis(); f.captureUptimeMs = now();
                f.width = bitmap.getWidth(); f.height = bitmap.getHeight(); f.imageHasAlpha = bitmap.hasAlpha();
                f.imageFileName = ImageEncodingPolicy.fileName(Build.VERSION.SDK_INT);
                ops.reset();
                long[] encodeMs = {0};
                start = now();
                privateStore.publish(f, stream -> {
                    long t = now();
                    try { return bitmap.compress(Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.PNG,
                            ImageEncodingPolicy.quality(Build.VERSION.SDK_INT), stream); }
                    finally { encodeMs[0] = now() - t; }
                });
                output.append("publishAsset=").append(i).append(" decodeMs=").append(decodeMs)
                        .append(" inputPixels=").append((long) f.width * f.height).append(" knownFixtureAlpha=true hasAlpha=").append(f.imageHasAlpha)
                        .append(" encodedBytes=").append(f.imageBytes).append(" encodeMs=").append(encodeMs[0])
                        .append(" publishMs=").append(now() - start).append(" dirFsyncMs=").append(ops.syncMs)
                        .append(" dirFsyncCount=").append(ops.syncCount).append(" moveMs=").append(ops.moveMs)
                        .append(" moveCount=").append(ops.moveCount).append(" replaceMs=").append(ops.replaceMs)
                        .append(" replaceCount=").append(ops.replaceCount).append('\n');
            } finally { bitmap.recycle(); }
        }
        profileOcr();
        stage = "complete";
    }

    private Bitmap decode(int index) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = instrumentation.getContext().getAssets().open(String.format(Locale.ROOT, "profile-%02d.webp", index))) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) throw new IOException("asset decode");
            // The four selected source manifests all record imageHasAlpha=true.
            bitmap.setHasAlpha(true);
            return bitmap;
        }
    }

    private void profileOcr() throws Exception {
        final class Resources {
            TextRecognizer recognizer;
            Bitmap bitmap;
            void close() { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); if (recognizer != null) recognizer.close(); }
        }
        Resources resources = new Resources();
        NotificationOcrLease lease = new NotificationOcrLease.Gate().acquire(resources::close);
        if (lease == null) throw new IllegalStateException("lease unavailable");
        try {
            resources.recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            for (int round = 1; round <= 2; round++) for (int i = 1; i <= 4; i++) {
                stage = "ocr-" + round + "-" + i;
                checkBudget();
                long decodeStart = now();
                resources.bitmap = decode(i);
                long decodeMs = now() - decodeStart;
                Bitmap bitmap = resources.bitmap;
                long token = lease.beginTask();
                long start = now();
                Task<Text> task;
                nativePending = true;
                try { task = resources.recognizer.process(InputImage.fromBitmap(bitmap, 0)); }
                catch (RuntimeException | Error e) { nativePending = false; lease.nativeDone(token); throw e; }
                long returned = now();
                boolean completeImmediately = task.isComplete();
                AtomicLong callbackAt = new AtomicLong(-1);
                lease.setProbe(token, task::isComplete);
                task.addOnCompleteListener(Runnable::run, ignored -> { callbackAt.set(now()); lease.nativeDone(token); });
                output.append("ocrRound=").append(round).append(" asset=").append(i).append(" decodeMs=").append(decodeMs)
                        .append(" inputPixels=").append((long) bitmap.getWidth() * bitmap.getHeight())
                        .append(" knownFixtureAlpha=true processCallReturnMs=").append(returned - start)
                        .append(" taskIsCompleteImmediatelyAfterProcess=").append(completeImmediately);
                try {
                    long remaining = Math.min(12_000, deadline - now());
                    if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
                    Text text = Tasks.await(task, remaining, TimeUnit.MILLISECONDS);
                    long awaited = now();
                    nativePending = false;
                    lease.nativeDone(token);
                    List<OcrLine> lines = new ArrayList<>();
                    for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
                        Rect box = line.getBoundingBox();
                        if (box != null) lines.add(new OcrLine(line.getText(), box.left, box.top, box.right, box.bottom));
                    }
                    output.append(" directCompletionObservedMs=").append(callbackAt.get() < 0 ? -1 : callbackAt.get() - start)
                            .append(" workerAwaitReturnMs=").append(awaited - start)
                            .append(" countdownCount=").append(OcrFrameParser.countdowns(lines).size()).append('\n');
                    bitmap.recycle(); resources.bitmap = null;
                } catch (Exception e) {
                    nativePending = !task.isComplete();
                    output.append(" awaitError=").append(e.getClass().getSimpleName()).append(" taskIsComplete=").append(task.isComplete())
                            .append(" directCompletionObservedMs=").append(callbackAt.get() < 0 ? -1 : callbackAt.get() - start)
                            .append(" workerAwaitReturnMs=").append(now() - start).append('\n');
                    throw e;
                }
            }
        } finally { lease.consumerDone(); }
    }

    private void checkBudget() throws java.util.concurrent.TimeoutException {
        if (now() >= deadline) throw new java.util.concurrent.TimeoutException("overall budget");
    }
    private static long now() { return SystemClock.elapsedRealtime(); }

    private static final class PublishOps implements DiskFrameStore.FileOps {
        long syncMs, moveMs, replaceMs;
        int syncCount, moveCount, replaceCount;
        void reset() { syncMs = moveMs = replaceMs = 0; syncCount = moveCount = replaceCount = 0; }
        void sync(File directory) throws IOException {
            long start = now(); syncCount++;
            FileDescriptor fd = null;
            try {
                if (!directory.isDirectory()) throw new IOException("directory sync target");
                fd = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
                Os.fsync(fd);
            } catch (ErrnoException e) { throw new IOException("directory sync failed", e); }
            finally {
                if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { }
                syncMs += now() - start;
            }
        }
        @Override public void move(File source, File target) throws IOException {
            long start = now(); moveCount++;
            try { DiskFrameStore.DEFAULT_OPS.move(source, target); } finally { moveMs += now() - start; }
        }
        @Override public void replace(File source, File target) throws IOException {
            long start = now(); replaceCount++;
            try { DiskFrameStore.DEFAULT_OPS.replace(source, target); } finally { replaceMs += now() - start; }
        }
        @Override public boolean delete(File file) { throw new IllegalStateException("unexpected delete"); }
    }
}
