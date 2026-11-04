package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.local.farmreminder.MonitorCoordinator.State.*;
import static com.local.farmreminder.MonitorCoordinator.Reason.*;

public class MonitorPresentationTest {
    private static MonitorPresentation view(MonitorCoordinator.State state, MonitorCoordinator.Reason reason,
            boolean wanted, boolean foreground, boolean running) {
        return MonitorPresentation.present(state, reason, wanted, foreground, running, true, true);
    }

    @Test public void foregroundServiceAloneIsNeverMonitoring() {
        for (MonitorCoordinator.State state : new MonitorCoordinator.State[]{STARTING, WAITING, RUNNING}) {
            MonitorPresentation ui = view(state, NONE, true, true, false);
            assertNotEquals("监测中", ui.title);
            assertTrue(ui.showStop); assertFalse(ui.showStart); assertFalse(ui.modeEditable);
        }
        assertEquals("监测中", view(RUNNING, NONE, true, true, true).title);
    }

    @Test public void waitingKeepsStopAndShowsSpecificDependencyWithoutLocalTimeout() {
        MonitorCoordinator.Reason[] reasons = {ACCESSIBILITY, SOURCE_PERMISSION, SHIZUKU, LOCKED, NOT_TARGET, BUSY};
        String[] words = {"截图服务", "授权", "Shizuku", "解锁", "目标农场", "仍在处理"};
        for (int i = 0; i < reasons.length; i++) {
            MonitorPresentation ui = view(WAITING, reasons[i], true, true, false);
            assertEquals("等待监测", ui.title); assertTrue(ui.detail.contains(words[i]));
            assertTrue(ui.showStop); assertFalse(ui.showStart); assertFalse(ui.needsAttention);
            assertFalse(ui.modeEditable);
            assertEquals(ui.title, view(WAITING, reasons[i], true, true, false).title);
        }
    }

    @Test public void suspendedRequiresExplicitRetryAndStillAllowsStop() {
        for (boolean wanted : new boolean[]{false, true}) {
            MonitorPresentation ui = view(SUSPENDED, WATCHDOG, wanted, false, false);
            assertEquals("监测已暂停", ui.title); assertEquals("重试监测", ui.startLabel);
            assertTrue(ui.showStart); assertTrue(ui.startEnabled); assertTrue(ui.showStop);
            assertTrue(ui.detail.contains("手动重试")); assertTrue(ui.needsAttention);
            assertEquals(!wanted, ui.modeEditable);
        }
    }

    @Test public void stoppedBySystemNeverOffersAnAutomaticResumeState() {
        for (MonitorCoordinator.State state : new MonitorCoordinator.State[]{STOPPED, SUSPENDED}) {
            MonitorPresentation ui = view(state, SYSTEM_STOP, false, false, false);
            assertTrue(ui.showStart); assertFalse(ui.showStop); assertTrue(ui.modeEditable);
            assertEquals("开始监测", ui.startLabel); assertTrue(ui.detail.contains("只有手动开始"));
            assertNotEquals("监测中", ui.title);
        }
    }

    @Test public void firstEnrollmentIsAnExplicitStartNotAnOldRuntimeFlag() {
        MonitorPresentation ui = view(NOT_ENROLLED, NONE, false, false, false);
        assertTrue(ui.showStart); assertFalse(ui.showStop);
        assertEquals("开始监测", ui.startLabel);
        assertTrue(MonitorPresentation.RESTORE_HINT.contains("尝试恢复"));
        assertTrue(MonitorPresentation.RESTORE_HINT.contains("主动停止后不恢复"));
    }

    @Test public void missingSetupOffersManualSetupAndDoesNotUnlockAnIntendedSession() {
        MonitorPresentation idle = MonitorPresentation.present(NOT_ENROLLED, NONE, false, false, false, true, false);
        assertEquals("完成设置", idle.startLabel); assertTrue(idle.startEnabled);
        MonitorPresentation waiting = MonitorPresentation.present(WAITING, ACCESSIBILITY, true, false, false, true, false);
        assertFalse(waiting.modeEditable); assertTrue(waiting.showStop); assertFalse(waiting.showStart);
    }

