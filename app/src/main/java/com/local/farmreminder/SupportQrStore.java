package com.local.farmreminder;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class SupportQrStore {
    static boolean needsLegacyPermission(Context context) {
        return needsLegacyPermission(Build.VERSION.SDK_INT,
                Build.VERSION.SDK_INT >= 29 || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
    }

    static boolean needsLegacyPermission(int sdk, boolean granted) {
        return sdk >= 26 && sdk <= 28 && !granted;
    }

    static Uri save(Context context) throws IOException, SecurityException {
        String name = "FarmReminder_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        if (Build.VERSION.SDK_INT >= 29) return saveModern(context, name);
        return saveLegacy(context, name);
    }

    private static Uri saveModern(Context context, String name) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        return SupportSavePipeline.save(new SupportSavePipeline.Target<Uri>() {
            public Uri create() {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FarmReminder");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            }
            public void write(Uri uri) throws IOException {
                OutputStream output = resolver.openOutputStream(uri, "w");
                if (output == null) throw new IOException("Cannot open image output");
                try (OutputStream out = output) { copyResource(context, out); }
            }
            public void publish(Uri uri) throws IOException {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (resolver.update(uri, values, null, null) <= 0) throw new IOException("Image publication failed");
            }
            public void cleanup(Uri uri) { resolver.delete(uri, null, null); }
        });
    }

    @SuppressWarnings("deprecation")
    private static Uri saveLegacy(Context context, String name) throws IOException {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FarmReminder");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create pictures directory");
        File file = new File(directory, name);
        if (!file.createNewFile()) throw new IOException("Image already exists");
        boolean complete = false;
        Uri uri = null;
        try {
            try (OutputStream out = new FileOutputStream(file)) { copyResource(context, out); }
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Image indexing failed");
            complete = true;
            return uri;
        } finally {
            if (!complete) {
                if (uri != null) try { context.getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) { }
                if (!file.delete() && file.exists()) android.util.Log.w("SupportQrStore", "Could not remove incomplete image");
            }
        }
    }

    private static void copyResource(Context context, OutputStream output) throws IOException {
        try (InputStream input = context.getResources().openRawResource(R.raw.support_qr)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private SupportQrStore() { }
}
