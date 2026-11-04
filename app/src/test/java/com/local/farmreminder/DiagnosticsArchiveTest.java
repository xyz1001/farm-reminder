package com.local.farmreminder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static org.junit.Assert.*;

public class DiagnosticsArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private DiskFrameStore open(File root) throws IOException { return new DiskFrameStore(root, dir -> {}, DiskFrameStore.DEFAULT_OPS); }
    private DiskFrameStore.Frame publish(DiskFrameStore store, int sequence) throws IOException {
        DiskFrameStore.Frame f = new DiskFrameStore.Frame();
        f.sessionId = UUID.randomUUID().toString(); f.captureSequence = sequence; f.captureWallMs = 1234;
        f.captureUptimeMs = 99; f.windowId = 4; f.captureMethod = "window"; f.width = 100; f.height = 200;
        return store.publish(f, out -> { out.write(new byte[]{1, 2, 3}); return true; });
    }
    private Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e; byte[] buffer = new byte[1024];
            while ((e = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                files.put(e.getName(), out.toByteArray());
            }
        }
        return files;
    }
    @Test public void completedArchiveSurvivesRestartAndRetryAndCarriesNoMatch() throws Exception {
        File root = temp.newFolder(); DiskFrameStore store = open(root); publish(store, 1);
        DiskFrameStore.Frame f = store.claimNext();
        store.writeDiagnostic(f, "ocr-lines.tsv", DiagnosticCodec.lines(Arrays.asList(new OcrLine("昵称\t一\n二\\三", 1, 2, 3, 4))));
        ReminderStore.FrameOutcome outcome = new ReminderStore.FrameOutcome(true, false, false, false, Collections.emptyList(), "NO_MATCH");
        store.writeDiagnostic(f, "result.properties", DiagnosticCodec.result(f, "名字", Arrays.asList(123L, 456L), outcome));
        store.acknowledge(f); long retained = store.scan().retainedBytes;
        store = open(root); store.recover(); store.retryFailed();
        DiskFrameStore.Snapshot s = store.scan();
        assertEquals(1, s.archivedCount); assertEquals(0, s.cleanupPending); assertEquals(retained, s.retainedBytes);
        assertTrue(store.image(f).isFile()); assertNull(store.claimNext());
        Properties p = new Properties();
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(new File(root, f.id + "/result.properties").toPath()), StandardCharsets.UTF_8)) { p.load(reader); }
        assertEquals(f.id, p.getProperty("frameId")); assertEquals("好友", p.getProperty("selectedName"));
        assertEquals("NO_MATCH", p.getProperty("code")); assertEquals("123,456", p.getProperty("countdownsMs"));
        String lines = new String(Files.readAllBytes(new File(root, f.id + "/ocr-lines.tsv").toPath()), StandardCharsets.UTF_8);
        assertTrue(lines.contains("昵称\\t一\\n二\\\\三\t1\t2\t3\t4"));
    }
    @Test public void exportUsesSnapshotAndOmitsTmp() throws Exception {
        File root = temp.newFolder(); DiskFrameStore store = open(root); publish(store, 1);
        DiskFrameStore.Frame f = store.claimNext();
        store.writeDiagnostic(f, "ocr-lines.tsv", DiagnosticCodec.lines(Collections.singletonList(new OcrLine("中文", 1, 2, 3, 4))));
        store.writeDiagnostic(f, "result.properties", DiagnosticCodec.result(f, null, Collections.emptyList(), null));
        store.acknowledge(f); DiskFrameStore.Snapshot snapshot = store.scan();
        Files.write(new File(root, f.id + "/unfinished.tmp").toPath(), new byte[]{9});
        DiskFrameStore.Frame later = publish(store, 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(1, DiagnosticsExporter.export(store, snapshot, out, "test", true));
        Map<String, byte[]> zip = unzip(out.toByteArray());
        assertTrue(zip.containsKey("frames/" + f.id + "/image.png"));
        assertTrue(zip.containsKey("frames/" + f.id + "/manifest.properties"));
        assertTrue(zip.containsKey("frames/" + f.id + "/result.properties"));
        assertTrue(zip.containsKey("frames/" + f.id + "/ocr-lines.tsv"));
        assertFalse(zip.containsKey("frames/" + f.id + "/unfinished.tmp"));
        assertFalse(zip.containsKey("frames/" + later.id + "/image.png"));
        assertTrue(new String(zip.get("summary.properties"), StandardCharsets.ISO_8859_1).contains("unsavedCaptureInRam=true"));
    }
    @Test public void diagnosticFailureAndExportFailureKeepOriginal() throws Exception {
        File root = temp.newFolder(); DiskFrameStore store = open(root); publish(store, 1);
        DiskFrameStore.Frame f = store.claimNext();
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            public void move(File a, File b) throws IOException { DiskFrameStore.DEFAULT_OPS.move(a, b); }
            public void replace(File a, File b) throws IOException { throw new IOException("injected"); }
            public boolean delete(File f) { return false; }
        };
        try { new DiskFrameStore(root, dir -> {}, ops).writeDiagnostic(f, "ocr-lines.tsv", new byte[]{1}); fail(); } catch (IOException expected) { }
        assertTrue(store.image(f).isFile()); store.fail(f, "STORAGE_FAILED");
        try { DiagnosticsExporter.export(store, store.scan(), new java.io.OutputStream() { public void write(int value) throws IOException { throw new IOException("injected"); } }, "test", false); fail(); }
        catch (IOException expected) { }
        assertTrue(store.image(f).isFile()); assertEquals(DiskFrameStore.State.FAILED, store.scan().frames.get(0).state);
    }
    @Test public void symlinkArtifactCannotEscapeRoot() throws Exception {
        File root = temp.newFolder(); DiskFrameStore store = open(root); DiskFrameStore.Frame f = publish(store, 1);
        File outside = temp.newFile(); Files.write(outside.toPath(), new byte[]{7});
        try { Files.createSymbolicLink(new File(root, f.id + "/result.properties").toPath(), outside.toPath()); }
        catch (UnsupportedOperationException | IOException | SecurityException unavailable) { Assume.assumeNoException("symbolic links unavailable", unavailable); }
        try { DiagnosticsExporter.export(store, store.scan(), new ByteArrayOutputStream(), "test", false); fail(); }
        catch (IOException expected) { }
        assertTrue(store.image(f).isFile());
    }
    @Test public void symlinkFrameDirectoryCannotEscapeRoot() throws Exception {
        File root = temp.newFolder(); DiskFrameStore store = open(root); DiskFrameStore.Frame f = publish(store, 1);
        DiskFrameStore.Snapshot snapshot = store.scan();
        File outsideParent = temp.newFolder();
        java.nio.file.Path original = new File(root, f.id).toPath();
        java.nio.file.Path outside = outsideParent.toPath().resolve(f.id);
        Files.move(original, outside);
        Files.write(outside.resolve("result.properties"), "outside secret".getBytes(StandardCharsets.UTF_8));
        try { Files.createSymbolicLink(original, outside); }
        catch (UnsupportedOperationException | IOException | SecurityException unavailable) { Assume.assumeNoException("symbolic links unavailable", unavailable); }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { DiagnosticsExporter.export(store, snapshot, out, "test", false); fail("must reject linked frame directory"); }
        catch (IOException expected) { }
        assertFalse(new String(out.toByteArray(), StandardCharsets.ISO_8859_1).contains("outside secret"));
        assertTrue(Files.exists(outside.resolve("image.png")));
    }
    @Test public void pendingFrameWithoutDiagnosticsCanBeExported() throws Exception {
        File root = temp.newFolder(); DiskFrameStore store = open(root); DiskFrameStore.Frame f = publish(store, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(1, DiagnosticsExporter.export(store, store.scan(), out, "test", false));
        Map<String, byte[]> zip = unzip(out.toByteArray());
        assertTrue(zip.containsKey("frames/" + f.id + "/image.png"));
        assertFalse(zip.containsKey("frames/" + f.id + "/result.properties"));
        assertTrue(new String(zip.get("summary.properties"), StandardCharsets.ISO_8859_1).contains("incompleteCount=1"));
    }
    @Test public void webpExportPreservesBytesAndExcludesUnlistedFiles() throws Exception {
        File root = temp.newFolder(); DiskFrameStore store = open(root);
        DiskFrameStore.Frame f = new DiskFrameStore.Frame(); f.sessionId = UUID.randomUUID().toString();
        f.captureSequence = 1; f.captureMethod = "window"; f.width = 100; f.height = 200;
        f.imageFileName = "image.webp"; f.imageHasAlpha = false; byte[] bytes = {10,20,30,40};
        store.publish(f, out -> { out.write(bytes); return true; });
        Files.write(new File(root, f.id + "/unfinished.tmp").toPath(), new byte[]{99});
        Files.write(new File(root, "outside.txt").toPath(), new byte[]{88});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiagnosticsExporter.export(store, store.scan(), out, "test", false);
        Map<String, byte[]> zip = unzip(out.toByteArray());
        assertArrayEquals(bytes, zip.get("frames/" + f.id + "/image.webp"));
        assertFalse(zip.containsKey("frames/" + f.id + "/image.png"));
        assertFalse(zip.containsKey("frames/" + f.id + "/unfinished.tmp"));
        assertFalse(zip.containsKey("outside.txt"));
    }
}
