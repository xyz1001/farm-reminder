package com.local.farmreminder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

final class WeChatSupportNavigator {
    static boolean open(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (intent == null) return false;
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                // Best effort: WeChat may accept this shortcut hint without opening its scanner.
                context.startActivity(new Intent(intent).putExtra("LauncherUI.From.Scaner.Shortcut", true));
                return true;
            } catch (RuntimeException unavailable) {
                // Retry the unmodified public launcher only when starting the shortcut fails.
            }
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) { return false; }
    }

    private WeChatSupportNavigator() { }
}
