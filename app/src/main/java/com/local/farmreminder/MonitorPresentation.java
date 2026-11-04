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
            title = "设备暂不支持"; detail = "截图需要 Android 11 及以上，已有提醒与导出仍可使用。";
        } else if (state == MonitorCoordinator.State.RUNNING && monitoringRunning) {
            title = "监测中"; detail = "根据所选模式判断农场前台，满足条件时截图。";
        } else if (state == MonitorCoordinator.State.STARTING) {
            title = "正在准备监测"; detail = "监测请求已记录，正在等待服务与所需条件就绪。";
        } else if (state == MonitorCoordinator.State.WAITING || state == MonitorCoordinator.State.RUNNING) {
            title = "等待监测"; detail = waiting(reason);
        } else if (systemStopped) {
            title = "监测已停止"; detail = "系统已停止监测；只有手动开始后才会再次监测。";
        } else if (state == MonitorCoordinator.State.SUSPENDED) {
            title = "监测已暂停"; detail = suspended(reason);
        } else if (state == MonitorCoordinator.State.ERROR) {
            title = reason == MonitorCoordinator.Reason.STORAGE_ERROR ? "状态保存失败" : "监测未恢复";
            detail = reason == MonitorCoordinator.Reason.STORAGE_ERROR
                    ? "监测状态未能可靠保存，请重试操作；暂无法确认重启后的恢复状态。"
                    : reason == MonitorCoordinator.Reason.HISTORY_UNAVAILABLE
                    ? "无法确认上次退出原因，未自动恢复；请手动重试。" : suspended(reason);
        } else if (foregroundActive || monitoringRunning) {
            title = "正在停止"; detail = "正在结束当前监测，请等待服务退出。";
        } else if (wanted) {
            title = "等待监测"; detail = waiting(reason);
        } else {
            title = setupReady ? "准备就绪" : "需要完成设置";
            detail = reason == MonitorCoordinator.Reason.SYSTEM_STOP
                    ? "系统已停止监测；只有手动开始后才会再次监测。"
                    : reason == MonitorCoordinator.Reason.USER_STOP
                    ? "已主动停止，更新或重启后不恢复；截图与提醒保留。"
                    : setupReady ? "手动开始后，进入好友农场查看成熟时间。" : "完成所选模式的权限设置后，再手动开始监测。";
        }
        String startLabel = !supported || reason == MonitorCoordinator.Reason.UNSUPPORTED ? "设备不支持"
                : retry ? "重试监测" : !setupReady ? "完成设置" : "开始监测";
        return new MonitorPresentation(title, detail, startLabel, showStart,
                supported && reason != MonitorCoordinator.Reason.UNSUPPORTED && showStart,
                showStop, modeEditable, retry);
    }

    private static String waiting(MonitorCoordinator.Reason reason) {
        if (reason == null) return "监测尚未运行，等待服务确认；可随时停止。";
        switch (reason) {
            case ACCESSIBILITY: return "等待截图服务启用并连接；请在权限中心手动设置，可随时停止。";
            case SOURCE_PERMISSION: return "等待所选模式的来源权限；请在权限中心手动设置，可随时停止。";
            case SHIZUKU: return "等待 Shizuku 连接；请手动启动并确认授权，可随时停止。";
            case LOCKED: return "等待设备解锁；监测请求保留，可随时停止。";
            case NOT_TARGET: return "等待目标农场处于前台；监测请求保留，可随时停止。";
            case BUSY: return "截图或识别仍在处理；可停止后，在处理结束时手动重试。";
            default: return "监测尚未运行，等待服务与所需条件就绪；可随时停止。";
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
            case SOURCE_PERMISSION: return "所选模式的来源权限尚未就绪；请手动完成设置后重试。";
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
            case ACCEPTED: return "已记录监测请求，请查看当前状态。";
            case ALREADY_ACTIVE: return "已有监测请求，请查看当前状态。";
            case STORAGE_FAILED: return "监测状态保存失败，未确认启动，请重试。";
            case UNSUPPORTED: return "当前条件不支持启动，请查看监测状态。";
            default: return "启动请求未被接受，请检查后重试。";
        }
    }

    static String stopFeedback(MonitorCoordinator.RequestCode code) {
        if (code == MonitorCoordinator.RequestCode.STORAGE_FAILED)
            return "当前已停止，停止状态保存失败，请重试";
        if (code == MonitorCoordinator.RequestCode.ACCEPTED)
            return "已停止，截图与提醒保留；更新或重启后不恢复。";
        return "停止请求未确认，请重试。";
    }
}
