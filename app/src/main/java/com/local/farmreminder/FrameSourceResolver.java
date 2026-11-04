package com.local.farmreminder;

import android.content.Context;
import java.io.File;

/** Synchronous file verification: call only on a worker, outside ReminderStore.LOCK. */
final class FrameSourceResolver {
    static File resolve(Context context, String ref) {
        try { return FrameSourceFiles.resolve(new File(context.getNoBackupFilesDir().getCanonicalFile(), "frames-v1"), ref); }
        catch (Exception ignored) { return null; }
    }
}
