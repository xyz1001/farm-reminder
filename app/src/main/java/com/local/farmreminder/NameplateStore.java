package com.local.farmreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Optional independent full nameplates. All methods are synchronous worker-only. */
public final class NameplateStore {
    private static final long MAX_FILE = 4L * 1024 * 1024, MAX_SOURCE = 64L * 1024 * 1024;
    private NameplateStore() { }
    private static File root(Context context, boolean create) throws Exception {
        File parent = context.getNoBackupFilesDir().getCanonicalFile(), dir = new File(parent, "nameplates-v1");
        if (Files.isSymbolicLink(dir.toPath())) throw new IllegalArgumentException();
        if (create && !dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException();
        if (!dir.isDirectory() || !dir.getCanonicalFile().equals(dir.getAbsoluteFile())) throw new IllegalArgumentException();
        return dir;
    }
    private static File asset(File root, String key) throws Exception {
        if (NameplateRefPolicy.normalize(key).isEmpty()) throw new IllegalArgumentException();
        File file = new File(root, key + ".png");
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IllegalArgumentException();
        for (String suffix : new String[]{".new", ".bak"})
            if (Files.isSymbolicLink(new File(file.getPath() + suffix).toPath())) throw new IllegalArgumentException();
        return file;
    }
    private static boolean validFile(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_FILE) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return NameplateAssetPolicy.stored(bounds.outWidth, bounds.outHeight);
    }
    private static boolean decodableFile(File file) {
        if (!validFile(file)) return false;
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            return bitmap != null && NameplateAssetPolicy.stored(bitmap.getWidth(), bitmap.getHeight());
        } finally { if (bitmap != null) bitmap.recycle(); }
    }
    public static String saveFromFrame(Context context, File image, List<OcrLine> lines, String frameKey) {
        Bitmap decoded = null, crop = null, scaled = null;
        try {
            String key = NameplateRefPolicy.key(frameKey);
            if (key.isEmpty() || image == null || lines == null || Files.isSymbolicLink(image.toPath())
                    || !image.isFile() || image.length() <= 0 || image.length() > MAX_SOURCE) return "";
            File output = asset(root(context, true), key);
            if (output.exists()) return decodableFile(output) ? key : "";
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 100000 || bounds.outHeight > 100000) return "";
            int sample = 1;
            while (((bounds.outWidth + (long) sample - 1) / sample) * ((bounds.outHeight + (long) sample - 1) / sample) > NameplateAssetPolicy.WORK_PIXELS) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeFile(image.getAbsolutePath(), options);
            if (decoded == null || (long) decoded.getWidth() * decoded.getHeight() > NameplateAssetPolicy.WORK_PIXELS) return "";
            int w = decoded.getWidth(), h = decoded.getHeight(); int[] pixels = new int[w * h];
            decoded.getPixels(pixels, 0, w, 0, 0, w, h);
            List<OcrLine> mapped = new ArrayList<>();
            for (OcrLine line : lines) {
                if (line == null || line.left < 0 || line.top < 0 || line.right <= line.left || line.bottom <= line.top || line.right > bounds.outWidth || line.bottom > bounds.outHeight) continue;
                int l = (int) ((long) line.left * w / bounds.outWidth), t = (int) ((long) line.top * h / bounds.outHeight);
                int r = (int) (((long) line.right * w + bounds.outWidth - 1) / bounds.outWidth), b = (int) (((long) line.bottom * h + bounds.outHeight - 1) / bounds.outHeight);
                mapped.add(new OcrLine(line.text, l, t, r, b));
            }
            NameplateLocator.Bounds found = NameplateLocator.locate(pixels, w, h, mapped);
            if (found == null || found.left < 0 || found.top < 0 || found.right > w || found.bottom > h
                    || !NameplateAssetPolicy.wide(found.right - found.left, found.bottom - found.top)) return "";
            crop = Bitmap.createBitmap(decoded, found.left, found.top, found.right - found.left, found.bottom - found.top);
            NameplateAssetPolicy.Size size = NameplateAssetPolicy.fit(crop.getWidth(), crop.getHeight(), NameplateAssetPolicy.MAX_EDGE,
                    NameplateAssetPolicy.MAX_EDGE, NameplateAssetPolicy.MAX_PIXELS);
            if (size == null || !NameplateAssetPolicy.stored(size.width, size.height)) return "";
            scaled = size.width == crop.getWidth() && size.height == crop.getHeight() ? crop
                    : Bitmap.createScaledBitmap(crop, size.width, size.height, true);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, encoded) || encoded.size() <= 0 || encoded.size() > MAX_FILE) return "";
            byte[] expected = encoded.toByteArray(); AtomicFile atomic = new AtomicFile(output); FileOutputStream stream = null; boolean active = false;
            try {
                stream = atomic.startWrite(); active = true; stream.write(expected); stream.getFD().sync();
                active = false; atomic.finishWrite(stream);
                try (FileInputStream in = atomic.openRead(); ByteArrayOutputStream verified = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) { if (verified.size() + n > MAX_FILE) return ""; verified.write(buffer, 0, n); }
                    if (!Arrays.equals(expected, verified.toByteArray()) || !decodableFile(output)) return "";
                }
            } catch (Exception e) { if (active && stream != null) atomic.failWrite(stream); return ""; }
            return key;
        } catch (Exception | OutOfMemoryError ignored) { return ""; }
        finally { if (scaled != null && scaled != crop && scaled != decoded) scaled.recycle(); if (crop != null && crop != decoded) crop.recycle(); if (decoded != null) decoded.recycle(); }
    }
    public static Bitmap load(Context context, String key, int maxPixels) {
        Bitmap bitmap = null;
        try {
            if (maxPixels <= 0) return null;
            File file = asset(root(context, false), key); if (!validFile(file)) return null;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null || !NameplateAssetPolicy.stored(bitmap.getWidth(), bitmap.getHeight())) return null;
            NameplateAssetPolicy.Size size = NameplateAssetPolicy.fit(bitmap.getWidth(), bitmap.getHeight(), NameplateAssetPolicy.MAX_EDGE,
                    NameplateAssetPolicy.MAX_EDGE, Math.min(NameplateAssetPolicy.MAX_PIXELS, maxPixels));
            if (size == null) return null;
            if (size.width == bitmap.getWidth() && size.height == bitmap.getHeight()) { Bitmap result = bitmap; bitmap = null; return result; }
            Bitmap result = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true);
            if (result == bitmap) bitmap = null;
            return result;
        } catch (Exception | OutOfMemoryError ignored) { return null; }
        finally { if (bitmap != null) bitmap.recycle(); }
    }
    public static Bitmap loadNotification(Context context, String key) {
        Bitmap original = null, result = null;
        try {
            original = load(context, key, NameplateAssetPolicy.MAX_PIXELS); if (original == null) return null;
            NameplateAssetPolicy.Size size = NameplateAssetPolicy.notificationFit(original.getWidth(), original.getHeight());
            if (size == null) return null;
            result = Bitmap.createBitmap(NameplateAssetPolicy.NOTIFY_WIDTH, NameplateAssetPolicy.NOTIFY_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result); canvas.drawColor(0x00000000);
            int x = (NameplateAssetPolicy.NOTIFY_WIDTH - size.width) / 2, y = (NameplateAssetPolicy.NOTIFY_HEIGHT - size.height) / 2;
            canvas.drawBitmap(original, null, new Rect(x, y, x + size.width, y + size.height), new Paint(Paint.FILTER_BITMAP_FLAG));
            Bitmap prepared = result; result = null; return prepared;
        } catch (Exception | OutOfMemoryError ignored) { return null; }
        finally { if (original != null) original.recycle(); if (result != null) result.recycle(); }
    }
}
