package com.local.farmreminder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Short, conditional screen copy only; never changes monitoring or reminder data. */
final class ScreenPresentation {
    static String runningDetail(String status, boolean shizuku) {
        if (status == null || status.isEmpty()) return "";
        if (status.contains("LOW_SPACE")) return "空间不足，请检查存储。";
        if (status.contains("WRITE_FAILED") || status.contains("STORAGE_FAILED")
                || status.contains("保存") && status.contains("失败"))
            return "保存遇到问题，请到工具页检查。";
        if (status.contains("OCR_TIMEOUT")) return "识别超时，请稍后重试。";
        if (status.contains("CAPTURE_TIMEOUT")) return "截图超时，请稍后重试。";
        if (status.contains("USAGE_VERIFY_REJECTED") || status.contains("USAGE_VERIFY_FAILED"))
            return "未确认农场，请退出小程序后重进。";
        if (status.contains("SERVICE_UNAVAILABLE") || status.contains("服务断开") || status.contains("服务中断"))
            return "截图服务未连接，请检查权限。";
        if (status.contains("CAPTURE_NO_MATCH_TIMEOUT")) return shizuku ? "等待进入农场。" : "请重新开始监测。";
        if (status.contains("失败") || status.contains("错误")) return "截图遇到问题，请到工具页检查。";
        if (status.contains("任务未结束")) return "等待上次任务完成。";
        if (status.contains("USAGE_VERIFY_PENDING")) return "正在确认农场。";
        if (status.contains("NOT_FOREGROUND") || status.contains("等待")) return "等待进入农场。";
        return "";
    }

    static String processingSummary(OcrProgress progress) {
        return "已处理 " + progress.processed + " 张 · 排队 " + progress.queued + " 张\n"
                + "留存 " + progress.archivedCount + " 张"
                + (progress.failedCount > 0 ? " · 失败 " + progress.failedCount + " 张" : "")
                + String.format(Locale.CHINA, " · %.1f MiB", progress.retainedBytes / (1024f * 1024f));
    }

    static String processingNotice(OcrProgress progress) {
        if (progress.hasUnsavedCapture || "WRITE_FAILED".equals(progress.pauseReason))
            return "有截图未保存，请先重试；勿关闭应用或清除数据。";
        if ("LOW_SPACE".equals(progress.pauseReason)) return "存储空间不足，请先释放空间。";
        if (progress.writing) return "正在保存截图";
        return progress.recognizing ? "正在识别" : "";
    }

    static String notificationStatus(NotificationSettingsPolicy.State state) {
        switch (state) {
            case APP_BLOCKED: return "应用通知已关闭";
            case CHANNEL_BLOCKED: return "提醒通知已关闭";
            case VIBRATION_OFF: return "提醒振动已关闭";
            case VIBRATION_ON: return "提醒振动已开启";
            case CHANNEL_MISSING: return "提醒通知尚未创建";
            default: return "暂时无法读取通知设置";
        }
    }

    static String notificationHint(NotificationSettingsPolicy.State state) {
        return state == NotificationSettingsPolicy.State.VIBRATION_OFF ? "可在系统设置中开启振动，实际效果仍受系统设置影响。" : "";
    }

    static String schedule(long matureAt, long triggerAt) {
        long seconds = Math.max(0, (matureAt - triggerAt) / 1000);
        if (seconds == 0) return "成熟时提醒";
        String lead = "提前 " + (seconds % 60 == 0 ? seconds / 60 + " 分钟" : seconds + " 秒") + "提醒";
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA);
        boolean sameDay = day.format(new Date(matureAt)).equals(day.format(new Date(triggerAt)));
        return lead + " · " + new SimpleDateFormat(sameDay ? "HH:mm:ss" : "M月d日 HH:mm:ss", Locale.CHINA)
                .format(new Date(triggerAt));
    }

    private ScreenPresentation() { }
}
