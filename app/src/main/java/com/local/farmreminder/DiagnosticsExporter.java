package com.local.farmreminder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports only known frame artifacts, from one disk-executor snapshot. */
final class DiagnosticsExporter {
    private static final String[] FILES = {"image.png", "initial.properties", "manifest.properties", "ocr-lines.tsv", "result.properties"};

    static int export(DiskFrameStore store, DiskFrameStore.Snapshot snapshot, OutputStream output,
                      String version, boolean unsaved) throws IOException {
        File root = store.root().toPath().toRealPath().toFile();
        List<DiskFrameStore.Frame> frames = new ArrayList<>(snapshot.frames);
        if (frames.isEmpty() && unsaved) throw new IOException("截图尚在内存中，无法导出；请稍后重试");
        int incomplete = 0;
        for (DiskFrameStore.Frame f : frames) {
            File dir = safeChild(root, f.id);
            if (f.state != DiskFrameStore.State.COMPLETED || !safeChild(dir, "result.properties").isFile()) incomplete++;
        }
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            Properties summary = new Properties();
            summary.setProperty("diagnosticSchema", "1");
            summary.setProperty("exportWallMs", Long.toString(System.currentTimeMillis()));
            summary.setProperty("softwareVersion", version);
            summary.setProperty("frameCount", Integer.toString(frames.size()));
            summary.setProperty("incompleteCount", Integer.toString(incomplete));
            summary.setProperty("unsavedCaptureInRam", Boolean.toString(unsaved));
            summary.setProperty("excludedArtifactCount", Integer.toString(snapshot.failedArtifacts + snapshot.cleanupPending));
            zip.putNextEntry(new ZipEntry("summary.properties")); summary.store(zip, "local sensitive diagnostics"); zip.closeEntry();
            byte[] buffer = new byte[32768];
            for (DiskFrameStore.Frame f : frames) {
                File dir = safeChild(root, f.id);
                if (!dir.isDirectory()) throw new IOException("frame directory unavailable");
                for (String name : FILES) {
                    File file = safeChild(dir, name);
                    if (!file.exists()) continue;
                    if (!file.isFile()) throw new IOException("invalid frame artifact");
                    zip.putNextEntry(new ZipEntry("frames/" + f.id + "/" + name));
                    try (FileInputStream in = new FileInputStream(file)) {
                        int n; while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry();
                }
            }
            zip.finish();
        }
        return frames.size();
    }
    private static File safeChild(File parent, String name) throws IOException {
        Path realParent = parent.toPath().toRealPath();
        Path child = realParent.resolve(name).normalize();
        if (!realParent.equals(child.getParent()) || Files.isSymbolicLink(child)) throw new IOException("invalid frame path");
        if (Files.exists(child, LinkOption.NOFOLLOW_LINKS) && !child.toRealPath().equals(child))
            throw new IOException("invalid frame path");
        return child.toFile();
    }
}
