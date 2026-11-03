package com.local.farmreminder;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class BoundedProcessQueryTest {
    private static class FakeProcess extends Process {
        final InputStream out, err;
        final int exit;
        FakeProcess(byte[] out, byte[] err, int exit) {
            this.out = new ByteArrayInputStream(out); this.err = new ByteArrayInputStream(err); this.exit = exit;
        }
        FakeProcess(InputStream out) { this.out = out; this.err = new ByteArrayInputStream(new byte[0]); exit = 0; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return out; }
        @Override public InputStream getErrorStream() { return err; }
        @Override public int waitFor() { return exit; }
        @Override public boolean waitFor(long time, TimeUnit unit) throws InterruptedException { return true; }
        @Override public int exitValue() { return exit; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
    private static BoundedProcessQuery runner(byte[] out, byte[] err, int exit) {
        return new BoundedProcessQuery(() -> new FakeProcess(out, err, exit), 100, 200);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test public void acceptsCompleteOutputWithoutFinalNewline() {
        assertEquals("complete", runner(bytes("complete"), bytes(""), 0).query());
    }
    @Test public void rejectsInvalidUtf8NonzeroAndOversize() {
        assertNull(runner(new byte[]{(byte) 0xc3}, bytes(""), 0).query());
        assertNull(runner(bytes("ok"), new byte[]{(byte) 0xff}, 0).query());
        assertNull(runner(bytes("ok"), bytes(""), 1).query());
        assertNull(runner(new byte[1024 * 1024 + 1], bytes(""), 0).query());
    }
    @Test public void blockedDrainFailsClosedAfterTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        InputStream blocked = new InputStream() {
            @Override public int read() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
                return -1;
            }
            @Override public void close() { }
        };
        AtomicInteger starts = new AtomicInteger();
        BoundedProcessQuery runner = new BoundedProcessQuery(() -> {
            starts.incrementAndGet();
            return new FakeProcess(blocked) {
                @Override public boolean waitFor(long time, TimeUnit unit) { return false; }
            };
        }, 50, 50);
        try {
            assertNull(runner.query());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertNull(runner.query());
            assertEquals(1, starts.get());
        } finally { release.countDown(); }
    }
    @Test public void destroyDuringQueryRejectsOutput() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        BoundedProcessQuery runner = new BoundedProcessQuery(() -> new FakeProcess(bytes("match"), bytes(""), 0) {
            @Override public boolean waitFor(long time, TimeUnit unit) throws InterruptedException {
                waiting.countDown(); return release.await(time, unit);
            }
            @Override public void destroy() { release.countDown(); }
        }, 1000, 200);
        AtomicReference<String> result = new AtomicReference<>();
        Thread query = new Thread(() -> result.set(runner.query()));
        query.start(); assertTrue(waiting.await(1, TimeUnit.SECONDS));
        runner.destroy(); query.join(2000);
        assertFalse(query.isAlive()); assertNull(result.get()); assertNull(runner.query());
    }
}
