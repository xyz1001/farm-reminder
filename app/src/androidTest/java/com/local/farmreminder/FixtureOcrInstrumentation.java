package com.local.farmreminder;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Headless fixture replay: no activity, UI automation or screenshot capture. */
public final class FixtureOcrInstrumentation extends Instrumentation {
    private Bundle args;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        TextRecognizer recognizer = null;
        Bitmap bitmap = null;
        try {
            Rect region = new Rect(Integer.parseInt(args.getString("left")), Integer.parseInt(args.getString("top")),
                    Integer.parseInt(args.getString("right")), Integer.parseInt(args.getString("bottom")));
            if (region.isEmpty()) throw new IllegalArgumentException("empty region");
            try (InputStream input = getContext().getAssets().open("farm-friend-readonly.png")) {
                bitmap = BitmapFactory.decodeStream(input);
            }
            if (bitmap == null) throw new IllegalStateException("invalid fixture");
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 12, TimeUnit.SECONDS);
            List<OcrLine> lines = new ArrayList<>();
            StringBuilder output = new StringBuilder();
            for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) continue;
                lines.add(new OcrLine(line.getText(), box.left, box.top, box.right, box.bottom));
                if (!region.contains(box)) continue;
                output.append("line ").append(box.flattenToString()).append(' ')
                        .append(redact(line.getText())).append('\n');
                for (Text.Element element : line.getElements()) {
                    Rect elementBox = element.getBoundingBox();
                    if (elementBox != null && region.contains(elementBox)) output.append("element ")
                            .append(elementBox.flattenToString()).append(' ').append(redact(element.getText())).append('\n');
                }
            }
            result.putString("output", output + "countdownMs=" + OcrFrameParser.countdown(lines));
            finish(0, result);
        } catch (Exception e) {
            result.putString("error", e.getClass().getName());
            finish(1, result);
        } finally {
            if (recognizer != null) recognizer.close();
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static String redact(String text) {
        return text.replaceAll("[^0-9:：后成熟\\s]", "?");
    }
}
