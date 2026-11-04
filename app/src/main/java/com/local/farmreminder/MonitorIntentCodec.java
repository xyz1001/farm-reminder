package com.local.farmreminder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Strict, bounded independent format; unknown versions and corrupt input fail closed. */
final class MonitorIntentCodec {
    private static final int MAGIC = 0x4d494e54, VERSION = 1, MAX_BYTES = 32768;
    private MonitorIntentCodec() { }
    static byte[] encode(MonitorIntentState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeBoolean(state.enrolled); out.writeBoolean(state.wanted);
        out.writeLong(state.revision); out.writeLong(state.manualStartWall); out.writeUTF(state.sourceMode);
        out.writeUTF(state.suspension.name()); out.writeInt(state.acknowledged.size());
        for (MonitorIntentState.Exit e : state.acknowledged) {
            out.writeLong(e.timestamp); out.writeInt(e.pid); out.writeInt(e.reason); out.writeUTF(e.process);
        }
        out.flush(); byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_BYTES) throw new IOException("record too large");
        return encoded;
    }
    static MonitorIntentState decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("invalid record length");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("invalid schema");
            int enrolledByte = in.readUnsignedByte(), wantedByte = in.readUnsignedByte();
            if (enrolledByte > 1 || wantedByte > 1) throw new IOException("invalid boolean");
            long revision = in.readLong(), wall = in.readLong();
            String mode = in.readUTF(); MonitorIntentState.Suspension suspension = MonitorIntentState.Suspension.valueOf(in.readUTF());
            int count = in.readInt(); if (count < 0 || count > MonitorIntentState.MAX_HISTORY) throw new IOException("invalid count");
            List<MonitorIntentState.Exit> history = new ArrayList<>();
            for (int i = 0; i < count; i++) history.add(new MonitorIntentState.Exit(in.readLong(), in.readInt(), in.readInt(), in.readUTF()));
            if (in.available() != 0) throw new IOException("trailing data");
            return new MonitorIntentState(enrolledByte == 1, wantedByte == 1, revision, wall, mode, suspension, history);
        } catch (IllegalArgumentException e) { throw new IOException("invalid record", e); }
    }
}