    @Test public void stopSaveFailureIsNotFalseSuccessAndKeepsStopRetryAvailable() {
        assertEquals("当前已停止，停止状态保存失败，请重试",
                MonitorPresentation.stopFeedback(MonitorCoordinator.RequestCode.STORAGE_FAILED));
        MonitorPresentation ui = view(ERROR, STORAGE_ERROR, false, false, false);
        assertTrue(ui.showStop); assertEquals("状态保存失败", ui.title);
        assertFalse(ui.modeEditable);
        assertTrue(ui.detail.contains("暂无法确认"));
        assertTrue(ui.detail.contains("当前未监测"));
        assertFalse(view(ERROR, STORAGE_ERROR, true, true, true).detail.contains("当前未监测"));
    }

    @Test public void queuedAcceptanceDoesNotClaimCaptureHasStarted() {
        assertEquals("",
                MonitorPresentation.startFeedback(MonitorCoordinator.RequestCode.ACCEPTED));
        assertTrue(MonitorPresentation.startFeedback(MonitorCoordinator.RequestCode.STORAGE_FAILED).contains("未确认启动"));
        assertFalse(MonitorPresentation.startFeedback(MonitorCoordinator.RequestCode.ALREADY_ACTIVE).contains("已开始"));
    }

    @Test public void historyFailureIsNotMistakenForUnsupportedScreenshotHardware() {
        assertTrue(MonitorPresentation.startFeedback(MonitorCoordinator.RequestCode.UNSUPPORTED, HISTORY_UNAVAILABLE)
                .contains("无法确认上次退出原因"));
        assertTrue(MonitorPresentation.startFeedback(MonitorCoordinator.RequestCode.STORAGE_FAILED, HISTORY_UNAVAILABLE)
                .contains("未确认启动"));
    }

    @Test public void historyAndUnexpectedErrorsRequireManualRetry() {
        MonitorPresentation history = view(ERROR, HISTORY_UNAVAILABLE, true, false, false);
        assertTrue(history.detail.contains("未自动恢复")); assertTrue(history.showStart); assertTrue(history.showStop);
        MonitorPresentation unexpected = view(SUSPENDED, UNEXPECTED_STOP, true, false, false);
        assertTrue(unexpected.detail.contains("手动重试"));
        assertTrue(view(SUSPENDED, SOURCE_CHANGED, true, false, false).detail.contains("先停止"));
    }

    @Test public void unsupportedDeviceCannotStartButCanStopOutstandingIntent() {
        MonitorPresentation ui = MonitorPresentation.present(WAITING, UNSUPPORTED, true, true, false, false, false);
        assertFalse(ui.startEnabled); assertTrue(ui.showStop); assertNotEquals("监测中", ui.title);
    }

    @Test public void stoppedServiceStillExitingDoesNotLookReadyOrRunning() {
        MonitorPresentation ui = view(STOPPED, USER_STOP, false, true, false);
        assertEquals("正在停止", ui.title); assertFalse(ui.showStart); assertTrue(ui.showStop); assertFalse(ui.modeEditable);
    }

    @Test public void normalStatesNeedNoPermanentImplementationExplanation() {
        assertEquals("", view(RUNNING, NONE, true, true, true).detail);
        assertEquals("", view(STARTING, NONE, true, false, false).detail);
        assertEquals("", view(STOPPED, USER_STOP, false, false, false).detail);
        assertEquals("", view(NOT_ENROLLED, NONE, false, false, false).detail);
        assertFalse(view(ERROR, STORAGE_ERROR, false, false, false).detail.isEmpty());
    }

    @Test public void restoreExplanationAppearsOnlyBeforeFirstEnrollment() {
        assertTrue(MonitorPresentation.showRestoreHint(false, NOT_ENROLLED, true));
        assertFalse(MonitorPresentation.showRestoreHint(true, STOPPED, true));
        assertFalse(MonitorPresentation.showRestoreHint(true, RUNNING, true));
        assertFalse(MonitorPresentation.showRestoreHint(false, ERROR, true));
        assertFalse(MonitorPresentation.showRestoreHint(false, NOT_ENROLLED, false));
    }
}
