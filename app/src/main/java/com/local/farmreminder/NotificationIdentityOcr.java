package com.local.farmreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.SystemClock;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fresh optional notification OCR. This API is synchronous and worker-only. */
final class NotificationIdentityOcr {
    private static final NotificationOcrLease.Gate GATE = new NotificationOcrLease.Gate();
    private static final long MAX_PIXELS = 4_000_000, MAX_FILE = 64L * 1024 * 1024;
    private static final class Resources {
        Bitmap bitmap;
        TextRecognizer recognizer;
        void close() {
            try { if (recognizer != null) recognizer.close(); }
            finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
        }
    }
    private NotificationIdentityOcr() { }
    private static long remaining(long deadline) { return NotificationOcrBudget.remaining(deadline, SystemClock::elapsedRealtime); }

    static NotificationIdentityLabel read(Context context, String sourceFrameRef, long deadlineElapsed) {
        if (remaining(deadlineElapsed) <= 0) return NotificationIdentityLabel.EMPTY;
        Resources resources = new Resources();
        NotificationOcrLease lease = GATE.acquire(resources::close);
        if (lease == null) return NotificationIdentityLabel.EMPTY;
        boolean taskReturned = false;
        try {
            if (remaining(deadlineElapsed) <= 0) return NotificationIdentityLabel.EMPTY;
            File image = FrameSourceResolver.resolve(context, sourceFrameRef);
            if (remaining(deadlineElapsed) <= 0 || image == null || !image.isFile() || image.length() <= 0 || image.length() > MAX_FILE)
                return NotificationIdentityLabel.EMPTY;
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
            if (remaining(deadlineElapsed) <= 0 || bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 100000 || bounds.outHeight > 100000) return NotificationIdentityLabel.EMPTY;
            int sample = 1;
            while (((bounds.outWidth + (long) sample - 1) / sample) * ((bounds.outHeight + (long) sample - 1) / sample) > MAX_PIXELS) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            resources.bitmap = BitmapFactory.decodeFile(image.getAbsolutePath(), options);
            if (remaining(deadlineElapsed) <= 0 || resources.bitmap == null
                    || (long) resources.bitmap.getWidth() * resources.bitmap.getHeight() > MAX_PIXELS) return NotificationIdentityLabel.EMPTY;
            resources.recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            if (remaining(deadlineElapsed) <= 0) return NotificationIdentityLabel.EMPTY;
            Task<Text> task = NotificationOcrBudget.startIfTime(deadlineElapsed, SystemClock::elapsedRealtime,
                    () -> resources.recognizer.process(InputImage.fromBitmap(resources.bitmap, 0)));
            if (task == null) return NotificationIdentityLabel.EMPTY;
            taskReturned = true;
            lease.setProbe(task::isComplete);
            try { task.addOnCompleteListener(Runnable::run, ignored -> lease.nativeDone()); }
            catch (RuntimeException registrationFailure) {
                // The lease's maintenance executor probes without blocking this worker.
                return NotificationIdentityLabel.EMPTY;
            }
            Text result = NotificationOcrBudget.await(deadlineElapsed, SystemClock::elapsedRealtime,
                    wait -> Tasks.await(task, wait, TimeUnit.MILLISECONDS));
            long parseDeadline = deadlineElapsed > Long.MAX_VALUE - 250 ? Long.MAX_VALUE : deadlineElapsed + 250;
            if (remaining(parseDeadline) <= 0 || result == null) return NotificationIdentityLabel.EMPTY;
            int w = resources.bitmap.getWidth(), h = resources.bitmap.getHeight();
            List<OcrLine> lines = new ArrayList<>();
            List<NotificationIdentityParser.TextRow> rows = new ArrayList<>();
            for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) {
                OcrLine parent = validLine(line.getText(), line.getBoundingBox(), w, h);
                if (parent == null) continue;
                lines.add(parent);
                List<OcrLine> elements = new ArrayList<>();
                for (Text.Element element : line.getElements()) {
                    OcrLine child = validLine(element.getText(), element.getBoundingBox(), w, h);
                    if (child != null) elements.add(child);
                }
                rows.add(new NotificationIdentityParser.TextRow(parent, elements));
            }
            if (remaining(parseDeadline) <= 0) return NotificationIdentityLabel.EMPTY;
            int[] pixels = new int[w * h];
            resources.bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            if (remaining(parseDeadline) <= 0) return NotificationIdentityLabel.EMPTY;
            AvatarLocator.Bounds avatar = AvatarLocator.locate(pixels, w, h, lines);
            if (avatar == null || remaining(parseDeadline) <= 0) return NotificationIdentityLabel.EMPTY;
            int d = avatar.right - avatar.left;
            boolean completeBadge = d > 0 && avatar.left + 1.20 * d <= w && avatar.top + .88 * d <= h
                    && avatar.left + .68 * d >= 0 && avatar.top + .44 * d >= 0;
            NotificationIdentityLabel label = NotificationIdentityParser.parseRows(rows, avatar, w, h, true, completeBadge);
            return remaining(parseDeadline) > 0 && label != null ? label : NotificationIdentityLabel.EMPTY;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return NotificationIdentityLabel.EMPTY;
        } catch (Exception | OutOfMemoryError ignored) {
            return NotificationIdentityLabel.EMPTY;
        } finally {
            if (!taskReturned) lease.nativeDone();
            lease.consumerDone();
        }
    }
    private static OcrLine validLine(String text, Rect b, int w, int h) {
        if (b != null && b.left >= 0 && b.top >= 0 && b.right > b.left && b.bottom > b.top && b.right <= w && b.bottom <= h)
            return new OcrLine(text, b.left, b.top, b.right, b.bottom);
        return null;
    }
}
