package com.local.farmreminder;

import java.io.BufferedInputStream;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Serial-executor-confined durable frame repository. It does not start capture or OCR.
 * Frames are retained after completion; explicit cleanup is available for legacy callers.
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
        String id, sessionId, captureMethod, digest, failureCode = "";
        long enqueueSequence, captureSequence, captureWallMs, captureUptimeMs, imageBytes;
        int windowId, width, height, attempts;
        boolean contextCertain = true;
        State state = State.PENDING;
    }
    static final class Snapshot {
        final List<Frame> frames = new ArrayList<>();
        int failedArtifacts, cleanupPending, archivedCount;
        long retainedBytes, pendingBytes;
        int pending;
    }

    private final File root;
    private final DirectorySync sync;
    private final FileOps ops;
    private final DigestReader digestReader;
    private final Set<String> recoveryRequired = new HashSet<>();
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
        // Scan sequence even for staged or completed records; never reuse an observed number.
        for (File file : children(root)) {
            try { nextSequence = Math.max(nextSequence, readFrame(file).enqueueSequence); }
            catch (IOException | RuntimeException ignored) { /* Preserve unknown artifacts. */ }
        }
    }

    Frame publish(Frame f, ImageWriter writer) throws IOException {
        if (f.sessionId == null || f.captureMethod == null || f.width <= 0 || f.height <= 0)
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
             DigestOutputStream output = new DigestOutputStream(raw, digest)) {
            if (!writer.write(output)) throw new IOException("image encoding failed");
            output.flush();
            raw.getFD().sync();
        }
        f.imageBytes = tmp.length();
        if (f.imageBytes <= 0) throw new IOException("empty image");
        f.digest = hex(digest.digest());
        ops.move(tmp, new File(staging, "image.png"));
        sync.sync(staging);
        writeManifest(staging, f, false);
        File published = new File(root, f.id);
        ops.move(staging, published);
        sync.sync(root);
        return f;
    }

    /** Startup recovery is explicit and must run once, never while a claim is active. */
    void recover() throws IOException {
        for (File dir : children(root)) {
            if (!dir.isDirectory()) continue;
            if (dir.getName().endsWith(".staging")) {
                Frame f;
                try {
                    f = readFrame(dir);
                } catch (IOException | RuntimeException ignored) { continue; }
                if (!(f.id + ".staging").equals(dir.getName())) continue;
                publishStaging(dir, f);
            } else {
                Frame f;
                try {
                    f = readFrame(dir);
                } catch (IOException | RuntimeException ignored) { continue; }
                if (!f.id.equals(dir.getName())) continue;
                if (f.state == State.PROCESSING) {
                    recoveryRequired.add(f.id);
                    f.state = State.FAILED; f.failureCode = "INTERRUPTED_PROCESS";
                    writeManifest(dir, f, true);
                }
                recoveryRequired.remove(f.id);
            }
        }
    }

    Snapshot scan() throws IOException {
        Snapshot result = new Snapshot();
        for (File dir : children(root)) {
            if (dir.getName().equals("sequence.properties")) { result.retainedBytes += dir.length(); continue; }
            if (isCleanupTombstone(dir)) {
                result.cleanupPending++;
                result.retainedBytes += imageBytes(dir);
                for (File child : children(dir)) if (!isCleanupFile(child)) { result.failedArtifacts++; break; }
                continue;
            }
            if (!dir.isDirectory() || dir.getName().endsWith(".staging")) {
                result.failedArtifacts++; result.retainedBytes += imageBytes(dir); continue;
            }
            try {
                Frame f = readFrame(dir);
                if (!f.id.equals(dir.getName()) || (f.state != State.COMPLETED && !hasImage(dir, f))) throw new IOException("invalid frame");
                result.frames.add(f); result.retainedBytes += imageBytes(dir);
                if (recoveryRequired.contains(f.id)) result.failedArtifacts++;
                if (f.state == State.PENDING) { result.pending++; result.pendingBytes += f.imageBytes; }
                if (f.state == State.COMPLETED) {
                    if (hasImage(dir, f)) result.archivedCount++; else result.cleanupPending++;
                }
            } catch (IOException | RuntimeException e) {
                result.failedArtifacts++; result.retainedBytes += imageBytes(dir);
            }
        }
        result.frames.sort(Comparator.comparingLong((Frame f) -> f.enqueueSequence).thenComparing(f -> f.id));
        return result;
    }

    Frame claimNext() throws IOException {
        for (Frame f : scan().frames) if (f.state == State.PENDING) {
            if (!validImage(directory(f), f)) {
                transition(f, State.PENDING, State.FAILED, "INVALID_IMAGE");
                continue;
            }
            f.state = State.PROCESSING; f.attempts++;
            writeManifest(directory(f), f, true);
            return f;
        }
        return null;
    }

    File image(Frame f) { return new File(directory(f), "image.png"); }

    File root() { return root; }

    void writeDiagnostic(Frame f, String name, byte[] bytes) throws IOException {
        if (!(name.equals("ocr-lines.tsv") || name.equals("result.properties"))) throw new IOException("invalid diagnostic name");
        File dir = directory(f);
        Frame current = readFrame(dir);
        if (current.state != State.PROCESSING || !sameCapture(f, current)) throw new IOException("invalid diagnostic frame");
        File tmp = new File(dir, name + "-" + UUID.randomUUID() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
        ops.replace(tmp, new File(dir, name));
        sync.sync(dir);
    }

    /** Reconcile an uncertain publication without disturbing an active OCR claim. */
    boolean reconcilePublication(Frame f) throws IOException {
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
            File image = new File(dir, "image.png");
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
                && a.width == b.width && a.height == b.height && a.contextCertain == b.contextCertain;
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
        for (Frame f : scan().frames) if (f.state == State.FAILED)
            transition(f, State.FAILED, State.PENDING, "");
    }

    /** Cleanup is safe only after the COMPLETED transition was durably committed. */
    boolean cleanup(Frame f) throws IOException {
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
        if (name.equals("image.png") || name.equals("initial.properties") || name.equals("manifest.properties")) return true;
        if (!name.startsWith("manifest-") || !name.endsWith(".tmp")) return false;
        try {
            String id = name.substring("manifest-".length(), name.length() - ".tmp".length());
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException e) { return false; }
    }

    private void transition(Frame f, State from, State to, String code) throws IOException {
        Frame persisted = readFrame(directory(f));
        if (persisted.state != from) throw new IOException("invalid frame transition");
        persisted.state = to; persisted.failureCode = code;
        writeManifest(directory(f), persisted, true);
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
        p.setProperty("failureCode", f.failureCode); return p;
    }
    private static Frame readFrame(File dir) throws IOException {
        File file = new File(dir, "manifest.properties");
        if (!file.isFile()) file = new File(dir, "initial.properties");
        Properties p = new Properties();
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
            if (f.enqueueSequence <= 0 || f.captureSequence <= 0 || f.width <= 0 || f.height <= 0 || f.captureMethod == null) throw new IOException("invalid metadata");
            return f;
        } catch (IllegalArgumentException | NullPointerException e) { throw new IOException("invalid metadata", e); }
    }
    private static boolean hasImage(File dir, Frame f) {
        File image = new File(dir, "image.png");
        return image.isFile() && image.length() == f.imageBytes && f.imageBytes > 0
                && f.digest != null && f.digest.matches("[0-9a-f]{64}");
    }
    private boolean validImage(File dir, Frame f) throws IOException {
        return hasImage(dir, f) && digestReader.digest(new File(dir, "image.png")).equals(f.digest);
    }
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
    private static File[] children(File dir) throws IOException {
        File[] files = dir.listFiles(); if (files == null) throw new IOException("cannot list directory"); return files;
    }
    private static long imageBytes(File dir) {
        if (!dir.isDirectory()) return dir.isFile() ? dir.length() : 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        long bytes = 0;
        for (File child : children) bytes += imageBytes(child);
        return bytes;
    }
}
