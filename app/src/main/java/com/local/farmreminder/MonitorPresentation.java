package com.local.farmreminder;

/** Read-only presentation of durable user intent and actual runtime state. No timers or side effects. */
final class MonitorPresentation {
    static final String RESTORE_HINT = "开启后，更新或重启将尝试恢复；主动停止后不恢复。";
    final String title, detail, startLabel;
    final boolean showStart, startEnabled, showStop, modeEditable, needsAttention;

    private MonitorPresentation(String title, String detail, String startLabel, boolean showStart,
            boolean startEnabled, boolean showStop, boolean modeEditable, boolean needsAttention) {
        this.title = title; this.detail = detail; this.startLabel = startLabel;
        this.showStart = showStart; this.startEnabled = startEnabled; this.showStop = showStop;
        this.modeEditable = modeEditable; this.needsAttention = needsAttention;
    }

    static MonitorPresentation from(MonitorCoordinator.Snapshot snapshot, boolean supported, boolean setupReady) {
        return present(snapshot.state, snapshot.reason, snapshot.wanted, snapshot.foregroundActive,
                snapshot.monitoringRunning, supported, setupReady);
    }

    static boolean showRestoreHint(boolean enrolled, MonitorCoordinator.State state, boolean supported) {
        return supported && !enrolled && state == MonitorCoordinator.State.NOT_ENROLLED;
    }

    static MonitorPresentation present(MonitorCoordinator.State state, MonitorCoordinator.Reason reason,
            boolean wanted, boolean foregroundActive, boolean monitoringRunning, boolean supported, boolean setupReady) {
        boolean systemStopped = reason == MonitorCoordinator.Reason.SYSTEM_STOP && !wanted && !foregroundActive && !monitoringRunning;
        boolean retry = !systemStopped && (state == MonitorCoordinator.State.SUSPENDED || state == MonitorCoordinator.State.ERROR);
        boolean session = state == MonitorCoordinator.State.STARTING || state == MonitorCoordinator.State.WAITING
                || state == MonitorCoordinator.State.RUNNING;
        boolean showStop = wanted || foregroundActive || monitoringRunning || session
                || state == MonitorCoordinator.State.SUSPENDED && !systemStopped || reason == MonitorCoordinator.Reason.STORAGE_ERROR;
        boolean showStart = retry || !(session || wanted || foregroundActive || monitoringRunning);
        boolean modeEditable = !wanted && !foregroundActive && !monitoringRunning && !session
                && reason != MonitorCoordinator.Reason.STORAGE_ERROR;
        String title, detail;
        if (!supported || reason == MonitorCoordinator.Reason.UNSUPPORTED) {
            title = "设备暂不支持"; detail = "截图需要 Android 11 及以上。";
        } else if (state == MonitorCoordinator.State.RUNNING && monitoringRunning) {
            title = "监测中"; detail = "";
        } else if (state == MonitorCoordinator.State.STARTING) {
            title = "正在准备监测"; detail = "";
        } else if (state == MonitorCoordinator.State.WAITING || state == MonitorCoordinator.State.RUNNING) {
            title = "等待监测"; detail = waiting(reason);
        } else if (systemStopped) {
            title = "系统已停止监测"; detail = "只有手动开始后才会再次监测。";
        } else if (state == MonitorCoordinator.State.SUSPENDED) {
            title = "监测已暂停"; detail = suspended(reason);
        } else if (state == MonitorCoordinator.State.ERROR) {
            title = reason == MonitorCoordinator.Reason.STORAGE_ERROR ? "状态保存失败" : "监测未恢复";
            detail = reason == MonitorCoordinator.Reason.STORAGE_ERROR
                    ? (!monitoringRunning && !foregroundActive ? "当前未监测，" : "")
                            + "状态未保存；暂无法确认重启后是否恢复，请重试。"
                    : reason == MonitorCoordinator.Reason.HISTORY_UNAVAILABLE
                    ? "无法确认上次退出原因，未自动恢复；请手动重试。" : suspended(reason);
        } else if (foregroundActive || monitoringRunning) {
            title = "正在停止"; detail = "";
        } else if (wanted) {
            title = "等待监测"; detail = waiting(reason);
        } else {
            title = !setupReady ? "需要完成设置" : reason == MonitorCoordinator.Reason.USER_STOP ? "已停止" : "准备就绪";
            detail = reason == MonitorCoordinator.Reason.SYSTEM_STOP
                    ? "只有手动开始后才会再次监测。"
                    : setupReady ? "" : "请先完成所选模式的权限设置。";
        }
        String startLabel = !supported || reason == MonitorCoordinator.Reason.UNSUPPORTED ? "设备不支持"
                : retry ? "重试监测" : !setupReady ? "完成设置" : "开始监测";
        return new MonitorPresentation(title, detail, startLabel, showStart,
                supported && reason != MonitorCoordinator.Reason.UNSUPPORTED && showStart,
                showStop, modeEditable, retry);
    }

