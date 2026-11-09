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
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) { return false; }
    }

    private WeChatSupportNavigator() { }
}
