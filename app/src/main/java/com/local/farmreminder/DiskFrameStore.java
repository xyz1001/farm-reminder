package com.local.farmreminder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.ArrayDeque;

/**
 * Serial-executor-confined repository with a current-process FIFO. It does not start capture or OCR.
 * Finalized frames are retained only when requested; explicit export scans do not recover the queue.
 * The platform adapter must supply a directory fsync implementation.
 */
final class DiskFrameStore {
    interface DirectorySync { void sync(File directory) throws IOException; }
    interface ImageWriter { boolean write(OutputStream stream) throws IOException; }
    interface DigestReader { String digest(File image) throws IOException; }
    interface FileOps {
        void move(File source, File target) throws IOException;
        void replace(File source, File target) throws IOException;
        boolean delete(File path);
    }
    static final FileOps DEFAULT_OPS = new FileOps() {
        @Override public void move(File source, File target) throws IOException {
            if (target.exists() || !source.renameTo(target)) throw new IOException("rename failed");
        }
        @Override public void replace(File source, File target) throws IOException {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        @Override public boolean delete(File path) { return path.delete(); }
    };

    enum State { PENDING, PROCESSING, FAILED, COMPLETED }
    static final class Frame {
        String id, sessionId, captureMethod, digest, failureCode = "", imageFileName = "image.png";
        long enqueueSequence, captureSequence, captureWallMs, captureUptimeMs, imageBytes;
        int windowId, width, height, attempts;
        boolean contextCertain = true, imageHasAlpha = true;
        // Capture-time policy, intentionally process-local and never written to the manifest.
        boolean keepScreenshot;
        State state = State.PENDING;
    }
    static final class Snapshot {
        final List<Frame> frames = new ArrayList<>();
        int failedArtifacts, cleanupPending, archivedCount;
        long retainedBytes, pendingBytes;
        int pending;
    }
    static final class CountSnapshot {
        final int pending, failedFrames, failedArtifacts, cleanupPending, archivedCount;
        final long retainedBytes, pendingBytes;
        CountSnapshot(int pending, int failedFrames, int failedArtifacts, int cleanupPending,
                      int archivedCount, long retainedBytes, long pendingBytes) {
            this.pending = pending; this.failedFrames = failedFrames; this.failedArtifacts = failedArtifacts;
            this.cleanupPending = cleanupPending; this.archivedCount = archivedCount;
            this.retainedBytes = retainedBytes; this.pendingBytes = pendingBytes;
        }
    }

    interface ReadObserver { void read(File path); void list(File path); }
    private ReadObserver observer;
    void setReadObserver(ReadObserver observer) { this.observer = observer; }
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final ArrayDeque<Frame> failed = new ArrayDeque<>();
    private long retainedBytes, pendingBytes;
    private int archivedCount;
    private boolean uncertain;
    private String uncertainPublication;

    private final File root;
    private final DirectorySync sync;
    private final FileOps ops;
    private final DigestReader digestReader;
    private long nextSequence;

    DiskFrameStore(File root, DirectorySync sync, FileOps ops) throws IOException {
        this(root, sync, ops, DiskFrameStore::digestImage);
    }
    DiskFrameStore(File root, DirectorySync sync, FileOps ops, DigestReader digestReader) throws IOException {
        this.root = root; this.sync = sync; this.ops = ops;
        this.digestReader = digestReader;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create frame directory");
        File sequence = new File(root, "sequence.properties");
        if (sequence.exists()) {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(sequence)) { p.load(in); }
            try { nextSequence = Long.parseLong(p.getProperty("lastSequence")); }
            catch (RuntimeException e) { throw new IOException("invalid sequence ledger", e); }
        }
    }

