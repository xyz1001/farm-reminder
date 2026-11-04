package com.local.farmreminder;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MonitorIntentCommitTest {
    private static final class Fake implements MonitorIntentCommit.Transaction {
        byte[] published = {1}, staged; boolean syncFails, retainOld, readFails, finishFails;
        int syncs, finishes, rollbacks, reads;
        String steps = "";
        public void start() { steps += "start "; }
        public void write(byte[] bytes) { steps += "write "; staged = bytes.clone(); }
        public void sync() throws IOException { steps += "sync "; syncs++; if (syncFails) throw new IOException(); }
        public void finish() throws IOException { steps += "finish "; finishes++; if (finishFails) throw new IOException(); if (!retainOld) published = staged.clone(); }
        public void rollback() { rollbacks++; staged = null; }
        public byte[] readPublished() throws IOException { steps += "read"; reads++; if (readFails) throw new IOException(); return published.clone(); }
    }
    @Test public void successfulCommitSyncsBeforePublishingAndChecksExactBytes() throws Exception {
        Fake fake = new Fake(); MonitorIntentCommit.commit(fake, new byte[]{2, 3});
        assertEquals(1, fake.syncs); assertEquals(1, fake.finishes); assertEquals(1, fake.reads);
        assertEquals("start write sync finish read", fake.steps);
        assertEquals(0, fake.rollbacks); assertArrayEquals(new byte[]{2, 3}, fake.published);
    }
    @Test public void syncFailureRollsBackAndKeepsOldBytes() {
        Fake fake = new Fake(); fake.syncFails = true;
        try { MonitorIntentCommit.commit(fake, new byte[]{2}); fail(); } catch (IOException expected) { }
        assertEquals(1, fake.syncs); assertEquals(0, fake.finishes); assertEquals(1, fake.rollbacks);
        assertArrayEquals(new byte[]{1}, fake.published);
    }
    @Test public void silentPublishFailureAndUnreadableReadbackFailWithoutRollback() {
        Fake fake = new Fake(); fake.retainOld = true;
        try { MonitorIntentCommit.commit(fake, new byte[]{2}); fail(); } catch (IOException expected) { }
        assertEquals(1, fake.syncs); assertEquals(1, fake.finishes); assertEquals(0, fake.rollbacks); assertArrayEquals(new byte[]{1}, fake.published);
        Fake unreadable = new Fake(); unreadable.readFails = true;
        try { MonitorIntentCommit.commit(unreadable, new byte[]{2}); fail(); } catch (IOException expected) { }
        assertEquals(1, unreadable.syncs); assertEquals(1, unreadable.finishes); assertEquals(0, unreadable.rollbacks);
        assertArrayEquals(new byte[]{2}, unreadable.published); // Post-publish outcome is uncertain, never undo it here.
    }
    @Test public void finishThrowNeverRollsBackPossiblyPublishedRecord() {
        Fake fake = new Fake(); fake.finishFails = true;
        try { MonitorIntentCommit.commit(fake, new byte[]{2}); fail(); } catch (IOException expected) { }
        assertEquals(0, fake.rollbacks); assertEquals(0, fake.reads);
    }
    @Test public void repositorySurfacesFailedVerificationWithoutAcceptingStop() {
        Fake fake = new Fake();
        MonitorIntentRepository.Storage storage = new MonitorIntentRepository.Storage() {
            public byte[] read() { return fake.published.clone(); }
            public void write(byte[] bytes) throws IOException { MonitorIntentCommit.commit(fake, bytes); }
        };
        MonitorIntentRepository repo = new MonitorIntentRepository(storage);
        try { fake.published = MonitorIntentCodec.encode(MonitorIntentState.empty().start(MonitorIntentState.USAGE, java.util.Collections.emptyList(), 10)); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
        byte[] old = fake.published.clone(); fake.retainOld = true;
        MonitorIntentRepository.Result result = repo.stop();
        assertEquals(MonitorIntentRepository.Code.STORAGE_ERROR, result.code); assertTrue(result.state.wanted);
        assertTrue(Arrays.equals(old, fake.published)); assertTrue(repo.read().state.wanted);
    }
}
