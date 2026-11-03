package com.local.farmreminder;

import android.app.Application;
import android.content.Context;
import rikka.shizuku.ShizukuProvider;

public final class FarmApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        ShizukuProvider.disableAutomaticSuiInitialization();
    }
}
