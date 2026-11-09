package com.local.farmreminder;

import java.util.ArrayList;
import java.util.List;

/** Retains reminders until one minute after maturity, regardless of delivery state. */
public final class ReminderRetentionPolicy {
    private static final long RETENTION_MS = 60_000L;

    private ReminderRetentionPolicy() { }

    public static boolean isExpired(Reminder reminder, long now) {
        return reminder.matureAt <= Long.MAX_VALUE - RETENTION_MS
                && now >= reminder.matureAt + RETENTION_MS;
    }

    interface Storage {
        boolean repair() throws Exception;
        List<Reminder> read() throws Exception;
        boolean write(List<Reminder> reminders) throws Exception;
        default void failure(String stage, Throwable cause) { }
    }

    private static int failed(Storage storage, String stage, Throwable cause) {
        try { storage.failure(stage, cause); } catch (Throwable ignored) { }
        return -1;
    }

    static int prune(Storage storage, long now) {
        try {
            if (!storage.repair()) return failed(storage, "REPAIR_FAILED", null);
        } catch (Exception e) {
            return failed(storage, "REPAIR_FAILED", e);
        }
        List<Reminder> retained;
        int removed;
        try {
            List<Reminder> items = storage.read();
            retained = new ArrayList<>(items.size());
            for (Reminder item : items) if (!isExpired(item, now)) retained.add(item);
            removed = items.size() - retained.size();
        } catch (Exception e) {
            return failed(storage, "READ_FAILED", e);
        }
        if (removed == 0) return 0;
        try {
            return storage.write(retained) ? removed : failed(storage, "WRITE_FAILED", null);
        } catch (Exception e) {
            return failed(storage, "WRITE_FAILED", e);
        }
    }
}
