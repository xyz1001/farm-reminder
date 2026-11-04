package com.local.farmreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Independent optional crops. Both APIs are synchronous and must run off the UI thread. */
public final class AvatarStore {
    private static final int MAX_PIXELS = 65536, MAX_WORK_PIXELS = 2_097_152;
    private AvatarStore() { }
    private static File root(Context context, boolean create) throws Exception {
        File parent = context.getNoBackupFilesDir().getCanonicalFile();
        File dir = new File(parent, "avatars-v1");
        if (Files.isSymbolicLink(dir.toPath())) throw new IllegalArgumentException();
        if (create && !dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException();
        if (!dir.isDirectory() || !dir.getCanonicalFile().equals(dir.getAbsoluteFile())) throw new IllegalArgumentException();
        return dir;
    }
    private static File asset(File root, String key) throws Exception {
        if (AvatarRefPolicy.normalize(key).isEmpty()) throw new IllegalArgumentException();
        File file = new File(root, key + ".png");
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IllegalArgumentException();
        for (String suffix : new String[]{".new", ".bak"}) if (Files.isSymbolicLink(new File(file.getPath() + suffix).toPath())) throw new IllegalArgumentException();
        return file;
    }
    private static String key(String frameKey) throws Exception {
        if (frameKey == null || frameKey.isEmpty() || frameKey.length() > 512) return "";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(("avatar-v1:" + frameKey).getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static String saveFromFrame(Context context, File image, List<OcrLine> lines, String frameKey) {
        Bitmap decoded = null, crop = null, scaled = null;
        try {
            String key = key(frameKey); if (key.isEmpty() || image == null || lines == null || !image.isFile() || image.length() > 64L * 1024 * 1024) return "";
            File output = asset(root(context, true), key);
            if (output.isFile()) return key;
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 100000 || bounds.outHeight > 100000) return "";
            int sample = 1;
            while (((long) ((bounds.outWidth + (long) sample - 1) / sample) * ((bounds.outHeight + (long) sample - 1) / sample)) > MAX_WORK_PIXELS) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeFile(image.getAbsolutePath(), options); if (decoded == null) return "";
            int w = decoded.getWidth(), h = decoded.getHeight(); if ((long) w * h > MAX_WORK_PIXELS) return "";
            int[] pixels = new int[w * h]; decoded.getPixels(pixels, 0, w, 0, 0, w, h);
            List<OcrLine> mapped = new ArrayList<>();
            for (OcrLine line : lines) {
                if (line == null || line.left < 0 || line.top < 0 || line.right <= line.left || line.bottom <= line.top || line.right > bounds.outWidth || line.bottom > bounds.outHeight) continue;
                int l = (int) ((long) line.left * w / bounds.outWidth), t = (int) ((long) line.top * h / bounds.outHeight);
                int r = (int) (((long) line.right * w + bounds.outWidth - 1) / bounds.outWidth), b = (int) (((long) line.bottom * h + bounds.outHeight - 1) / bounds.outHeight);
                mapped.add(new OcrLine(line.text, l, t, r, b));
            }
            AvatarLocator.Bounds found = AvatarLocator.locate(pixels, w, h, mapped);
            if (found == null || found.left < 0 || found.top < 0 || found.right > w || found.bottom > h || found.right <= found.left || found.bottom <= found.top) return "";
            crop = Bitmap.createBitmap(decoded, found.left, found.top, found.right - found.left, found.bottom - found.top);
            float factor = Math.min(1f, 256f / Math.max(crop.getWidth(), crop.getHeight()));
            scaled = factor < 1 ? Bitmap.createScaledBitmap(crop, Math.max(1, Math.round(crop.getWidth() * factor)), Math.max(1, Math.round(crop.getHeight() * factor)), true) : crop;
            AtomicFile atomic = new AtomicFile(output); FileOutputStream stream = null;
            try { stream = atomic.startWrite(); if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException(); atomic.finishWrite(stream); }
            catch (Exception e) { if (stream != null) atomic.failWrite(stream); return ""; }
            return key;
        } catch (Exception | OutOfMemoryError ignored) { return ""; }
        finally { if (scaled != null && scaled != crop && scaled != decoded) scaled.recycle(); if (crop != null && crop != decoded) crop.recycle(); if (decoded != null) decoded.recycle(); }
    }
    public static Bitmap load(Context context, String key, int maxPixels) {
        Bitmap bitmap = null;
        try {
            if (maxPixels <= 0) return null;
            File file = asset(root(context, false), key); if (!file.isFile() || file.length() > 4L * 1024 * 1024) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 256 || bounds.outHeight > 256) return null;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath()); if (bitmap == null) return null;
            int cap = Math.min(MAX_PIXELS, maxPixels);
            if ((long) bitmap.getWidth() * bitmap.getHeight() <= cap) return bitmap;
            double factor = Math.sqrt((double) cap / ((long) bitmap.getWidth() * bitmap.getHeight()));
            int w = Math.max(1, (int) (bitmap.getWidth() * factor)), h = Math.max(1, (int) (bitmap.getHeight() * factor));
            while ((long) w * h > cap) { if (w > h) w--; else h--; }
            Bitmap result = Bitmap.createScaledBitmap(bitmap, w, h, true); if (result != bitmap) bitmap.recycle(); return result;
        } catch (Exception | OutOfMemoryError ignored) { if (bitmap != null) bitmap.recycle(); return null; }
    }
}
