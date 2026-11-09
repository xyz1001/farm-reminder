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
        Bitmap crop, scaled;
        TextRecognizer recognizer;
        void close() {
            try { if (recognizer != null) recognizer.close(); }
            finally {
                if (scaled != null && scaled != crop && scaled != bitmap && !scaled.isRecycled()) scaled.recycle();
                if (crop != null && crop != bitmap && !crop.isRecycled()) crop.recycle();
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }
    }
    private NotificationIdentityOcr() { }
    private static long remaining(long deadline) { return NotificationOcrBudget.remaining(deadline, SystemClock::elapsedRealtime); }

    static NotificationIdentityLabel read(Context context, String sourceFrameRef, long deadlineElapsed) {
        if (remaining(deadlineElapsed) <= 0) return NotificationIdentityLabel.EMPTY;
        Resources resources = new Resources();
        NotificationOcrLease lease = GATE.acquire(resources::close);
        if (lease == null) return NotificationIdentityLabel.EMPTY;
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
            return readOwned(resources, lease, deadlineElapsed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return NotificationIdentityLabel.EMPTY;
        } catch (Exception | OutOfMemoryError ignored) {
            return NotificationIdentityLabel.EMPTY;
        } finally { lease.consumerDone(); }
    }

    /** Takes ownership of bitmap, including when native work outlives the caller. */
    static NotificationIdentityLabel readBitmap(Bitmap bitmap, long deadlineElapsed) {
        Resources resources = new Resources(); resources.bitmap = bitmap;
        NotificationOcrLease lease = GATE.acquire(resources::close);
        if (lease == null) { resources.close(); return NotificationIdentityLabel.EMPTY; }
        try { return readOwned(resources, lease, deadlineElapsed); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return NotificationIdentityLabel.EMPTY; }
        catch (Exception | OutOfMemoryError ignored) { return NotificationIdentityLabel.EMPTY; }
        finally { lease.consumerDone(); }
    }

    private static Text recognize(Resources resources, NotificationOcrLease lease, Bitmap bitmap, long deadline) throws Exception {
        if (remaining(deadline) <= 0) return null;
        long token = lease.beginTask();
        boolean submitted = false;
        try {
            Task<Text> task = NotificationOcrBudget.startIfTime(deadline, SystemClock::elapsedRealtime,
                    () -> resources.recognizer.process(InputImage.fromBitmap(bitmap, 0)));
            if (task == null) return null;
            submitted = true;
            lease.setProbe(token, task::isComplete);
            try { task.addOnCompleteListener(Runnable::run, ignored -> lease.nativeDone(token)); }
            catch (RuntimeException registrationFailure) { return null; }
            Text result = NotificationOcrBudget.await(deadline, SystemClock::elapsedRealtime,
                    wait -> Tasks.await(task, wait, TimeUnit.MILLISECONDS));
            if (task.isComplete()) lease.nativeDone(token);
            return result;
        } finally { if (!submitted) lease.nativeDone(token); }
    }

    private static NotificationIdentityLabel readOwned(Resources resources, NotificationOcrLease lease, long deadlineElapsed) throws Exception {
            if (resources.bitmap == null || remaining(deadlineElapsed) <= 0) return NotificationIdentityLabel.EMPTY;
            resources.recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            Text result = recognize(resources, lease, resources.bitmap, deadlineElapsed);
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
            if (remaining(parseDeadline) <= 0 || label == null) return NotificationIdentityLabel.EMPTY;
            if (label.hasLevel() || !completeBadge || remaining(deadlineElapsed) <= 0) return label;
            int[] crop = NotificationBadgePolicy.crop(avatar, w, h);
            if (crop == null) return label;
            try {
                resources.crop = Bitmap.createBitmap(resources.bitmap, crop[0], crop[1], crop[2]-crop[0], crop[3]-crop[1]);
                resources.scaled = Bitmap.createScaledBitmap(resources.crop, (crop[2]-crop[0])*3, (crop[3]-crop[1])*3, true);
                Text badge = recognize(resources, lease, resources.scaled, deadlineElapsed);
                if (badge == null || remaining(parseDeadline) <= 0) return label;
                List<NotificationIdentityParser.TextRow> badgeRows = new ArrayList<>();
                int bw = resources.scaled.getWidth(), bh = resources.scaled.getHeight();
                for (Text.TextBlock block : badge.getTextBlocks()) for (Text.Line line : block.getLines()) {
                    OcrLine parent = validLine(line.getText(), line.getBoundingBox(), bw, bh);
                    if (parent == null) return label;
                    List<OcrLine> children = new ArrayList<>();
                    for (Text.Element element : line.getElements()) {
                        OcrLine child = validLine(element.getText(), element.getBoundingBox(), bw, bh);
                        if (child == null) return label;
                        children.add(child);
                    }
                    badgeRows.add(new NotificationIdentityParser.TextRow(parent, children));
                }
                Integer level = NotificationBadgePolicy.level(badgeRows, bw, bh);
                return NotificationBadgePolicy.finish(label, level, parseDeadline, SystemClock::elapsedRealtime);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return label; }
            catch (Exception | OutOfMemoryError ignored) { return label; }
    }
    private static OcrLine validLine(String text, Rect b, int w, int h) {
        if (b != null && b.left >= 0 && b.top >= 0 && b.right > b.left && b.bottom > b.top && b.right <= w && b.bottom <= h)
            return new OcrLine(text, b.left, b.top, b.right, b.bottom);
        return null;
    }
}
