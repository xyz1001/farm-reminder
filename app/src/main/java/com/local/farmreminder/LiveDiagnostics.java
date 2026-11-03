package com.local.farmreminder;

/** Local-only diagnostic bookkeeping; never initiates a device or remote read. */
final class LiveDiagnostics {
    private long samples, lastSample = -1, published = -1, deviceRead = -1;
    synchronized void sample(long now) { samples++; lastSample = now; }
    synchronized void published(long now) { published = now; }
    synchronized void deviceRead(long now) { deviceRead = now; }
    synchronized String serviceSnapshot(long now) {
        return "sampleCount=" + samples + " lastSampleAge=" + age(now, lastSample)
                + " publishedAge=" + age(now, published) + " deviceStateReadAge=" + age(now, deviceRead);
    }
    static String age(long now, long then) { return then < 0 || now < then ? "unknown" : Long.toString(now - then); }
    static String exceptionName(Throwable e) { return e == null ? "none" : e.getClass().getSimpleName(); }
    static String remoteReason(String reason) {
        if (reason == null) return "none";
        switch (reason) {
            case "unsupported identity": case "query failed": case "incomplete": case "multiple displays":
            case "ambiguous foreground": case "other activity": case "unsupported activity":
            case "other miniapp": case "target": return reason;
            default: return "REDACTED";
        }
    }
    static String invalidateReason(String why) {
        switch (why) {
            case "查询超时": return "QUERY_TIMEOUT";
            case "连接超时": return "BIND_TIMEOUT";
            case "查询失败或超时": return "QUERY_FAILED";
            case "查询结果无效": return "INVALID_RESULT";
            case "服务已断开": return "DISCONNECTED";
            case "已连接，等待查询": return "CONNECTED";
            case "等待任务查询": return "WAITING_QUERY";
            case "未监测": return "STOPPED";
            default: return "AUTH_OR_OTHER";
        }
    }
}
