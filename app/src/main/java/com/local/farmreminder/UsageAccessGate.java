package com.local.farmreminder;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;

public final class UsageAccessGate {
    private UsageAccessGate() { }

    public static boolean hasPermission(Context context) {
        try {
            if (context == null) return false;
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
