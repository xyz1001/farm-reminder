package com.local.farmreminder;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Process singleton; all user-intent commits are synchronous AtomicFile transactions. */
final class MonitorIntentStore {
    private static MonitorIntentStore instance;
    private final MonitorIntentRepository repository;
    private MonitorIntentStore(Context context) {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "monitor-intent-v1.bin"));
        repository = new MonitorIntentRepository(new MonitorIntentRepository.Storage() {
            public byte[] read() throws IOException {
                File base = file.getBaseFile(); File parent = base.getParentFile();
                if (parent == null || !parent.isDirectory() || !parent.canRead()) throw new IOException("storage unavailable");
                if (!base.exists() && !new File(base.getPath() + ".bak").exists()) {
                    if (new File(base.getPath() + ".new").exists()) throw new IOException("incomplete record");
                    return null;
                }
                try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > 32768) throw new IOException("record too large");
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                }
            }
            public void write(byte[] bytes) throws IOException {
                MonitorIntentRepository.Storage storage = this;
                MonitorIntentCommit.commit(new MonitorIntentCommit.Transaction() {
                    private FileOutputStream out;
                    public void start() throws IOException { out = file.startWrite(); }
                    public void write(byte[] value) throws IOException { out.write(value); }
                    public void sync() throws IOException { out.getFD().sync(); }
                    public void finish() { file.finishWrite(out); }
                    public void rollback() { file.failWrite(out); }
                    public byte[] readPublished() throws IOException { return storage.read(); }
                }, bytes);
            }
        });
    }
    static synchronized MonitorIntentStore get(Context context) {
        if (instance == null) instance = new MonitorIntentStore(context.getApplicationContext());
        return instance;
    }
    MonitorIntentRepository.Result read() { return repository.read(); }
    MonitorIntentRepository.Result start(String mode, MonitorExitHistory history, long wall) { return repository.start(mode, history, wall); }
    MonitorIntentRepository.Result stop() { return repository.stop(); }
    MonitorIntentRepository.Result suspendFatal(long ownerRevision) { return repository.suspendFatal(ownerRevision); }
    MonitorIntentRepository.Result observe(MonitorExitHistory history) { return repository.observe(history); }
}
