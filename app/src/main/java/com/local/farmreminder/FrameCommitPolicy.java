package com.local.farmreminder;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

/** Legacy checkpoint serialization; each observation is independently confirmed. */
final class FrameCommitPolicy {
    static final class Checkpoint {
        String frameId, sessionId, friend;
        int windowId, count;
        long sequence, uptime, maturity;

        String encode() throws Exception {
            Properties p = new Properties();
            p.setProperty("frame", frameId); p.setProperty("session", sessionId);
            p.setProperty("window", Integer.toString(windowId)); p.setProperty("sequence", Long.toString(sequence));
            p.setProperty("uptime", Long.toString(uptime)); p.setProperty("friend", friend);
            p.setProperty("maturity", Long.toString(maturity)); p.setProperty("count", Integer.toString(count));
            StringWriter out = new StringWriter(); p.store(out, null); return out.toString();
        }
        static Checkpoint decode(String value) throws Exception {
            if (value == null) return null;
            Properties p = new Properties(); p.load(new StringReader(value));
            Checkpoint c = new Checkpoint(); c.frameId = required(p, "frame"); c.sessionId = required(p, "session");
            c.windowId = Integer.parseInt(required(p, "window")); c.sequence = Long.parseLong(required(p, "sequence"));
            c.uptime = Long.parseLong(required(p, "uptime")); c.friend = required(p, "friend");
            c.maturity = Long.parseLong(required(p, "maturity")); c.count = Integer.parseInt(required(p, "count"));
            if (c.frameId.isEmpty() || c.sessionId.isEmpty() || c.sequence <= 0 || c.uptime < 0 || c.maturity < 0 || c.count < 0 || c.count > 2 || (c.count > 0 && c.friend.isEmpty())) throw new IllegalArgumentException("invalid checkpoint");
            return c;
        }
        private static String required(Properties p, String key) {
            String value = p.getProperty(key); if (value == null) throw new IllegalArgumentException("missing " + key); return value;
        }
    }
    static Checkpoint reduce(Checkpoint previous, String id, String session, int window, long sequence,
                             long uptime, long wall, String friend, Long countdown) {
        Checkpoint c = new Checkpoint(); c.frameId = id; c.sessionId = session; c.windowId = window;
        c.sequence = sequence; c.uptime = uptime; c.friend = "";
        if (countdown == null || countdown < 0 || wall < 0 || wall > Long.MAX_VALUE - countdown) return c;
        c.friend = "好友"; c.maturity = wall + countdown; c.count = 2;
        return c;
    }

    interface Transaction { boolean commit(); }
    /** A failed Android commit may still mutate the in-memory preferences map. */
    static final class Durability {
        boolean dirty;
        boolean commit(Transaction transaction) {
            boolean ok;
            try { ok = transaction.commit(); } catch (RuntimeException e) { ok = false; }
            dirty = !ok; return ok;
        }
    }
}
