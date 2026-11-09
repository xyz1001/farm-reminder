package com.local.farmreminder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.Assert.*;

public class DiskFrameStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final DiskFrameStore.DirectorySync NOOP_SYNC = dir -> { };
    private static final String SESSION = UUID.randomUUID().toString();

    private DiskFrameStore open(File root) throws IOException {
        return new DiskFrameStore(root, NOOP_SYNC, DiskFrameStore.DEFAULT_OPS);
    }
    private DiskFrameStore.Frame frame(long sequence) {
        DiskFrameStore.Frame f = new DiskFrameStore.Frame();
        f.sessionId = SESSION; f.captureSequence = sequence; f.captureWallMs = 1700000000000L + sequence;
        f.captureUptimeMs = 1000 + sequence; f.windowId = 4; f.captureMethod = "window";
        f.width = 100; f.height = 200;
        return f;
    }
    private DiskFrameStore.Frame publish(DiskFrameStore store, int n) throws IOException {
        return store.publish(frame(n), out -> { out.write(("image" + n).getBytes(StandardCharsets.UTF_8)); return true; });
    }

    @Test public void currentProcessFifoHandlesMoreThanFortyFramesAndReopenIsEmpty() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root);
        for (int i = 1; i <= 45; i++) publish(store, i);
        for (int i = 1; i <= 45; i++) { DiskFrameStore.Frame f = store.claimNext(); assertEquals(i, f.captureSequence); store.acknowledge(f); }
        assertNull(open(root).claimNext()); assertEquals(46, publish(open(root), 46).enqueueSequence);
    }

    @Test public void interruptedProcessingNeedsExplicitRetry() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root);
        publish(store, 1); store.claimNext(); publish(store, 2); publish(store, 3);
        DiskFrameStore.Frame second = store.claimNext(); store.fail(second, "OCR_FAILED");
        DiskFrameStore reopened = open(root); reopened.retryFailed();
        assertEquals(0, reopened.counts().pending); assertNull(reopened.claimNext());
        assertEquals(DiskFrameStore.State.PROCESSING, reopened.scan().frames.get(0).state);
        assertEquals(DiskFrameStore.State.FAILED, reopened.scan().frames.get(1).state);
        assertEquals(DiskFrameStore.State.PENDING, reopened.scan().frames.get(2).state);
    }

    @Test public void returnedClaimKeepsFrameAndOrder() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root);
        publish(store, 1); publish(store, 2);
        DiskFrameStore.Frame first = store.claimNext(); store.returnPending(first);
        assertEquals(1, store.claimNext().captureSequence);
        assertEquals(2, store.scan().frames.size());
    }

    @Test public void encodingFailureLeavesStagingAndExistingFrames() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root); publish(store, 1);
        try { store.publish(frame(2), out -> { out.write(1); throw new IOException("disk full"); }); fail(); }
        catch (IOException expected) { }
        DiskFrameStore reopened = open(root);
        assertEquals(1, reopened.scan().pending);
        assertTrue(reopened.scan().failedArtifacts >= 1);
        assertNull(reopened.claimNext());
    }

    @Test public void failedFramesAreNeverClaimedWithoutRetry() throws Exception {
        DiskFrameStore store = open(temporary.newFolder()); publish(store, 1);
        DiskFrameStore.Frame f = store.claimNext(); store.fail(f, "OCR_FAILED");
        assertNull(store.claimNext()); assertTrue(store.image(f).isFile());
        store.retryFailed(); assertEquals(f.id, store.claimNext().id);
    }

    @Test public void completedCleanupFailureNeverReclaimsFrame() throws Exception {
        File root = temporary.newFolder();
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            @Override public void move(File source, File target) throws IOException { DiskFrameStore.DEFAULT_OPS.move(source, target); }
            @Override public void replace(File source, File target) throws IOException { DiskFrameStore.DEFAULT_OPS.replace(source, target); }
            @Override public boolean delete(File file) { return false; }
        };
        DiskFrameStore store = new DiskFrameStore(root, NOOP_SYNC, ops);
        publish(store, 1); DiskFrameStore.Frame f = store.claimNext(); store.acknowledge(f);
        assertFalse(store.cleanup(f)); assertEquals(1, store.scan().cleanupPending);
        assertNull(open(root).claimNext());
    }

    @Test public void failedManifestUpdateDoesNotAcknowledge() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore first = open(root);
        publish(first, 1); DiskFrameStore.Frame f = first.claimNext();
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            @Override public void move(File source, File target) throws IOException { DiskFrameStore.DEFAULT_OPS.move(source, target); }
            @Override public void replace(File source, File target) throws IOException { throw new IOException("injected failure"); }
            @Override public boolean delete(File file) { return DiskFrameStore.DEFAULT_OPS.delete(file); }
        };
        DiskFrameStore failing = new DiskFrameStore(root, NOOP_SYNC, ops);
        try { failing.acknowledge(f); fail(); } catch (IOException expected) { }
        assertTrue(first.image(f).isFile());
        assertEquals(DiskFrameStore.State.PROCESSING, open(root).scan().frames.get(0).state);
    }

    @Test public void failedFinalDirectoryDeleteRemainsCleanupNotOcr() throws Exception {
        File root = temporary.newFolder();
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            @Override public void move(File source, File target) throws IOException { DiskFrameStore.DEFAULT_OPS.move(source, target); }
            @Override public void replace(File source, File target) throws IOException { DiskFrameStore.DEFAULT_OPS.replace(source, target); }
            @Override public boolean delete(File file) { return file.isDirectory() ? false : DiskFrameStore.DEFAULT_OPS.delete(file); }
        };
        DiskFrameStore store = new DiskFrameStore(root, NOOP_SYNC, ops);
        publish(store, 1); DiskFrameStore.Frame f = store.claimNext(); store.acknowledge(f);
        assertFalse(store.cleanup(f));
        DiskFrameStore reopened = open(root);
        assertEquals(1, reopened.scan().cleanupPending);
        assertEquals(0, reopened.scan().failedArtifacts);
        assertNull(reopened.claimNext());
        reopened.cleanupTombstones();
        assertEquals(0, reopened.scan().cleanupPending);
    }

    @Test public void failedRecoveryIsVisibleAndCanBeRetried() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore original = open(root);
        publish(original, 1); original.claimNext();
        DiskFrameStore reopened = open(root); reopened.retryFailed();
        assertNull(reopened.claimNext());
        assertEquals(DiskFrameStore.State.PROCESSING, reopened.scan().frames.get(0).state);
    }

    @Test public void renamedImageRecoversWithSameIdAfterManifestFailure() throws Exception {
        File root = temporary.newFolder(); final boolean[] fail = {true};
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            public void move(File a, File b) throws IOException { if (b.getName().equals("manifest.properties") && fail[0]) { fail[0] = false; throw new IOException("injected"); } DiskFrameStore.DEFAULT_OPS.move(a, b); }
            public void replace(File a, File b) throws IOException { DiskFrameStore.DEFAULT_OPS.replace(a, b); }
            public boolean delete(File f) { return DiskFrameStore.DEFAULT_OPS.delete(f); }
        };
        DiskFrameStore store = new DiskFrameStore(root, NOOP_SYNC, ops); DiskFrameStore.Frame f = frame(1);
        try { store.publish(f, out -> { out.write(42); return true; }); fail(); } catch (IOException expected) { }
        String id = f.id; assertTrue(store.reconcilePublication(f)); assertTrue(store.reconcilePublication(f));
        assertEquals(id, store.claimNext().id); assertNull(store.claimNext());
    }

    @Test public void snapshotsDoNotHashImagesAndClaimsHashOnlySelectedFrames() throws Exception {
        File root = temporary.newFolder(); final int[] reads = {0};
        DiskFrameStore store = new DiskFrameStore(root, NOOP_SYNC, DiskFrameStore.DEFAULT_OPS, image -> {
            reads[0]++;
            byte[] hash;
            try { hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image.toPath())); }
            catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
            StringBuilder value = new StringBuilder();
            for (byte b : hash) value.append(String.format("%02x", b & 255));
            return value.toString();
        });
        for (int i = 1; i <= 12; i++) publish(store, i);
        store.scan(); store.scan(); assertEquals(0, reads[0]);
        for (int i = 1; i <= 12; i++) {
            DiskFrameStore.Frame f = store.claimNext(); assertNotNull(f);
            store.acknowledge(f);
        }
        assertNull(store.claimNext()); assertEquals(12, reads[0]);
    }

    @Test public void digestMismatchFailsFrameAndContinuesToNext() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root);
        DiskFrameStore.Frame first = publish(store, 1); publish(store, 2);
        Files.write(store.image(first).toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, store.claimNext().captureSequence);
        assertEquals(DiskFrameStore.State.FAILED, store.scan().frames.get(0).state);
    }

    @Test public void uncertainContextPersistsAcrossReopen() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root);
        DiskFrameStore.Frame f = frame(1); f.contextCertain = false;
        store.publish(f, out -> { out.write(1); return true; });
        DiskFrameStore.Frame claim = store.claimNext();
        assertNotNull(claim); assertFalse(claim.contextCertain);
    }

    @Test public void currentPublicationReconcilesWithoutForkingAndPreservesFifo() throws Exception {
        File root = temporary.newFolder(); final boolean[] fail = {true};
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            public void move(File a, File b) throws IOException { if (b.getName().equals("manifest.properties") && fail[0]) { fail[0] = false; throw new IOException("injected"); } DiskFrameStore.DEFAULT_OPS.move(a,b); }
            public void replace(File a, File b) throws IOException { DiskFrameStore.DEFAULT_OPS.replace(a,b); }
            public boolean delete(File f) { return DiskFrameStore.DEFAULT_OPS.delete(f); }
        };
        DiskFrameStore store = new DiskFrameStore(root, NOOP_SYNC, ops); DiskFrameStore.Frame f = frame(1);
        try { store.publish(f, out -> { out.write(42); return true; }); fail(); } catch (IOException expected) { }
        String id = f.id; assertTrue(store.reconcilePublication(f));
        publish(store, 2); assertEquals(id, store.claimNext().id); assertEquals(2, store.claimNext().captureSequence);
    }

    @Test public void incompleteStageCannotForkOnReconcileOrPublish() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root); DiskFrameStore.Frame f = frame(1);
        try { store.publish(f, out -> { out.write(42); throw new IOException("injected"); }); fail(); }
        catch (IOException expected) { }
        String id = f.id; long sequence = f.enqueueSequence;
        try { store.reconcilePublication(f); fail(); } catch (IOException expected) { }
        try { store.publish(f, out -> { out.write(42); return true; }); fail(); } catch (IOException expected) { }
        assertEquals(id, f.id); assertEquals(sequence, f.enqueueSequence);
        assertTrue(new File(root, id + ".staging/image.tmp").isFile());
        assertEquals(1, store.scan().failedArtifacts); assertEquals(0, store.scan().pending);
    }

    @Test public void noFrameArtifactAllowsNewPublicationWithSameCaptureMetadata() throws Exception {
        File root = temporary.newFolder(); final boolean[] failSequence = {true};
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            @Override public void move(File source, File target) throws IOException { DiskFrameStore.DEFAULT_OPS.move(source, target); }
            @Override public void replace(File source, File target) throws IOException {
                if (failSequence[0]) { failSequence[0] = false; throw new IOException("injected"); }
                DiskFrameStore.DEFAULT_OPS.replace(source, target);
            }
            @Override public boolean delete(File file) { return DiskFrameStore.DEFAULT_OPS.delete(file); }
        };
        DiskFrameStore store = new DiskFrameStore(root, NOOP_SYNC, ops); DiskFrameStore.Frame f = frame(7);
        try { store.publish(f, out -> { out.write(1); return true; }); fail(); } catch (IOException expected) { }
        assertFalse(store.reconcilePublication(f));
        store.publish(f, out -> { out.write(1); return true; });
        DiskFrameStore.Frame claim = store.claimNext(); assertNotNull(claim);
        assertEquals(7, claim.captureSequence); assertEquals(2, claim.enqueueSequence);
        assertEquals(SESSION, claim.sessionId); assertEquals(f.captureWallMs, claim.captureWallMs);
    }

    @Test public void completedFrameCannotBeRepublished() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root);
        DiskFrameStore.Frame f = publish(store, 1); DiskFrameStore.Frame claim = store.claimNext();
        store.acknowledge(claim);
        store.publish(f, out -> { fail("must not rewrite completed frame"); return false; });
        assertEquals(DiskFrameStore.State.COMPLETED, f.state); assertEquals(0, store.scan().pending);
    }

    @Test public void invalidStageIsPreservedAndCannotFork() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root); DiskFrameStore.Frame f = frame(1);
        try { store.publish(f, out -> { out.write(1); throw new IOException("injected"); }); fail(); }
        catch (IOException expected) { }
        File initial = new File(root, f.id + ".staging/initial.properties");
        Files.write(initial.toPath(), "schemaVersion=unknown\n".getBytes(StandardCharsets.UTF_8));
        String id = f.id;
        try { store.reconcilePublication(f); fail(); } catch (IOException expected) { }
        try { store.publish(f, out -> { out.write(1); return true; }); fail(); } catch (IOException expected) { }
        assertEquals(id, f.id); assertTrue(initial.isFile()); assertEquals(1, store.scan().failedArtifacts);
    }

    @Test public void directPublishRetryAdoptsCompleteStage() throws Exception {
        File root = temporary.newFolder(); final boolean[] failManifest = {true};
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            @Override public void move(File source, File target) throws IOException {
                if (target.getName().equals("manifest.properties") && failManifest[0]) {
                    failManifest[0] = false; throw new IOException("injected");
                }
                DiskFrameStore.DEFAULT_OPS.move(source, target);
            }
            @Override public void replace(File source, File target) throws IOException { DiskFrameStore.DEFAULT_OPS.replace(source, target); }
            @Override public boolean delete(File file) { return DiskFrameStore.DEFAULT_OPS.delete(file); }
        };
        DiskFrameStore store = new DiskFrameStore(root, NOOP_SYNC, ops); DiskFrameStore.Frame f = frame(1);
        try { store.publish(f, out -> { out.write(1); return true; }); fail(); } catch (IOException expected) { }
        String id = f.id; long sequence = f.enqueueSequence;
        store.publish(f, out -> { fail("must adopt existing image"); return false; });
        assertEquals(id, f.id); assertEquals(sequence, f.enqueueSequence); assertEquals(1, store.scan().pending);
    }
    @Test public void webpMetadataAndHashSurviveReopenForBothAlphaValues() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root);
        for (int i = 1; i <= 2; i++) {
            DiskFrameStore.Frame f = frame(i); f.imageFileName = "image.webp"; f.imageHasAlpha = i == 1;
            store.publish(f, out -> { out.write(new byte[]{1,2,3,4}); return true; });
        }
        for (int i = 1; i <= 2; i++) {
            DiskFrameStore.Frame claim = store.claimNext(); assertNotNull(claim);
            assertEquals("image.webp", claim.imageFileName); assertEquals(i == 1, claim.imageHasAlpha);
            assertArrayEquals(new byte[]{1,2,3,4}, Files.readAllBytes(store.image(claim).toPath()));
            store.acknowledge(claim);
        }
    }
    @Test public void oldPngMetadataDefaultsOnReopen() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root); DiskFrameStore.Frame f = publish(store, 1);
        for (String name : new String[]{"initial.properties", "manifest.properties"}) {
            File file = new File(root, f.id + "/" + name); java.util.Properties p = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) { p.load(in); }
            p.remove("imageFileName"); p.remove("imageHasAlpha");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { p.store(out, null); }
        }
        DiskFrameStore.Frame claim = open(root).scan().frames.get(0);
        assertNotNull(claim); assertEquals("image.png", claim.imageFileName); assertTrue(claim.imageHasAlpha);
    }
    @Test public void webpCompleteStageReconcilesSameIdAfterFailure() throws Exception {
        File root = temporary.newFolder(); final boolean[] failManifest = {true};
        DiskFrameStore.FileOps ops = new DiskFrameStore.FileOps() {
            public void move(File a, File b) throws IOException {
                if (b.getName().equals("manifest.properties") && failManifest[0]) { failManifest[0] = false; throw new IOException("injected"); }
                DiskFrameStore.DEFAULT_OPS.move(a,b);
            }
            public void replace(File a, File b) throws IOException { DiskFrameStore.DEFAULT_OPS.replace(a,b); }
            public boolean delete(File f) { return DiskFrameStore.DEFAULT_OPS.delete(f); }
        };
        DiskFrameStore store = new DiskFrameStore(root, NOOP_SYNC, ops); DiskFrameStore.Frame f = frame(1);
        f.imageFileName = "image.webp"; f.imageHasAlpha = false;
        try { store.publish(f, out -> { out.write(42); return true; }); fail(); } catch (IOException expected) { }
        String id = f.id; assertTrue(store.reconcilePublication(f));
        store.publish(f, out -> { fail("must not rewrite"); return false; });
        DiskFrameStore.Frame claim = store.claimNext(); assertEquals(id, claim.id); assertFalse(claim.imageHasAlpha);
        assertEquals("image.webp", store.image(claim).getName());
    }
    @Test public void unsafeImageNameRejectedOnWriteAndRead() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root); DiskFrameStore.Frame bad = frame(1);
        bad.imageFileName = "../image.webp";
        try { store.publish(bad, out -> true); fail(); } catch (IOException expected) { }
        DiskFrameStore.Frame f = publish(store, 2); File manifest = new File(root, f.id + "/manifest.properties");
        java.util.Properties p = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(manifest)) { p.load(in); }
        p.setProperty("imageFileName", "../image.webp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(manifest)) { p.store(out, null); }
        assertEquals(1, open(root).scan().failedArtifacts); assertNull(open(root).claimNext());
    }
    @Test public void cachedCountsTrackTransitionsDiagnosticsAndMutableInput() throws Exception {
        DiskFrameStore store = open(temporary.newFolder());
        DiskFrameStore.Frame first = publish(store, 1); publish(store, 2);
        first.enqueueSequence = 99999; first.state = DiskFrameStore.State.FAILED;
        DiskFrameStore.Frame claimed = store.claimNext(); assertEquals(1, claimed.captureSequence);
        store.writeDiagnostic(claimed, "ocr-lines.tsv", new byte[73]); store.fail(claimed, "failed");
        assertEquals(1, store.counts().pending); assertEquals(1, store.counts().failedFrames);
        store.retryFailed(); assertEquals(2, store.claimNext().captureSequence); assertEquals(1, store.claimNext().captureSequence);
    }

    @Test public void hotReadsDoNotGrowWithCompletedArchive() throws Exception {
        for (int count : new int[]{0, 1000, 4745}) {
            File root = temporary.newFolder(); DiskFrameStore store = open(root);
            DiskFrameStore.Frame template = publish(store, 1);
            DiskFrameStore.Frame completed = store.claimNext(); store.acknowledge(completed);
            File source = new File(root, template.id);
            java.util.Properties p = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(new File(source, "manifest.properties"))) { p.load(in); }
            if (count == 0) { assertTrue(store.cleanup(completed)); }
            for (int i = 1; i < count; i++) {
                String id = UUID.randomUUID().toString(); File dir = new File(root, id); assertTrue(dir.mkdir());
                p.setProperty("frameId", id); p.setProperty("enqueueSequence", Integer.toString(i + 1));
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, "manifest.properties"))) { p.store(out, null); }
                Files.copy(new File(source, "image.png").toPath(), new File(dir, "image.png").toPath());
            }
            store.scan();
            final int[] reads = {0}, lists = {0}, rootLists = {0};
            store.setReadObserver(new DiskFrameStore.ReadObserver() {
                public void read(File path) { reads[0]++; }
                public void list(File path) { lists[0]++; if (root.equals(path)) rootLists[0]++; }
            });
            DiskFrameStore.Frame fresh = frame(count + 2);
            assertFalse(store.reconcilePublication(fresh));
            store.publish(fresh, out -> { out.write(42); return true; });
            assertEquals("fresh reconcile/publish must not scan root", 0, rootLists[0]);
            reads[0] = 0; lists[0] = 0;
            for (int i = 0; i < 10; i++) assertEquals(1, store.counts().pending);
            assertEquals(0, reads[0]); assertEquals(0, lists[0]);
            assertNotNull(store.claimNext());
            assertTrue("manifest reads " + reads[0], reads[0] <= 3);
            assertTrue("directory lists " + lists[0], lists[0] <= 2);
        }
    }

    @Test public void uncertainManifestSyncRequiresExplicitRecovery() throws Exception {
        File root = temporary.newFolder(); final boolean[] fail = {false};
        DiskFrameStore store = new DiskFrameStore(root, dir -> { if (fail[0] && !dir.equals(root)) { fail[0] = false; throw new IOException("injected"); } }, DiskFrameStore.DEFAULT_OPS);
        publish(store, 1); fail[0] = true;
        try { store.claimNext(); fail(); } catch (IOException expected) { }
        try { store.counts(); fail(); } catch (IOException expected) { }
        assertNull(open(root).claimNext());
    }

    @Test public void failedFullRecoveryCannotBeClearedByPublicationReconciliation() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore old = open(root); publish(old, 1);
        DiskFrameStore reopened = open(root);
        reopened.setReadObserver(new DiskFrameStore.ReadObserver() {
            public void read(File p) { fail("unexpected read: " + p); }
            public void list(File p) { fail("unexpected list: " + p); }
        });
        assertEquals(0, reopened.counts().pending); assertNull(reopened.claimNext()); reopened.retryFailed();
    }

    @Test public void constructorAndCountsNeverEnumerateHistoricalRoot() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore original = open(root); publish(original, 1);
        File noList = new File(root.getAbsolutePath()) {
            @Override public File[] listFiles() { throw new AssertionError("historical enumeration"); }
        };
        DiskFrameStore reopened = open(noList);
        assertEquals(0, reopened.counts().pending);
        assertNull(reopened.claimNext());
        reopened.retryFailed();
    }

    @Test public void explicitScanIncludesOldFramesWithoutChangingLiveFifo() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore old = open(root); publish(old, 1);
        DiskFrameStore store = open(root); publish(store, 2); publish(store, 3);
        assertEquals(3, store.scan().frames.size());
        assertEquals(2, store.counts().pending);
        assertEquals(2, store.claimNext().captureSequence);
        assertEquals(3, store.claimNext().captureSequence);
        assertNull(store.claimNext());
    }

    @Test public void finalizedNonDebugFrameIsDeletedButProcessingAndHistoryRemain() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore old = open(root); DiskFrameStore.Frame historical = publish(old, 1);
        DiskFrameStore store = open(root); DiskFrameStore.Frame fresh = publish(store, 2);
        DiskFrameStore.Frame claim = store.claimNext();
        assertFalse(claim.keepScreenshot);
        try { store.discardFinalized(claim); fail(); } catch (IOException expected) { }
        assertTrue(store.image(fresh).isFile());
        store.writeDiagnostic(claim, "ocr-lines.tsv", new byte[]{1});
        store.acknowledge(claim); store.discardFinalized(claim);
        assertFalse(store.image(fresh).exists()); assertFalse(new File(root, fresh.id).exists());
        assertTrue(old.image(historical).isFile()); assertEquals(0, store.counts().archivedCount);
    }

    @Test public void debugFlagSurvivesClaimsAndRetryAndRetainsFinalizedFrame() throws Exception {
        DiskFrameStore store = open(temporary.newFolder()); DiskFrameStore.Frame f = frame(1); f.keepScreenshot = true;
        store.publish(f, out -> { out.write(1); return true; });
        DiskFrameStore.Frame claim = store.claimNext(); assertTrue(claim.keepScreenshot);
        store.returnPending(claim); claim = store.claimNext(); assertTrue(claim.keepScreenshot);
        store.fail(claim, "OCR_TIMEOUT"); store.retryFailed();
        claim = store.claimNext(); assertTrue(claim.keepScreenshot);
        store.acknowledge(claim); store.discardFinalized(claim);
        assertTrue(store.image(claim).isFile()); assertEquals(1, store.counts().archivedCount);
    }

    @Test public void failedNonDebugFrameIsDeletedOnlyAfterFinalization() throws Exception {
        DiskFrameStore store = open(temporary.newFolder()); publish(store, 1);
        DiskFrameStore.Frame claim = store.claimNext();
        assertTrue(store.image(claim).isFile());
        store.fail(claim, "OCR_TIMEOUT"); store.discardFinalized(claim);
        assertFalse(store.image(claim).exists()); assertEquals(0, store.counts().failedFrames);
    }

    @Test public void corruptIndexedCandidateIsNeverClaimedAndScanSeesExternalChanges() throws Exception {
        File root = temporary.newFolder(); DiskFrameStore store = open(root);
        DiskFrameStore.Frame first = publish(store, 1); publish(store, 2); store.counts();
        Files.write(store.image(first).toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, store.claimNext().captureSequence);
        assertEquals(1, store.counts().failedFrames);
        File extra = new File(root, "external.txt"); Files.write(extra.toPath(), new byte[]{1, 2, 3});
        assertEquals(0, store.counts().failedArtifacts);
        assertEquals(1, store.scan().failedArtifacts); assertEquals(0, store.counts().failedArtifacts);
    }
}
