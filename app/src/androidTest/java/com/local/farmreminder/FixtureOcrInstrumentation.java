package com.local.farmreminder;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Headless asset replay only. No activity, UI automation or screenshot capture. */
public final class FixtureOcrInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        TextRecognizer recognizer = null;
        Bitmap source = null;
        int code = 0;
        try {
            String mode = args.getString("mode", "baseline");
            if (!mode.equals("baseline") && !mode.equals("roi")) throw new IllegalArgumentException("mode");
            String fixture = args.getString("fixture", "friend");
            if (!fixture.equals("friend") && !fixture.equals("current")) throw new IllegalArgumentException("fixture");
            try (InputStream input = getContext().getAssets().open("farm-" + fixture + "-readonly.png")) {
                source = BitmapFactory.decodeStream(input);
            }
            if (source == null) throw new IllegalStateException("invalid fixture");
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            RoiLayout layout = new RoiLayout(source.getWidth(), source.getHeight());
            Rect region = args.containsKey("left") && args.containsKey("top") && args.containsKey("right") && args.containsKey("bottom")
                    ? new Rect(Integer.parseInt(args.getString("left")), Integer.parseInt(args.getString("top")),
                    Integer.parseInt(args.getString("right")), Integer.parseInt(args.getString("bottom"))) : null;
            if (region != null && region.isEmpty()) throw new IllegalArgumentException("region");
            long[] pre = new long[5], ocr = new long[5], total = new long[5];
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 8; i++) { // one cold, two warmup, five measured
                long start = SystemClock.elapsedRealtimeNanos();
                Bitmap input = mode.equals("roi") ? RoiBitmap.create(source, layout) : source;
                try {
                    long ready = SystemClock.elapsedRealtimeNanos();
                    Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(input, 0)), 12, TimeUnit.SECONDS);
                    long done = SystemClock.elapsedRealtimeNanos();
                    List<OcrLine> lines = new ArrayList<>();
                    for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
                        Rect box = line.getBoundingBox();
                        if (box == null) continue;
                        OcrLine mapped = mode.equals("roi") ? layout.map(line.getText(), box.left, box.top, box.right, box.bottom)
                                : new OcrLine(line.getText(), box.left, box.top, box.right, box.bottom);
                        if (mapped != null) lines.add(mapped);
                        if (i == 7 && mapped != null && region != null && contains(region, mapped)) {
                            appendRedacted(output, "line", mapped);
                            for (Text.Element element : line.getElements()) {
                                Rect eb = element.getBoundingBox();
                                if (eb == null) continue;
                                OcrLine em = mode.equals("roi") ? layout.map(element.getText(), eb.left, eb.top, eb.right, eb.bottom)
                                        : new OcrLine(element.getText(), eb.left, eb.top, eb.right, eb.bottom);
                                if (em != null && contains(region, em)) appendRedacted(output, "element", em);
                            }
                        }
                    }
                    Long countdown = OcrFrameParser.countdown(lines);
                    boolean friend = OcrFrameParser.friend(lines, source.getWidth(), source.getHeight()) != null;
                    if (i == 0) output.append("coldMs=").append(ms(done-start)).append('\n');
                    if (i >= 3) {
                        int n = i-3; pre[n]=ready-start; ocr[n]=done-ready; total[n]=done-start;
                        output.append("run=").append(n).append(" countdownMs=").append(countdown)
                                .append(" friendConfirmed=").append(friend).append('\n');
                    }
                } finally { if (input != source) input.recycle(); }
            }
            output.append("mode=").append(mode).append(" fixture=").append(fixture)
                    .append(" pixels=").append(mode.equals("roi") ? (long)layout.width*layout.height : (long)source.getWidth()*source.getHeight())
                    .append(" preprocessMedianMs=").append(median(pre)).append(" ocrMedianMs=").append(median(ocr))
                    .append(" totalMedianMs=").append(median(total));
            result.putString("output", output.toString());
        } catch (Exception e) { code = 1; result.putString("error", e.getClass().getName()); }
        finally { if (recognizer != null) recognizer.close(); if (source != null) source.recycle(); }
        finish(code, result);
    }
    private static long ms(long nanos) { return nanos / 1_000_000; }
    private static long median(long[] values) { Arrays.sort(values); return ms(values[values.length/2]); }
    private static boolean contains(Rect region, OcrLine line) {
        return region.contains(line.left, line.top, line.right, line.bottom);
    }
    private static void appendRedacted(StringBuilder output, String label, OcrLine line) {
        output.append(label).append(' ').append(line.left).append(' ').append(line.top).append(' ')
                .append(line.right).append(' ').append(line.bottom).append(' ')
                .append(line.text.replaceAll("[^0-9:：后成熟\\s]", "?")).append('\n');
    }
}
