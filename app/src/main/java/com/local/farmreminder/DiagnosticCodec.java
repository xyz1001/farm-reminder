package com.local.farmreminder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Local sensitive diagnostics; never send this content to logs. */
final class DiagnosticCodec {
    static byte[] lines(List<OcrLine> lines) {
        StringBuilder out = new StringBuilder("text\tleft\ttop\tright\tbottom\n");
        for (OcrLine line : lines) out.append(escape(line.text)).append('\t').append(line.left).append('\t')
                .append(line.top).append('\t').append(line.right).append('\t').append(line.bottom).append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
    static byte[] result(DiskFrameStore.Frame frame, String friend, List<Long> countdowns,
                         ReminderStore.FrameOutcome outcome) throws IOException {
        Properties p = new Properties();
        p.setProperty("diagnosticSchema", "1"); p.setProperty("parserVersion", "1");
        p.setProperty("model", "MLKit Chinese 16.0.1"); p.setProperty("frameId", frame.id);
        p.setProperty("attempt", Integer.toString(frame.attempts));
        p.setProperty("selectedName", friend == null ? "" : friend);
        p.setProperty("countdownsMs", join(countdowns));
        p.setProperty("phase", outcome == null ? "OCR_COMPLETE" : "BUSINESS_COMPLETE");
        if (outcome != null) {
            p.setProperty("code", outcome.code == null ? "" : outcome.code);
            p.setProperty("committed", Boolean.toString(outcome.committed));
            p.setProperty("confirmed", Boolean.toString(outcome.confirmed));
            p.setProperty("added", Boolean.toString(outcome.added));
            p.setProperty("replay", Boolean.toString(outcome.replay));
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            for (Reminder reminder : outcome.reminders) ids.add(reminder.id);
            p.setProperty("reminderIds", join(ids));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        p.store(writer, "sensitive frame diagnostics"); writer.flush(); return out.toByteArray();
    }
    private static String join(List<?> values) {
        StringBuilder b = new StringBuilder();
        for (Object value : values) { if (b.length() > 0) b.append(','); b.append(value); }
        return b.toString();
    }
}
