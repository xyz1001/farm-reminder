package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class MonitorNotificationPresentationTest {
    private static OcrProgress progress(long processed, int queued, boolean recognizing, String reason) {
        return new OcrProgress(processed, 99, queued, 1024, recognizing, 3, 2, 2048, false, reason);
    }

    @Test public void idleProgressHidesCountsEvenWithProcessedFramesAndRetainedHistory() {
        assertEquals("监视中", MonitorNotificationPresentation.TITLE);
        assertEquals("",
                MonitorNotificationPresentation.content(progress(7, 0, false, "NONE"), "NONE"));
    }

    @Test public void promotionUsesSameCopyWithoutLoadingStatistics() {
        assertEquals("", MonitorNotificationPresentation.content(null, null));
    }

    @Test public void inFlightRecognitionShowsCountsWithoutQueuedFrames() {
        assertEquals("待处理 0 张",
                MonitorNotificationPresentation.content(progress(7, 0, true, "NONE"), null));
    }

    @Test public void writingOrUnsavedCaptureShowsCountsWithoutQueuedFrames() {
        OcrProgress writing = new OcrProgress(7, 99, 0, 1024, false, 3, 2, 2048, true, "NONE");
        OcrProgress unsaved = new OcrProgress(7, 99, 0, 1024, false, 3, 2, 2048, false, "WRITE_FAILED", true);
        assertEquals("待处理 0 张", MonitorNotificationPresentation.content(writing, null));
        assertEquals("待处理 0 张 · 保存失败", MonitorNotificationPresentation.content(unsaved, null));
    }

    @Test public void completingWorkHidesCountsAgain() {
        assertEquals("待处理 1 张",
                MonitorNotificationPresentation.content(progress(7, 1, false, "NONE"), null));
        assertEquals("待处理 0 张",
                MonitorNotificationPresentation.content(progress(7, 0, true, "NONE"), null));
        assertEquals("", MonitorNotificationPresentation.content(progress(8, 0, false, "NONE"), null));
    }

    @Test public void queuedFramesAndInFlightRecognitionAreSeparate() {
        assertEquals("待处理 4 张",
                MonitorNotificationPresentation.content(progress(7, 4, true, "NONE"), "NONE"));
        assertEquals("待处理 4 张",
                MonitorNotificationPresentation.content(progress(7, 4, false, "NONE"), null));
    }

    @Test public void processedHistoryNeverChangesPendingCopyOrAddsRecognitionLabel() {
        for (long processed : new long[]{0, 7, 999}) {
            String content = MonitorNotificationPresentation.content(progress(processed, 4, true, "NONE"), null);
            assertEquals("待处理 4 张", content);
            assertFalse(content.contains("已处理"));
            assertFalse(content.contains("识别中"));
            assertEquals("", MonitorNotificationPresentation.content(progress(processed, 0, false, "NONE"), null));
        }
    }

    @Test public void routineWaitingAndScreenStatesAddNoStatusOrError() {
        for (String reason : new String[]{"NONE", "LOCKED", "NOT_TARGET", "STOPPED", "BUSY",
                "USER_STOP", "NOT_FOREGROUND", "USAGE_VERIFY_PENDING", "SHIZUKU", "UNKNOWN", "NON_MATCH"}) {
            assertEquals("",
                    MonitorNotificationPresentation.content(progress(7, 0, false, reason), reason));
        }
    }

    @Test public void currentErrorsUseOneShortMessageWithoutRepeatingTitle() {
        String[] reasons = {"ACCESSIBILITY", "SOURCE_PERMISSION", "SHIZUKU_PERMISSION", "SHIZUKU_CONNECTION", "WRITE_FAILED",
                "STORAGE_FAILED", "STORAGE_ERROR", "LOW_SPACE", "WATCHDOG", "OCR_TIMEOUT",
                "CAPTURE_TIMEOUT", "ENGINE_FATAL"};
        String[] words = {"无障碍未连接", "来源权限失效", "Shizuku 授权不可用", "Shizuku 连接中断", "保存失败",
                "保存失败", "保存失败", "存储空间不足", "任务超时", "任务超时", "任务超时", "识别服务异常"};
        for (int i = 0; i < reasons.length; i++) {
            String content = MonitorNotificationPresentation.content(progress(7, 0, false, reasons[i]), reasons[i]);
            assertEquals(words[i], content);
            assertFalse(content.contains(MonitorNotificationPresentation.TITLE));
        }
    }

    @Test public void currentDependencyErrorTakesPriorityOverProgressError() {
        assertEquals("无障碍未连接",
                MonitorNotificationPresentation.content(progress(7, 0, false, "WRITE_FAILED"), "ACCESSIBILITY"));
    }

    @Test public void broadShizukuPauseDoesNotOverrideCurrentCaptureErrorOrSurviveRecovery() {
        OcrProgress paused = progress(7, 0, false, "SHIZUKU");
        assertEquals("截图失败",
                MonitorNotificationPresentation.content(paused, null, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED));
        assertEquals("Shizuku 连接中断",
                MonitorNotificationPresentation.content(paused, "SHIZUKU_CONNECTION"));
        assertEquals("",
                MonitorNotificationPresentation.content(paused, null));
    }

    @Test public void storageErrorStillAppearsWhenScreenIsLockedOrOutsideTarget() {
        for (String reason : new String[]{"LOCKED", "NOT_TARGET", null}) {
            assertEquals("存储空间不足",
                    MonitorNotificationPresentation.content(progress(7, 0, false, "LOW_SPACE"), reason));
        }
    }

    @Test public void recoveryRemovesErrorWithoutKeepingHistoricalFailures() {
        assertTrue(MonitorNotificationPresentation.content(progress(7, 0, false, "WRITE_FAILED"), null)
                .endsWith("保存失败"));
        // failedCount remains nonzero: history is not evidence of a current blocker.
        assertEquals("",
                MonitorNotificationPresentation.content(progress(7, 0, false, "NONE"), null));
    }

    @Test public void unknownDiagnosticsAreNeverDumpedIntoNotification() {
        assertEquals("",
                MonitorNotificationPresentation.content(progress(7, 0, false, "TECHNICAL_DETAIL"), "raw diagnostic"));
    }
    @Test public void currentNativeFailureAppearsAlongsideProgressAndClears() {
        OcrProgress progress = progress(7, 2, false, "NONE");
        ContainerCapturePolicy p = new ContainerCapturePolicy();
        p.enter("stay", 1, false); p.begin("stay", 1, 1);
        p.screenshotFailure("stay", 1, 1, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
        p.fail("stay", 1, 1);
        assertEquals("待处理 2 张 · 截图失败",
                MonitorNotificationPresentation.content(progress, null, p.captureIssue("stay", 1)));
        assertEquals("待处理 2 张 · 截图请求受限",
                MonitorNotificationPresentation.content(progress, null, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_RATE_LIMIT));
        assertEquals("待处理 2 张 · 无障碍未连接",
                MonitorNotificationPresentation.content(progress, "ACCESSIBILITY", p.captureIssue("stay", 1)));
        p.clear();
        assertEquals("待处理 2 张",
                MonitorNotificationPresentation.content(progress, null, p.captureIssue("stay", 1)));
    }
}