    Frame publish(Frame f, ImageWriter writer) throws IOException {
        if (uncertain && (f.id == null || !f.id.equals(uncertainPublication)))
            throw new IOException("frame store unavailable");
        if (f.sessionId == null || f.captureMethod == null || f.width <= 0 || f.height <= 0 || !validImageName(f.imageFileName))
            throw new IOException("invalid capture metadata");
        try {
            Frame result = publishInternal(f, writer);
            enqueuePublished(result);
            return result;
        } catch (IOException | RuntimeException e) {
            uncertain = true; uncertainPublication = f.id; throw e;
        }
    }
    private void enqueuePublished(Frame f) throws IOException {
        if (f.state != State.PENDING) return;
        for (Frame existing : pending) if (existing.id.equals(f.id)) return;
        Frame copy = readFrame(directory(f));
        copy.keepScreenshot = f.keepScreenshot;
        pending.addLast(copy); pendingBytes += copy.imageBytes; retainedBytes += copy.imageBytes;
    }
    private Frame publishInternal(Frame f, ImageWriter writer) throws IOException {
        if (f.sessionId == null || f.captureMethod == null || f.width <= 0 || f.height <= 0 || !validImageName(f.imageFileName))
            throw new IOException("invalid capture metadata");
        if (f.id != null && reconcilePublication(f)) return f;
        f.id = UUID.randomUUID().toString();
        f.enqueueSequence = ++nextSequence;
        Properties sequence = new Properties(); sequence.setProperty("lastSequence", Long.toString(nextSequence));
        File sequenceTmp = new File(root, "sequence-" + UUID.randomUUID() + ".tmp");
        writeProperties(sequenceTmp, sequence);
        ops.replace(sequenceTmp, new File(root, "sequence.properties"));
        sync.sync(root);
        f.state = State.PENDING; f.attempts = 0; f.failureCode = "";
        File staging = new File(root, f.id + ".staging");
        if (!staging.mkdir()) throw new IOException("cannot create staging directory");
        sync.sync(root);
        // Initial metadata makes an interrupted write identifiable without inventing an image.
        writeProperties(new File(staging, "initial.properties"), properties(f));
        sync.sync(staging);
        File tmp = new File(staging, "image.tmp");
        MessageDigest digest = sha256();
        try (FileOutputStream raw = new FileOutputStream(tmp);
             DigestOutputStream hashed = new DigestOutputStream(raw, digest);
             BufferedOutputStream output = new BufferedOutputStream(hashed, 64 * 1024)) {
            if (!writer.write(output)) throw new IOException("image encoding failed");
            output.flush();
            raw.getFD().sync();
        }
        f.imageBytes = tmp.length();
        if (f.imageBytes <= 0) throw new IOException("empty image");
        f.digest = hex(digest.digest());
        ops.move(tmp, new File(staging, f.imageFileName));
        sync.sync(staging);
        writeManifest(staging, f, false);
        File published = new File(root, f.id);
        ops.move(staging, published);
        sync.sync(root);
        return f;
    }

    Snapshot scan() throws IOException {
        Snapshot result = new Snapshot();
        for (File dir : children(root)) {
            Snapshot part = scanEntry(dir);
            result.frames.addAll(part.frames);
            result.pending += part.pending; result.pendingBytes += part.pendingBytes;
            result.failedArtifacts += part.failedArtifacts; result.cleanupPending += part.cleanupPending;
            result.archivedCount += part.archivedCount; result.retainedBytes += part.retainedBytes;
        }
        result.frames.sort(Comparator.comparingLong((Frame f) -> f.enqueueSequence).thenComparing(f -> f.id));
        return result;
    }

    private Snapshot scanEntry(File dir) throws IOException {
        Snapshot result = new Snapshot();
        {
            if (dir.getName().equals("sequence.properties")) { result.retainedBytes += dir.length(); return result; }
            if (isCleanupTombstone(dir)) {
                result.cleanupPending++;
                result.retainedBytes += imageBytes(dir);
                for (File child : children(dir)) if (!isCleanupFile(child)) { result.failedArtifacts++; break; }
                return result;
            }
            if (!dir.isDirectory() || dir.getName().endsWith(".staging")) {
                result.failedArtifacts++; result.retainedBytes += imageBytes(dir); return result;
            }
            Frame f;
            try {
                f = readFrame(dir);
            } catch (IOException | RuntimeException e) {
                result.failedArtifacts++; result.retainedBytes += imageBytes(dir);
                return result;
            }
            if (!f.id.equals(dir.getName()) || (f.state != State.COMPLETED && !hasImage(dir, f))) {
                result.failedArtifacts++; result.retainedBytes += imageBytes(dir);
                return result;
            }
                result.retainedBytes += imageBytes(dir); result.frames.add(f);
                if (f.state == State.PENDING) { result.pending++; result.pendingBytes += f.imageBytes; }
                if (f.state == State.COMPLETED) {
                    if (hasImage(dir, f)) result.archivedCount++; else result.cleanupPending++;
                }
        }
        return result;
    }

