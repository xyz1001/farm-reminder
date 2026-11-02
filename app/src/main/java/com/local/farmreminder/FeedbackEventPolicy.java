package com.local.farmreminder;

import java.util.ArrayDeque;

/** Session-only suppression of repeated confirmed results; names never leave memory. */
final class FeedbackEventPolicy {
    private static final int MAX_KEYS = 64;
    private static final class Entry {
        final String friend; final long matureAt;
        Entry(String friend, long matureAt) { this.friend = friend; this.matureAt = matureAt; }
    }
    private final ArrayDeque<Entry> seen = new ArrayDeque<>();
    boolean confirmed(String friend, long matureAt) {
        for (Entry entry : seen) if (entry.friend.equals(friend) && Math.abs(matureAt-entry.matureAt) <= 5000) return false;
        seen.addLast(new Entry(friend, matureAt));
        if (seen.size() > MAX_KEYS) seen.removeFirst();
        return true;
    }
    void reset() { seen.clear(); }
}
