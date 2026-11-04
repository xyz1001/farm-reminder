package com.local.farmreminder;

import org.junit.Test;
import java.util.Calendar;
import static org.junit.Assert.*;

public class ScreenPresentationTest {
    private static OcrProgress progress(boolean recognizing, boolean writing, String reason, boolean unsaved, int failed) {
        return new OcrProgress(7,11,2,2048,recognizing,failed,3,2*1024*1024L,writing,reason,unsaved,5);
    }

    @Test public void healthyMonitoringHasNoSecondStatusSentence() {
        for (String status : new String[]{"", "USAGE_VERIFY_READY", "微信小程序容器观察中", "保存成功"})
            assertEquals("", ScreenPresentation.runningDetail(status, true));
        assertEquals("", ScreenPresentation.runningDetail(null, false));
    }

    @Test public void actionableErrorsAndWaitingRemainVisibleInPlainLanguage() {
        assertTrue(ScreenPresentation.runningDetail("WRITE_FAILED", true).contains("工具页"));
        assertTrue(ScreenPresentation.runningDetail("OCR_TIMEOUT", true).contains("超时"));
        assertTrue(ScreenPresentation.runningDetail("截图保存交接失败", true).contains("保存"));
        assertTrue(ScreenPresentation.runningDetail("USAGE_VERIFY_REJECTED", false).contains("退出小程序"));
        assertEquals("等待进入农场。", ScreenPresentation.runningDetail("NOT_FOREGROUND", true));
        assertEquals("请重新开始监测。", ScreenPresentation.runningDetail("CAPTURE_NO_MATCH_TIMEOUT", false));
        assertTrue(ScreenPresentation.runningDetail("截图任务未结束", true).contains("等待"));
    }

    @Test public void toolsKeepCountsAndStorageWithoutZeroFailureOrProtocolExplanation() {
        assertEquals("已处理 7 张 · 排队 2 张\n留存 5 张 · 2.0 MiB",
                ScreenPresentation.processingSummary(progress(false,false,"NONE",false,0)));
        assertTrue(ScreenPresentation.processingSummary(progress(false,false,"NONE",false,2)).contains("失败 2 张"));
        assertEquals("", ScreenPresentation.processingNotice(progress(false,false,"NONE",false,0)));
        assertEquals("", ScreenPresentation.processingNotice(progress(false,false,"STOPPED",false,0)));
    }

    @Test public void unsavedCaptureWarningSurvivesEvenWhileWritingOrRecognizing() {
        String warning = ScreenPresentation.processingNotice(progress(true,true,"NONE",true,0));
        assertTrue(warning.contains("未保存")); assertTrue(warning.contains("重试")); assertTrue(warning.contains("勿关闭"));
        assertTrue(warning.contains("清除数据"));
        assertEquals(warning, ScreenPresentation.processingNotice(progress(false,false,"WRITE_FAILED",false,0)));
    }

    @Test public void activeProcessingAndLowStorageAreShownOnlyAsNeeded() {
        assertEquals("正在识别", ScreenPresentation.processingNotice(progress(true,false,"NONE",false,0)));
        assertEquals("正在保存截图", ScreenPresentation.processingNotice(progress(false,true,"NONE",false,0)));
        assertTrue(ScreenPresentation.processingNotice(progress(false,false,"LOW_SPACE",false,0)).contains("存储空间不足"));
    }

    @Test public void notificationHelpIsConditionalAndStatusHasNoChannelJargon() {
        assertEquals("", ScreenPresentation.notificationHint(NotificationSettingsPolicy.State.VIBRATION_ON));
        assertTrue(ScreenPresentation.notificationHint(NotificationSettingsPolicy.State.VIBRATION_OFF).contains("系统设置"));
        assertEquals("提醒通知已关闭", ScreenPresentation.notificationStatus(NotificationSettingsPolicy.State.CHANNEL_BLOCKED));
        assertEquals("应用通知已关闭", ScreenPresentation.notificationStatus(NotificationSettingsPolicy.State.APP_BLOCKED));
        assertEquals("提醒振动已开启", ScreenPresentation.notificationStatus(NotificationSettingsPolicy.State.VIBRATION_ON));
    }

    @Test public void maturityTimeDoesNotRepeatTheIdenticalTriggerTime() {
        long mature = localTime(15,3,4,5);
        assertEquals("成熟时提醒", ScreenPresentation.schedule(mature,mature));
        assertEquals("提前 1 分钟提醒 · 03:03:05", ScreenPresentation.schedule(mature,mature-60000));
        assertEquals("提前 15 秒提醒 · 03:03:50", ScreenPresentation.schedule(mature,mature-15000));
    }

    @Test public void triggerAcrossMidnightKeepsDateAndSeconds() {
        long mature = localTime(15,0,0,5);
        assertEquals("提前 1 分钟提醒 · 1月14日 23:59:05", ScreenPresentation.schedule(mature,mature-60000));
    }

    private static long localTime(int day,int hour,int minute,int second) {
        Calendar time=Calendar.getInstance();time.clear();time.set(2026,Calendar.JANUARY,day,hour,minute,second);
        return time.getTimeInMillis();
    }
}
