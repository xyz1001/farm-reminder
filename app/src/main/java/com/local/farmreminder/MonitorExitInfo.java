package com.local.farmreminder;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/** No side effects: query only this package's main process, without reading descriptions or traces. */
final class MonitorExitInfo {
    private MonitorExitInfo() { }
    static MonitorExitHistory query(Context context) {
        if (Build.VERSION.SDK_INT < 30) return MonitorExitHistory.unavailable(MonitorExitHistory.Status.UNSUPPORTED);
        try { return querySupported(context); }
        catch (RuntimeException e) { return MonitorExitHistory.unavailable(MonitorExitHistory.Status.ERROR); }
    }
    @TargetApi(30)
    private static MonitorExitHistory querySupported(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return MonitorExitHistory.unavailable(MonitorExitHistory.Status.ERROR);
        String packageName = context.getPackageName();
        List<ApplicationExitInfo> values = manager.getHistoricalProcessExitReasons(packageName, 0, MonitorIntentState.MAX_HISTORY);
        if (values == null) return MonitorExitHistory.unavailable(MonitorExitHistory.Status.ERROR);
        List<MonitorIntentState.Exit> exits = new ArrayList<>();
        for (ApplicationExitInfo value : values) {
            if (value == null) return MonitorExitHistory.unavailable(MonitorExitHistory.Status.ERROR);
            if (!packageName.equals(value.getProcessName())) continue;
            exits.add(new MonitorIntentState.Exit(value.getTimestamp(), value.getPid(), value.getReason(), packageName));
        }
        return MonitorExitHistory.ok(exits);
    }
}