    private static String waiting(MonitorCoordinator.Reason reason) {
        if (reason == null) return "等待监测条件就绪。";
        switch (reason) {
            case ACCESSIBILITY: return "请在权限中心启用并连接截图服务。";
            case SOURCE_PERMISSION: return "请在权限中心完成所选模式的授权。";
            case SHIZUKU: return "请手动启动 Shizuku 并确认授权。";
            case LOCKED: return "等待设备解锁。";
            case NOT_TARGET: return "等待进入目标农场。";
            case BUSY: return "截图或识别仍在处理。";
            default: return "等待监测条件就绪。";
        }
    }

    private static String suspended(MonitorCoordinator.Reason reason) {
        if (reason == null) return "本次未自动恢复，请检查后手动重试。";
        switch (reason) {
            case BUSY: return "上次截图或识别尚未结束；处理完成后请手动重试。";
            case WATCHDOG: return "截图或识别超时；请检查后手动重试。";
            case ENGINE_FATAL: return "截图或识别出现问题；请检查后手动重试。";
            case SOURCE_CHANGED: return "监测来源已改变；请先停止，确认模式后再开始。";
            case HISTORY_UNAVAILABLE: return "无法确认上次退出原因，未自动恢复；请手动重试。";
            case START_REJECTED: return "系统未接受启动请求；请检查所需条件后手动重试。";
            case UNEXPECTED_STOP: return "上次监测意外结束，需要手动重试。";
            case ACCESSIBILITY: return "截图服务尚未就绪；请手动完成设置后重试。";
            case SOURCE_PERMISSION: return "所选模式尚未授权，请完成设置后重试。";
            case SHIZUKU: return "Shizuku 尚未就绪；请手动启动并确认授权后重试。";
            case LOCKED: return "请解锁设备后手动重试。";
            default: return "本次未自动恢复，请检查后手动重试。";
        }
    }

    static String startFeedback(MonitorCoordinator.RequestCode code) {
        return startFeedback(code, MonitorCoordinator.Reason.NONE);
    }

    static String startFeedback(MonitorCoordinator.RequestCode code, MonitorCoordinator.Reason reason) {
        if (reason == MonitorCoordinator.Reason.HISTORY_UNAVAILABLE)
            return "无法确认上次退出原因，未确认启动；请检查后手动重试。";
        if (code == null) return "监测请求未确认，请重试。";
        switch (code) {
            case ACCEPTED: return ""; // The visible state already acknowledges progress; do not add a second status banner.
            case ALREADY_ACTIVE: return "";
            case STORAGE_FAILED: return "监测状态保存失败，未确认启动，请重试。";
            case UNSUPPORTED: return "当前条件不支持启动，请查看监测状态。";
            default: return "启动请求未被接受，请检查后重试。";
        }
    }

    static String stopFeedback(MonitorCoordinator.RequestCode code) {
        if (code == MonitorCoordinator.RequestCode.STORAGE_FAILED)
            return "当前已停止，停止状态保存失败，请重试";
        if (code == MonitorCoordinator.RequestCode.ACCEPTED)
            return "已停止，记录保留。";
        return "停止请求未确认，请重试。";
    }
}
