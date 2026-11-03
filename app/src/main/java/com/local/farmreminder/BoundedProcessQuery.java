package com.local.farmreminder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns one process and its drains. Incomplete cleanup permanently fails closed. */
final class BoundedProcessQuery {
    interface Starter { java.lang.Process start() throws Exception; }
    private static final int LIMIT = 1024 * 1024;
    private final Starter starter;
    private final long timeoutMs, cleanupMs;
    private boolean busy, destroyed;
    private java.lang.Process child;

    BoundedProcessQuery(Starter starter) { this(starter, 2000, 500); }
    BoundedProcessQuery(Starter starter, long timeoutMs, long cleanupMs) {
        this.starter = starter; this.timeoutMs = timeoutMs; this.cleanupMs = cleanupMs;
    }
    synchronized boolean isDestroyed() { return destroyed; }

    String query() {
        synchronized (this) {
            if (busy || destroyed) return null;
            busy = true;
        }
        java.lang.Process p = null;
        Thread outThread = null, errThread = null;
        String value = null;
        try {
            p = starter.start();
            synchronized (this) { child = p; if (destroyed) return null; }
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            AtomicInteger count = new AtomicInteger(); AtomicBoolean bad = new AtomicBoolean();
            outThread = drain(p.getInputStream(), out, count, bad);
            errThread = drain(p.getErrorStream(), err, count, bad);
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return null;
            outThread.join(cleanupMs); errThread.join(cleanupMs);
            if (outThread.isAlive() || errThread.isAlive() || bad.get() || p.exitValue() != 0) return null;
            value = decode(out.toByteArray());
            decode(err.toByteArray());
        } catch (Exception ignored) {
            value = null;
        } finally {
            boolean clean = p == null || cleanup(p, outThread, errThread);
            synchronized (this) {
                if (clean) { child = null; busy = false; }
                if (!clean || destroyed) value = null;
                notifyAll();
            }
        }
        return value;
    }

    private static String decode(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output,
                                AtomicInteger count, AtomicBoolean bad) {
        Thread thread = new Thread(() -> {
            try {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (count.addAndGet(n) > LIMIT) { bad.set(true); return; }
                    output.write(buffer, 0, n);
                }
            } catch (Exception ignored) { bad.set(true); }
        }, "task-dump-drain");
        thread.setDaemon(true); thread.start(); return thread;
    }

    private boolean cleanup(java.lang.Process p, Thread out, Thread err) {
        AtomicBoolean complete = new AtomicBoolean();
        Thread cleaner = new Thread(() -> {
            try {
                p.destroyForcibly();
                p.getOutputStream().close();
                p.getInputStream().close();
                p.getErrorStream().close();
                if (!p.waitFor(cleanupMs, TimeUnit.MILLISECONDS)) return;
                if (out != null) out.join(cleanupMs);
                if (err != null) err.join(cleanupMs);
                complete.set((out == null || !out.isAlive()) && (err == null || !err.isAlive()));
            } catch (Exception ignored) { }
        }, "task-dump-cleanup");
        cleaner.setDaemon(true); cleaner.start();
        try { cleaner.join(cleanupMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return !cleaner.isAlive() && complete.get();
    }

    void destroy() {
        java.lang.Process p;
        synchronized (this) { destroyed = true; p = child; }
        if (p != null) {
            Thread signal = new Thread(() -> { try { p.destroyForcibly(); } catch (Exception ignored) { } }, "task-dump-stop");
            signal.setDaemon(true); signal.start();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs + 3 * cleanupMs);
        synchronized (this) {
            while (busy) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(this, left); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
}
