package com.local.farmreminder;

import android.os.Bundle;
import android.os.Process;

/** Runs only as Shizuku's root or shell user service; no dump leaves this process. */
public final class ShizukuTaskQueryService extends ITaskQuery.Stub {
    private final BoundedProcessQuery runner = new BoundedProcessQuery(
            () -> new ProcessBuilder("/system/bin/dumpsys", "activity", "activities").start());

    public ShizukuTaskQueryService() {}

    private static Bundle unknown(String reason) {
        Bundle result = new Bundle(); result.putString("state", "UNKNOWN"); result.putString("reason", reason); return result;
    }

    @Override public Bundle queryForegroundTask() {
        if (!ShizukuIdentityPolicy.isSupportedUid(Process.myUid())) return unknown("unsupported identity");
        String dump = runner.query();
        if (dump == null) return unknown("query failed");
        TaskDumpParser.Result parsed = TaskDumpParser.parse(dump, true);
        if (runner.isDestroyed()) return unknown("query failed");
        Bundle result = new Bundle(); result.putString("state", parsed.state.name());
        result.putString("reason", parsed.reason); result.putString("key", parsed.key);
        result.putString("component", parsed.component); return result;
    }

    @Override public void destroy() {
        runner.destroy();
        System.exit(0);
    }
}