    CountSnapshot counts() throws IOException {
        if (uncertain && uncertainPublication == null) throw new IOException("frame store unavailable");
        return new CountSnapshot(pending.size(), failed.size(), 0, 0, archivedCount, retainedBytes, pendingBytes);
    }

    Frame claimNext() throws IOException {
        if (uncertain) throw new IOException("frame store unavailable");
        while (!pending.isEmpty()) {
            Frame f = pending.peekFirst();
            try {
                Frame persisted = readFrame(directory(f));
                persisted.keepScreenshot = f.keepScreenshot;
                if (!sameCapture(f, persisted) || persisted.state != State.PENDING)
                    throw new IOException("pending frame changed");
                if (!validImage(directory(f), persisted)) {
                    transition(f, State.PENDING, State.FAILED, "INVALID_IMAGE");
                    continue;
                }
                persisted.state = State.PROCESSING; persisted.attempts++;
                writeManifest(directory(f), persisted, true);
                pending.removeFirst(); pendingBytes -= f.imageBytes;
                return persisted;
            } catch (IOException | RuntimeException e) { uncertain = true; uncertainPublication = null; throw e; }
        }
        return null;
    }

    File image(Frame f) {
        if (!validImageName(f.imageFileName)) throw new IllegalArgumentException("invalid image name");
        return new File(directory(f), f.imageFileName);
    }

    File root() { return root; }

