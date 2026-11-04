package com.local.farmreminder;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MonitorIntentRepositoryTest {
    private static final class Memory implements MonitorIntentRepository.Storage {
        byte[] bytes; boolean failWrite, failRead;
        public byte[] read() throws IOException { if (failRead) throw new IOException(); return bytes == null ? null : bytes.clone(); }
        public void write(byte[] value) throws IOException { if (failWrite) throw new IOException(); bytes = value.clone(); }
    }
    private static MonitorIntentState.Exit exit(long time, int pid, int reason) {
        return new MonitorIntentState.Exit(time, pid, reason, "com.local.farmreminder");
    }
    private static MonitorExitHistory ok(MonitorIntentState.Exit... entries) { return MonitorExitHistory.ok(Arrays.asList(entries)); }
    @Test public void missingIsNotEnrolledAndStartEnrollsWithModeAndHistory() {
        MonitorIntentRepository repo = new MonitorIntentRepository(new Memory());
        MonitorIntentRepository.Result initial = repo.read();
        assertEquals(MonitorIntentRepository.Code.MISSING, initial.code); assertFalse(initial.state.enrolled); assertFalse(initial.state.wanted);
        MonitorIntentState.Exit existing = exit(300, 10, 10);
        MonitorIntentRepository.Result started = repo.start(MonitorIntentState.SHIZUKU, ok(existing), 500);
        assertEquals(MonitorIntentRepository.Code.OK, started.code); assertTrue(started.state.enrolled); assertTrue(started.state.wanted);
        assertEquals(1, started.state.revision); assertEquals(500, started.state.manualStartWall);
        assertEquals(MonitorIntentState.SHIZUKU, started.state.sourceMode); assertEquals(Collections.singletonList(existing), repo.read().state.acknowledged);
        assertTrue(started.state.mayRun(1));
    }
    @Test public void corruptedAndUnknownSchemaNeverBecomeEmptyRecord() throws Exception {
        Memory memory = new Memory(); MonitorIntentRepository repo = new MonitorIntentRepository(memory);
        memory.bytes = new byte[]{1, 2}; assertEquals(MonitorIntentRepository.Code.CORRUPT, repo.read().code);
        assertEquals(MonitorIntentRepository.Code.CORRUPT, repo.start(MonitorIntentState.USAGE, ok(), 1).code);
        memory.bytes = MonitorIntentCodec.encode(MonitorIntentState.empty()); memory.bytes[7] = 2;
        assertEquals(MonitorIntentRepository.Code.CORRUPT, repo.read().code);
        assertNull(repo.read().state);
    }
    @Test public void failedAtomicCommitReturnsPreviousStateNotProposed() {
        Memory memory = new Memory(); MonitorIntentRepository repo = new MonitorIntentRepository(memory);
        repo.start(MonitorIntentState.USAGE, ok(), 10); byte[] old = memory.bytes.clone(); memory.failWrite = true;
        MonitorIntentRepository.Result stopped = repo.stop();
        assertEquals(MonitorIntentRepository.Code.STORAGE_ERROR, stopped.code); assertTrue(stopped.state.wanted);
        assertArrayEquals(old, memory.bytes); assertTrue(repo.read().state.wanted);
        memory.failRead = true; assertEquals(MonitorIntentRepository.Code.STORAGE_ERROR, repo.read().code);
    }
    @Test public void stopBeatsQueuedRevisionAndOldFatalCannotAffectNewStart() {
        MonitorIntentRepository repo = new MonitorIntentRepository(new Memory());
        long oldRevision = repo.start(MonitorIntentState.USAGE, ok(), 100).state.revision;
        MonitorIntentState stopped = repo.stop().state;
        assertFalse(stopped.mayRun(oldRevision)); assertFalse(stopped.wanted);
        MonitorIntentState newer = repo.start(MonitorIntentState.SHIZUKU, ok(), 101).state;
        assertEquals(MonitorIntentRepository.Code.STALE, repo.suspendFatal(oldRevision).code);
        assertTrue(repo.read().state.mayRun(newer.revision));
        MonitorIntentState fatal = repo.suspendFatal(newer.revision).state;
        assertTrue(fatal.wanted); assertEquals(MonitorIntentState.Suspension.MANUAL_RETRY_REQUIRED, fatal.suspension);
        assertFalse(fatal.mayRun(fatal.revision));
    }
    @Test public void manualStartAcknowledgesOldStopButNewSameTimeOrClockRollbackStops() {
        MonitorIntentState.Exit old = exit(1000, 1, 10), sameTime = exit(1000, 2, 10), rollback = exit(3, 3, 10);
        MonitorIntentRepository repo = new MonitorIntentRepository(new Memory());
        repo.start(MonitorIntentState.USAGE, ok(old), 2000);
        assertTrue(repo.observe(ok(old)).state.wanted);
        MonitorIntentState system = repo.observe(ok(sameTime, old)).state;
        assertFalse(system.wanted); assertEquals(MonitorIntentState.Suspension.SYSTEM_STOP, system.suspension);
        repo.start(MonitorIntentState.USAGE, ok(sameTime, old), 1);
        assertFalse(repo.observe(ok(rollback, sameTime, old)).state.wanted);
    }
    @Test public void updateReasonBenignAndPre34AmbiguousUserReasonStops() {
        MonitorIntentRepository repo = new MonitorIntentRepository(new Memory());
        repo.start(MonitorIntentState.USAGE, ok(), 10);
        assertTrue(repo.observe(ok(exit(20, 1, 16))).state.wanted);
        assertFalse(repo.observe(ok(exit(21, 2, 10))).state.wanted);
    }
    @Test public void unacknowledgedCrashesAndAnrSuspendUntilExplicitStart() {
        for (int reason : new int[]{MonitorExitHistory.CRASH, MonitorExitHistory.CRASH_NATIVE, MonitorExitHistory.ANR}) {
            MonitorIntentState.Exit failure = exit(90, reason, reason);
            MonitorIntentRepository repo = new MonitorIntentRepository(new Memory());
            MonitorIntentState started = repo.start(MonitorIntentState.USAGE, ok(), 100).state;
            MonitorIntentState suspended = repo.observe(ok(failure)).state;
            assertTrue(suspended.wanted); assertEquals(started.revision + 1, suspended.revision);
            assertEquals(MonitorIntentState.Suspension.MANUAL_RETRY_REQUIRED, suspended.suspension);
            assertFalse(suspended.mayRun(suspended.revision));
            assertEquals(suspended.revision, repo.observe(ok(failure)).state.revision);
            MonitorIntentState retry = repo.start(MonitorIntentState.USAGE, ok(failure), 50).state;
            assertTrue(retry.mayRun(retry.revision)); assertTrue(repo.observe(ok(failure)).state.wanted);
            assertEquals(MonitorIntentState.Suspension.NONE, repo.read().state.suspension);
        }
    }
    @Test public void userRequestedStopWinsOverCrashRegardlessOfHistoryOrder() {
        for (boolean reverse : new boolean[]{false, true}) {
            MonitorIntentRepository repo = new MonitorIntentRepository(new Memory()); repo.start(MonitorIntentState.USAGE, ok(), 1);
            MonitorIntentState.Exit crash = exit(2, 1, MonitorExitHistory.CRASH), stop = exit(3, 2, MonitorExitHistory.USER_REQUESTED);
            MonitorIntentState state = repo.observe(reverse ? ok(stop, crash) : ok(crash, stop)).state;
            assertFalse(state.wanted); assertEquals(MonitorIntentState.Suspension.SYSTEM_STOP, state.suspension);
        }
    }
    @Test public void acknowledgedCrashAndNormalSystemReclaimDoNotSuspend() {
        MonitorIntentRepository repo = new MonitorIntentRepository(new Memory());
        MonitorIntentState.Exit oldCrash = exit(300, 1, MonitorExitHistory.CRASH);
        MonitorIntentState started = repo.start(MonitorIntentState.SHIZUKU, ok(oldCrash), 400).state;
        MonitorIntentState observed = repo.observe(ok(exit(401, 2, 3), exit(402, 3, 16), oldCrash)).state;
        assertTrue(observed.wanted); assertEquals(started.revision, observed.revision);
        assertEquals(MonitorIntentState.Suspension.NONE, observed.suspension);
    }
    @Test public void unsupportedAndUnavailableHistoryNeverEnrollOrRestore() {
        MonitorIntentRepository repo = new MonitorIntentRepository(new Memory());
        assertEquals(MonitorIntentRepository.Code.UNSUPPORTED, repo.start(MonitorIntentState.USAGE,
                MonitorExitHistory.unavailable(MonitorExitHistory.Status.UNSUPPORTED), 10).code);
        assertEquals(MonitorIntentRepository.Code.HISTORY_UNAVAILABLE, repo.start(MonitorIntentState.USAGE,
                MonitorExitHistory.unavailable(MonitorExitHistory.Status.ERROR), 10).code);
        assertFalse(repo.read().state.enrolled);
        repo.start(MonitorIntentState.USAGE, ok(), 10);
        assertEquals(MonitorIntentRepository.Code.HISTORY_UNAVAILABLE, repo.observe(null).code);
    }
    @Test public void boundedRecentFingerprintsAndStrictCodec() throws Exception {
        List<MonitorIntentState.Exit> exits = new ArrayList<>();
        for (int i = 0; i < 80; i++) exits.add(exit(1000 - i, i + 1, 1));
        MonitorIntentState state = MonitorIntentState.empty().start(MonitorIntentState.USAGE, exits, 20);
        assertEquals(64, state.acknowledged.size()); assertEquals(exits.get(0), state.acknowledged.get(0));
        byte[] bytes = MonitorIntentCodec.encode(state); MonitorIntentState decoded = MonitorIntentCodec.decode(bytes);
        assertEquals(state.acknowledged, decoded.acknowledged);
        String binary = new String(bytes, StandardCharsets.ISO_8859_1);
        assertFalse(binary.contains("friend")); assertFalse(binary.contains("ocr"));
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        try { MonitorIntentCodec.decode(trailing); fail("trailing data"); } catch (IOException expected) { }
    }
}
