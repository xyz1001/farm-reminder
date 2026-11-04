package com.local.farmreminder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.Properties;

/** Strict, read-only resolver for one exact frame, never a scan or recovery. */
final class FrameSourceFiles {
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    private static File child(File parent, String name) throws Exception {
        File result = new File(parent, name).getAbsoluteFile();
        if (Files.isSymbolicLink(result.toPath()) || !result.getCanonicalFile().equals(result)) throw new IllegalArgumentException();
        return result;
    }
    static File resolve(File root, String ref) {
        try {
            String id = SourceFrameRefPolicy.normalize(ref);
            if (id.isEmpty() || root == null) return null;
            root = root.getAbsoluteFile();
            if (Files.isSymbolicLink(root.toPath()) || !root.getCanonicalFile().equals(root) || !root.isDirectory()) return null;
            File dir = child(root, id); if (!dir.isDirectory()) return null;
            File manifest = child(dir, "manifest.properties"), initial = child(dir, "initial.properties");
            File metadata = Files.exists(manifest.toPath(), LinkOption.NOFOLLOW_LINKS) ? manifest : initial;
            if (!metadata.isFile() || metadata.length() <= 0 || metadata.length() > 65536) return null;
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(metadata)) { p.load(in); }
            if (!"1".equals(p.getProperty("schemaVersion")) || !id.equals(p.getProperty("frameId"))) return null;
            java.util.UUID.fromString(p.getProperty("captureSessionId"));
            if (Long.parseLong(p.getProperty("enqueueSequence")) <= 0 || Long.parseLong(p.getProperty("captureSequence")) <= 0
                    || Long.parseLong(p.getProperty("captureWallMs")) < 0 || Long.parseLong(p.getProperty("captureUptimeMs")) < 0) return null;
            Integer.parseInt(p.getProperty("windowId"));
            if (p.getProperty("captureMethod") == null || p.getProperty("captureMethod").isEmpty()) return null;
            String state = p.getProperty("state");
            if (!("PENDING".equals(state) || "PROCESSING".equals(state) || "FAILED".equals(state) || "COMPLETED".equals(state))
                    || Integer.parseInt(p.getProperty("attempts")) < 0) return null;
            for (String key : new String[]{"contextCertain", "imageHasAlpha"}) {
                String value = p.getProperty(key, "true");
                if (!"true".equals(value) && !"false".equals(value)) return null;
            }
            int width = Integer.parseInt(p.getProperty("width")), height = Integer.parseInt(p.getProperty("height"));
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || (long) width * height > 100_000_000L) return null;
            long length = Long.parseLong(p.getProperty("imageBytes")); String expected = p.getProperty("digest");
            if (length <= 0 || length > MAX_IMAGE_BYTES || expected == null || !expected.matches("[0-9a-f]{64}")) return null;
            String name = p.getProperty("imageFileName", "image.png");
            if (!"image.png".equals(name) && !"image.webp".equals(name)) return null;
            File image = child(dir, name); if (!image.isFile() || image.length() != length) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long read = 0;
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(image))) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) { read += n; if (read > length) return null; digest.update(buf, 0, n); }
            }
            if (read != length) return null;
            StringBuilder actual = new StringBuilder(64);
            for (byte b : digest.digest()) actual.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return expected.contentEquals(actual) ? image : null;
        } catch (Exception ignored) { return null; }
    }
}
