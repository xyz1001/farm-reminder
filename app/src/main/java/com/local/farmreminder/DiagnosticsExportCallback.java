package com.local.farmreminder;

public interface DiagnosticsExportCallback {
    void onSuccess(int frameCount);
    void onFailure(String message);
}
