package com.local.farmreminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try { new AlarmScheduler(context).rescheduleAll(); } catch (RuntimeException ignored) { }
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) MonitorCoordinator.restore(context, MonitorRuntimePolicy.Trigger.BOOT);
        else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) MonitorCoordinator.restore(context, MonitorRuntimePolicy.Trigger.UPDATE);
    }
}
