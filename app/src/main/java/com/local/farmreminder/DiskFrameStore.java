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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeSet;

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
        String id, sessionId, captureMethod, digest, failureCode = "", imageFileName = "image.png";
        long enqueueSequence, captureSequence, captureWallMs, captureUptimeMs, imageBytes;
        int windowId, width, height, attempts;
        boolean contextCertain = true, imageHasAlpha = true;
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

    // One serial owner per root; external edits require an explicit authoritative scan.
    private static final class Entry {
        final String name, pendingId;
        final long sequence, retained, pendingBytes;
        final int pending, failedFrames, failedArtifacts, cleanupPending, archived;
        Entry(String name, Snapshot s) {
            this.name = name; retained = s.retainedBytes; pendingBytes = s.pendingBytes;
            pending = s.pending; failedArtifacts = s.failedArtifacts;
            cleanupPending = s.cleanupPending; archived = s.archivedCount;
            Frame f = s.frames.isEmpty() ? null : s.frames.get(0);
            failedFrames = f != null && f.state == State.FAILED ? 1 : 0;
            pendingId = f != null && f.state == State.PENDING ? f.id : null;
            sequence = pendingId == null ? 0 : f.enqueueSequence;
        }
    }
    interface ReadObserver { void read(File path); void list(File path); }
    private ReadObserver observer;
    void setReadObserver(ReadObserver observer) { this.observer = observer; }
    private final Map<String, Entry> entries = new HashMap<>();
    private final TreeSet<Entry> pendingIndex = new TreeSet<>(Comparator
            .comparingLong((Entry e) -> e.sequence).thenComparing(e -> e.pendingId));
    private int cachedPending, cachedFailedFrames, cachedFailedArtifacts, cachedCleanup, cachedArchived;
    private long cachedRetained, cachedPendingBytes;
    private boolean cacheReady, uncertain;
    private final Set<String> uncertainPublications = new HashSet<>();

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
        if (uncertain && uncertainPublications.isEmpty()) throw new IOException("frame store requires recovery");
        if (f.sessionId == null || f.captureMethod == null || f.width <= 0 || f.height <= 0 || !validImageName(f.imageFileName))
            throw new IOException("invalid capture metadata");
        try {
            Frame result = publishInternal(f, writer);
            refresh(new File(root, "sequence.properties"));
            refresh(directory(result));
            return result;
        } catch (IOException | RuntimeException e) { uncertain = true; if (f.id != null) uncertainPublications.add(f.id); cacheReady = false; throw e; }
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

    /** Startup recovery is explicit and must run once, never while a claim is active. */
    void recover() throws IOException {
        cacheReady = false;
        // A failed full recovery cannot be resolved by reconciling just one publication.
        uncertainPublications.clear();
        try {
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
        // Explicit recovery re-establishes directory durability before clearing uncertainty.
        for (File dir : children(root)) if (dir.isDirectory()) sync.sync(dir);
        sync.sync(root);
        uncertain = false; uncertainPublications.clear();
        } catch (IOException | RuntimeException e) { uncertain = true; throw e; }
    }

    Snapshot scan() throws IOException {
        cacheReady = false;
        Snapshot result = new Snapshot();
        Map<String, Entry> fresh = new HashMap<>();
        for (File dir : children(root)) {
            Snapshot part = scanEntry(dir);
            fresh.put(dir.getName(), new Entry(dir.getName(), part));
            result.frames.addAll(part.frames);
            result.pending += part.pending; result.pendingBytes += part.pendingBytes;
            result.failedArtifacts += part.failedArtifacts; result.cleanupPending += part.cleanupPending;
            result.archivedCount += part.archivedCount; result.retainedBytes += part.retainedBytes;
        }
        result.frames.sort(Comparator.comparingLong((Frame f) -> f.enqueueSequence).thenComparing(f -> f.id));
        if (!uncertain) {
            entries.clear(); pendingIndex.clear();
            cachedPending = cachedFailedFrames = cachedFailedArtifacts = cachedCleanup = cachedArchived = 0;
            cachedRetained = cachedPendingBytes = 0;
            for (Entry e : fresh.values()) addEntry(e, 1);
            cacheReady = true;
        }
        return result;
    }

    private Snapshot scanEntry(File dir) throws IOException {
        return scanEntry(dir, false);
    }
    private Snapshot scanEntry(File dir, boolean strict) throws IOException {
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
                if (strict) throw new IOException("cannot refresh touched frame", e);
                result.failedArtifacts++; result.retainedBytes += imageBytes(dir);
                return result;
            }
            if (!f.id.equals(dir.getName()) || (f.state != State.COMPLETED && !hasImage(dir, f))) {
                result.failedArtifacts++; result.retainedBytes += imageBytes(dir);
                return result;
            }
                result.retainedBytes += imageBytes(dir); result.frames.add(f);
                if (recoveryRequired.contains(f.id)) result.failedArtifacts++;
                if (f.state == State.PENDING) { result.pending++; result.pendingBytes += f.imageBytes; }
                if (f.state == State.COMPLETED) {
                    if (hasImage(dir, f)) result.archivedCount++; else result.cleanupPending++;
                }
        }
        return result;
    }

    private void addEntry(Entry e, int sign) {
        if (sign > 0) { entries.put(e.name, e); if (e.pendingId != null) pendingIndex.add(e); }
        else { entries.remove(e.name); if (e.pendingId != null) pendingIndex.remove(e); }
        cachedPending += sign * e.pending; cachedFailedFrames += sign * e.failedFrames;
        cachedFailedArtifacts += sign * e.failedArtifacts; cachedCleanup += sign * e.cleanupPending;
        cachedArchived += sign * e.archived; cachedRetained += sign * e.retained;
        cachedPendingBytes += sign * e.pendingBytes;
    }
    private void refresh(File path) throws IOException {
        if (!cacheReady) return;
        // A touched frame was just written: do not convert a failed refresh into a guessed count.
        Entry replacement = path.exists() ? new Entry(path.getName(), scanEntry(path, true)) : null;
        Entry previous = entries.get(path.getName());
        if (previous != null) addEntry(previous, -1);
        if (replacement != null) addEntry(replacement, 1);
    }
    CountSnapshot counts() throws IOException {
        if (uncertain) throw new IOException("frame store requires recovery");
        if (!cacheReady) scan();
        return new CountSnapshot(cachedPending, cachedFailedFrames, cachedFailedArtifacts,
                cachedCleanup, cachedArchived, cachedRetained, cachedPendingBytes);
    }

    Frame claimNext() throws IOException {
        counts();
        // Bound corrupt work per invocation; fail instead of reporting an empty queue.
        for (int examined = 0; examined < 64 && !pendingIndex.isEmpty(); examined++) {
            Entry candidate = pendingIndex.first();
            Frame f;
            try { f = readFrame(new File(root, candidate.name)); }
            catch (IOException | RuntimeException e) { uncertain = true; cacheReady = false; throw e; }
            if (!candidate.pendingId.equals(f.id) || f.enqueueSequence != candidate.sequence || f.state != State.PENDING) {
                cacheReady = false;
                throw new IOException("pending index changed; scan required");
            }
            boolean valid;
            try { valid = validImage(directory(f), f); }
            catch (IOException | RuntimeException e) { uncertain = true; cacheReady = false; throw e; }
            if (!valid) {
                transition(f, State.PENDING, State.FAILED, "INVALID_IMAGE");
                continue;
            }
            f.state = State.PROCESSING; f.attempts++;
            try { writeManifest(directory(f), f, true); refresh(directory(f)); }
            catch (IOException | RuntimeException e) { uncertain = true; cacheReady = false; throw e; }
            return f;
        }
        if (!pendingIndex.isEmpty()) throw new IOException("invalid pending batch limit reached");
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
            refresh(dir);
        } catch (IOException | RuntimeException e) { uncertain = true; cacheReady = false; throw e; }
    }

    /** Reconcile an uncertain publication without disturbing an active OCR claim. */
    boolean reconcilePublication(Frame f) throws IOException {
        if (f.id == null) return false;
        cacheReady = false;
        if (uncertain && !uncertainPublications.contains(f.id))
            throw new IOException("frame store requires recovery");
        try {
            boolean reconciled = reconcilePublicationInternal(f);
            // Reconciliation only resolves this publication; sync its directories and root.
            File staged = f.id == null ? null : new File(root, f.id + ".staging");
            File published = f.id == null ? null : directory(f);
            if (staged != null && staged.isDirectory()) sync.sync(staged);
            if (published != null && published.isDirectory()) sync.sync(published);
            sync.sync(root);
            uncertainPublications.remove(f.id);
            uncertain = !uncertainPublications.isEmpty();
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
        cacheReady = false;
        for (Frame f : scan().frames) if (f.state == State.FAILED)
            transition(f, State.FAILED, State.PENDING, "");
    }

    /** Cleanup is safe only after the COMPLETED transition was durably committed. */
    boolean cleanup(Frame f) throws IOException {
        if (uncertain) throw new IOException("frame store requires recovery");
        cacheReady = false;
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
        cacheReady = false;
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
        persisted.state = to; persisted.failureCode = code;
        try { writeManifest(directory(f), persisted, true); refresh(directory(f)); }
        catch (IOException | RuntimeException e) { uncertain = true; cacheReady = false; throw e; }
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
