package com.local.farmreminder;

/** In-memory notification copy only; does not start monitoring or refresh progress. */
final class MonitorNotificationPresentation {
    static final String TITLE = "监视中";

    static String content(OcrProgress progress, String currentReason) {
        return content(progress, currentReason, ContainerCapturePolicy.CaptureIssue.NONE);
    }

    static String content(OcrProgress progress, String currentReason, ContainerCapturePolicy.CaptureIssue issue) {
        // processed counts completed OCR attempts, not successful recognition or new reminders.
        long processed = progress == null ? 0 : Math.max(0, progress.processed);
        int queued = progress == null ? 0 : Math.max(0, progress.queued);
        String text = "已处理 " + processed + " 张 · 待处理 " + queued + " 张";
        if (progress != null && progress.recognizing) text += " · 识别中";
        String error = shortError(currentReason);
        if (error.isEmpty()) error = shortCaptureIssue(issue);
        if (error.isEmpty() && progress != null) error = shortError(progress.pauseReason);
        return error.isEmpty() ? text : text + " · " + error;
    }

    private static String shortCaptureIssue(ContainerCapturePolicy.CaptureIssue issue) {
        if (issue == null) return "";
        switch (issue) {
            case SCREENSHOT_FAILED: return "截图失败";
            case SCREENSHOT_RATE_LIMIT: return "截图请求受限";
            default: return "";
        }
    }

    private static String shortError(String reason) {
        if (reason == null) return "";
        switch (reason) {
            case "ACCESSIBILITY": return "无障碍未连接";
            case "SOURCE_PERMISSION": return "来源权限失效";
            case "SHIZUKU_PERMISSION": return "Shizuku 授权不可用";
            case "SHIZUKU_CONNECTION": return "Shizuku 连接中断";
            case "WRITE_FAILED":
            case "STORAGE_FAILED":
            case "STORAGE_ERROR": return "保存失败";
            case "LOW_SPACE": return "存储空间不足";
            case "WATCHDOG":
            case "OCR_TIMEOUT":
            case "CAPTURE_TIMEOUT": return "任务超时";
            case "ENGINE_FATAL": return "识别服务异常";
            default: return "";
        }
    }

    private MonitorNotificationPresentation() { }
}