    void writeDiagnostic(Frame f, String name, byte[] bytes) throws IOException {
        if (uncertain) throw new IOException("frame store requires recovery");
        if (!(name.equals("ocr-lines.tsv") || name.equals("result.properties"))) throw new IOException("invalid diagnostic name");
        File dir = directory(f);
        Frame current = readFrame(dir);
        if (current.state != State.PROCESSING || !sameCapture(f, current)) throw new IOException("invalid diagnostic frame");
        File tmp = new File(dir, name + "-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes); out.flush(); out.getFD().sync();
            }
            ops.replace(tmp, new File(dir, name));
            sync.sync(dir);
        } catch (IOException | RuntimeException e) { uncertain = true; uncertainPublication = null; throw e; }
    }

    /** Reconcile an uncertain publication without disturbing an active OCR claim. */
    boolean reconcilePublication(Frame f) throws IOException {
        if (f.id == null) return false;
        if (uncertain && !f.id.equals(uncertainPublication))
            throw new IOException("frame store requires recovery");
        try {
            boolean reconciled = reconcilePublicationInternal(f);
            // Reconciliation only resolves this publication; sync its directories and root.
            File staged = f.id == null ? null : new File(root, f.id + ".staging");
            File published = f.id == null ? null : directory(f);
            if (staged != null && staged.isDirectory()) sync.sync(staged);
            if (published != null && published.isDirectory()) sync.sync(published);
            sync.sync(root);
            uncertain = false; uncertainPublication = null;
            if (reconciled) enqueuePublished(f);
            return reconciled;
        } catch (IOException | RuntimeException e) { uncertain = true; throw e; }
    }
    private boolean reconcilePublicationInternal(Frame f) throws IOException {
        if (f.id == null) return false;
        try {
            if (!UUID.fromString(f.id).toString().equals(f.id)) throw new IOException("invalid frame id");
        } catch (IllegalArgumentException e) { throw new IOException("invalid frame id", e); }
        File published = directory(f), staged = new File(root, f.id + ".staging");
        File tombstone = new File(root, f.id + ".completed");
        if (!published.exists() && !staged.exists() && !tombstone.exists()) return false;
        if ((published.exists() ? 1 : 0) + (staged.exists() ? 1 : 0) + (tombstone.exists() ? 1 : 0) != 1)
            throw new IOException("conflicting frame artifacts");
        if (tombstone.exists()) {
            if (!isCleanupTombstone(tombstone)) throw new IOException("invalid completion artifact");
            sync.sync(root);
            return true;
        }
        File candidate = published.exists() ? published : staged;
        if (!candidate.isDirectory()) throw new IOException("invalid frame artifact");
        Frame persisted = readFrame(candidate);
        if (!sameCapture(f, persisted)) throw new IOException("frame metadata mismatch");
        if (candidate.equals(staged)) {
            if (!publishStaging(staged, persisted)) throw new IOException("incomplete staging artifact");
        } else if (persisted.state != State.COMPLETED && !validImage(candidate, persisted)) {
            throw new IOException("invalid published image");
        }
        sync.sync(root);
        f.imageBytes = persisted.imageBytes; f.digest = persisted.digest;
        f.state = persisted.state; f.attempts = persisted.attempts; f.failureCode = persisted.failureCode;
        return true;
    }

    private boolean publishStaging(File dir, Frame f) throws IOException {
        File manifest = new File(dir, "manifest.properties");
        if (!manifest.exists()) {
            File image = new File(dir, f.imageFileName);
            if (!image.isFile() || image.length() <= 0 || f.state != State.PENDING) return false;
            f.imageBytes = image.length();
            f.digest = digestReader.digest(image);
            writeManifest(dir, f, false);
        } else if (!manifest.isFile() || !validImage(dir, f)) return false;
        ops.move(dir, new File(root, f.id));
        sync.sync(root);
        return true;
    }

    private static boolean sameCapture(Frame a, Frame b) {
        return a.id.equals(b.id) && a.enqueueSequence == b.enqueueSequence
                && java.util.Objects.equals(a.sessionId, b.sessionId)
                && java.util.Objects.equals(a.captureMethod, b.captureMethod)
                && a.captureSequence == b.captureSequence && a.captureWallMs == b.captureWallMs
                && a.captureUptimeMs == b.captureUptimeMs && a.windowId == b.windowId
                && a.width == b.width && a.height == b.height && a.contextCertain == b.contextCertain
                && java.util.Objects.equals(a.imageFileName, b.imageFileName) && a.imageHasAlpha == b.imageHasAlpha;
    }

    void fail(Frame f, String code) throws IOException {
        transition(f, State.PROCESSING, State.FAILED, code);
    }
    void returnPending(Frame f) throws IOException {
        transition(f, State.PROCESSING, State.PENDING, "");
    }
    void acknowledge(Frame f) throws IOException {
        transition(f, State.PROCESSING, State.COMPLETED, "");
    }
    void retryFailed() throws IOException {
        if (uncertain) throw new IOException("frame store unavailable");
        while (!failed.isEmpty()) transition(failed.peekFirst(), State.FAILED, State.PENDING, "");
    }

    /** Remove only a finalized, non-debug frame from this process. */
    void discardFinalized(Frame f) throws IOException {
        if (f.keepScreenshot) return;
        if (uncertain) throw new IOException("frame store unavailable");
        File dir = directory(f);
        Frame persisted = readFrame(dir);
        if (!sameCapture(f, persisted) || (persisted.state != State.COMPLETED && persisted.state != State.FAILED))
            throw new IOException("frame is not finalized");
        File[] files = children(dir);
        for (File file : files) {
            String name = file.getName();
            if (!isCleanupFile(file) && !(file.isFile() && (name.equals("ocr-lines.tsv") || name.equals("result.properties"))))
                throw new IOException("unknown frame artifact");
        }
        try {
            for (File file : files) if (!ops.delete(file)) throw new IOException("cannot delete frame artifact");
            sync.sync(dir);
            if (!ops.delete(dir)) throw new IOException("cannot delete frame directory");
            sync.sync(root);
        } catch (IOException | RuntimeException e) { uncertain = true; uncertainPublication = null; throw e; }
        retainedBytes -= f.imageBytes;
        if (persisted.state == State.COMPLETED) archivedCount--;
        else failed.removeIf(item -> item.id.equals(f.id));
    }

    /** Cleanup is safe only after the COMPLETED transition was durably committed. */
    boolean cleanup(Frame f) throws IOException {
        if (uncertain) throw new IOException("frame store requires recovery");
        try { return cleanupInternal(f); }
        catch (IOException | RuntimeException e) { uncertain = true; throw e; }
    }
    private boolean cleanupInternal(Frame f) throws IOException {
        File dir = directory(f);
        File tombstone = new File(root, f.id + ".completed");
        if (!dir.exists() && isCleanupTombstone(tombstone)) dir = tombstone;
        else {
            Frame current = readFrame(dir);
            if (current.state != State.COMPLETED) return false;
            // The name is a durable completion marker even if removing the manifest
            // succeeds but the final directory removal subsequently fails.
            ops.move(dir, tombstone);
            sync.sync(root);
            dir = tombstone;
        }
        for (File child : children(dir)) if (!isCleanupFile(child) || !ops.delete(child)) return false;
        sync.sync(dir);
        if (!ops.delete(dir)) return false;
        sync.sync(root);
        return true;
    }

    /** Resume interrupted cleanup without treating a tombstone as an OCR candidate. */
    void cleanupTombstones() throws IOException {
        if (uncertain) throw new IOException("frame store requires recovery");
        try { cleanupTombstonesInternal(); }
        catch (IOException | RuntimeException e) { uncertain = true; throw e; }
    }
    private void cleanupTombstonesInternal() throws IOException {
        for (File dir : children(root)) {
            if (!isCleanupTombstone(dir)) continue;
            boolean complete = true;
            for (File child : children(dir)) if (!isCleanupFile(child) || !ops.delete(child)) { complete = false; break; }
            if (!complete) continue;
            sync.sync(dir);
            if (ops.delete(dir)) sync.sync(root);
        }
    }

    private static boolean isCleanupTombstone(File dir) {
        String name = dir.getName();
        if (!dir.isDirectory() || !name.endsWith(".completed")) return false;
        try {
            String id = name.substring(0, name.length() - ".completed".length());
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException e) { return false; }
    }

    private static boolean isCleanupFile(File file) {
        String name = file.getName();
        if (!file.isFile()) return false;
        if (validImageName(name) || name.equals("initial.properties") || name.equals("manifest.properties")) return true;
        if (!name.startsWith("manifest-") || !name.endsWith(".tmp")) return false;
        try {
            String id = name.substring("manifest-".length(), name.length() - ".tmp".length());
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException e) { return false; }
    }

    private void transition(Frame f, State from, State to, String code) throws IOException {
        if (uncertain) throw new IOException("frame store requires recovery");
        Frame persisted = readFrame(directory(f));
        if (persisted.state != from) throw new IOException("invalid frame transition");
        persisted.keepScreenshot = f.keepScreenshot;
        persisted.state = to; persisted.failureCode = code;
        try { writeManifest(directory(f), persisted, true); }
        catch (IOException | RuntimeException e) { uncertain = true; uncertainPublication = null; throw e; }
        if (from == State.PENDING) { pending.removeFirst(); pendingBytes -= f.imageBytes; }
        if (from == State.FAILED) failed.removeFirst();
        if (to == State.FAILED) failed.addLast(persisted);
        if (to == State.PENDING) { if (from == State.PROCESSING) pending.addFirst(persisted); else pending.addLast(persisted); pendingBytes += persisted.imageBytes; }
        if (to == State.COMPLETED) archivedCount++;
        f.state = to; f.failureCode = code; f.attempts = persisted.attempts;
    }

    private File directory(Frame f) { return new File(root, f.id); }

    private void writeManifest(File dir, Frame f, boolean replace) throws IOException {
        File target = new File(dir, "manifest.properties");
        File tmp = new File(dir, "manifest-" + UUID.randomUUID() + ".tmp");
        writeProperties(tmp, properties(f));
        // renameTo replaces atomically on Android/Linux; never delete the old manifest first.
        if (replace) {
            ops.replace(tmp, target);
        } else ops.move(tmp, target);
        sync.sync(dir);
    }

    private static void writeProperties(File file, Properties p) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(out, "frame metadata"); out.flush(); out.getFD().sync();
        }
    }
    private static Properties properties(Frame f) {
        Properties p = new Properties();
        p.setProperty("schemaVersion", "1"); p.setProperty("frameId", f.id);
        p.setProperty("enqueueSequence", Long.toString(f.enqueueSequence));
        p.setProperty("captureSessionId", f.sessionId); p.setProperty("captureSequence", Long.toString(f.captureSequence));
        p.setProperty("captureWallMs", Long.toString(f.captureWallMs)); p.setProperty("captureUptimeMs", Long.toString(f.captureUptimeMs));
        p.setProperty("windowId", Integer.toString(f.windowId)); p.setProperty("captureMethod", f.captureMethod);
        p.setProperty("width", Integer.toString(f.width)); p.setProperty("height", Integer.toString(f.height));
        p.setProperty("imageBytes", Long.toString(f.imageBytes)); p.setProperty("digest", f.digest == null ? "" : f.digest);
        p.setProperty("state", f.state.name()); p.setProperty("attempts", Integer.toString(f.attempts));
        p.setProperty("contextCertain", Boolean.toString(f.contextCertain));
        p.setProperty("imageFileName", f.imageFileName); p.setProperty("imageHasAlpha", Boolean.toString(f.imageHasAlpha));
        p.setProperty("failureCode", f.failureCode); return p;
    }
    private Frame readFrame(File dir) throws IOException {
        File file = new File(dir, "manifest.properties");
        if (!file.isFile()) file = new File(dir, "initial.properties");
        Properties p = new Properties();
        if (observer != null) observer.read(file);
        try (FileInputStream in = new FileInputStream(file)) { p.load(in); }
        try {
            if (!"1".equals(p.getProperty("schemaVersion"))) throw new IOException("unknown schema");
            Frame f = new Frame(); f.id = UUID.fromString(p.getProperty("frameId")).toString();
            f.sessionId = UUID.fromString(p.getProperty("captureSessionId")).toString();
            f.enqueueSequence = Long.parseLong(p.getProperty("enqueueSequence"));
            f.captureSequence = Long.parseLong(p.getProperty("captureSequence"));
            f.captureWallMs = Long.parseLong(p.getProperty("captureWallMs")); f.captureUptimeMs = Long.parseLong(p.getProperty("captureUptimeMs"));
            f.windowId = Integer.parseInt(p.getProperty("windowId")); f.captureMethod = p.getProperty("captureMethod");
            f.width = Integer.parseInt(p.getProperty("width")); f.height = Integer.parseInt(p.getProperty("height"));
            f.imageBytes = Long.parseLong(p.getProperty("imageBytes")); f.digest = p.getProperty("digest");
            f.state = State.valueOf(p.getProperty("state")); f.attempts = Integer.parseInt(p.getProperty("attempts"));
            f.failureCode = p.getProperty("failureCode", "");
            String contextCertain = p.getProperty("contextCertain", "true");
            if (!contextCertain.equals("true") && !contextCertain.equals("false")) throw new IOException("invalid context certainty");
            f.contextCertain = Boolean.parseBoolean(contextCertain);
            f.imageFileName = p.getProperty("imageFileName", "image.png");
            if (!validImageName(f.imageFileName)) throw new IOException("invalid image name");
            String alpha = p.getProperty("imageHasAlpha", "true");
            if (!alpha.equals("true") && !alpha.equals("false")) throw new IOException("invalid image alpha");
            f.imageHasAlpha = Boolean.parseBoolean(alpha);
            if (f.enqueueSequence <= 0 || f.captureSequence <= 0 || f.width <= 0 || f.height <= 0 || f.captureMethod == null) throw new IOException("invalid metadata");
            return f;
        } catch (IllegalArgumentException | NullPointerException e) { throw new IOException("invalid metadata", e); }
    }
    private static boolean hasImage(File dir, Frame f) {
        File image = new File(dir, f.imageFileName);
        return image.isFile() && image.length() == f.imageBytes && f.imageBytes > 0
                && f.digest != null && f.digest.matches("[0-9a-f]{64}");
    }
    private boolean validImage(File dir, Frame f) throws IOException {
        return hasImage(dir, f) && digestReader.digest(new File(dir, f.imageFileName)).equals(f.digest);
    }
    private static boolean validImageName(String name) { return "image.png".equals(name) || "image.webp".equals(name); }
    private static String digestImage(File image) throws IOException {
        MessageDigest digest = sha256();
        try (DigestInputStream in = new DigestInputStream(new BufferedInputStream(new FileInputStream(image)), digest)) {
            byte[] buffer = new byte[8192]; while (in.read(buffer) != -1) { }
        }
        return hex(digest.digest());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return s.toString();
    }
    private File[] children(File dir) throws IOException {
        if (observer != null) observer.list(dir);
        File[] files = dir.listFiles(); if (files == null) throw new IOException("cannot list directory"); return files;
    }
    private long imageBytes(File dir) throws IOException {
        if (!dir.isDirectory()) return dir.isFile() ? dir.length() : 0;
        File[] children = children(dir);
        long bytes = 0;
        for (File child : children) bytes += imageBytes(child);
        return bytes;
    }
}
