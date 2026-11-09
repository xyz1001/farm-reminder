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
import java.io.File;
import java.io.FileOutputStream;
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
        if ("pipeline-profile".equals(args.getString("mode"))) {
            PipelineProfileProbe.run(this);
            return;
        }
        if ("cache".equals(args.getString("mode"))) { runCache(); return; }
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

    /** Real cached frames only; never writes either production image store. */
    private void runCache() {
        Bundle result = new Bundle();
        StringBuilder output = new StringBuilder();
        int code = 0;
        TextRecognizer recognizer = null;
        try {
            File dir = new File(getTargetContext().getFilesDir(), "test-cache-replay/" + SystemClock.elapsedRealtimeNanos());
            if (!dir.mkdirs()) throw new IllegalStateException("output directory");
            output.append("outputDir=files/test-cache-replay/").append(dir.getName()).append('\n');
            output.append("badgeProbeEnabled=").append("true".equals(args.getString("badgeProbe"))).append('\n');
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            for (int i = 1; i <= 5; i++) {
                String id = String.format(java.util.Locale.ROOT, "sample-%02d", i);
                try { replayCache(id, dir, recognizer, output); }
                catch (Exception | OutOfMemoryError e) {
                    code = 1;
                    output.append(id).append(" errorClass=").append(e.getClass().getName()).append('\n');
                    if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); break; }
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            code = 1; output.append("errorClass=").append(e.getClass().getName()).append('\n');
        } finally { if (recognizer != null) recognizer.close(); }
        result.putString("output", output.toString());
        finish(code, result);
    }

    private void replayCache(String id, File dir, TextRecognizer recognizer, StringBuilder output) throws Exception {
        BitmapFactory.Options dimensions = new BitmapFactory.Options();
        dimensions.inJustDecodeBounds = true;
        try (InputStream stream = getContext().getAssets().open(id + ".webp")) {
            BitmapFactory.decodeStream(stream, null, dimensions);
        }
        if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0 || dimensions.outWidth > 100000 || dimensions.outHeight > 100000)
            throw new IllegalStateException("invalid asset");
        if ((long) dimensions.outWidth * dimensions.outHeight > 16_000_000) throw new IllegalStateException("full OCR pixel cap");
        int sample = 1;
        while (((dimensions.outWidth + (long) sample - 1) / sample) * ((dimensions.outHeight + (long) sample - 1) / sample) > NameplateAssetPolicy.WORK_PIXELS) sample *= 2;
        Bitmap bitmap;
        try (InputStream stream = getContext().getAssets().open(id + ".webp")) {
            bitmap = BitmapFactory.decodeStream(stream);
        }
        if (bitmap == null) throw new IllegalStateException("decode");
        Bitmap working = null;
        try {
            int originalW = bitmap.getWidth(), originalH = bitmap.getHeight();
            long start = SystemClock.elapsedRealtime();
            Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 12, TimeUnit.SECONDS);
            long elapsed = SystemClock.elapsedRealtime() - start;
            List<OcrLine> lines = new ArrayList<>();
            for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
                OcrLine parent = validLine(line.getText(), line.getBoundingBox(), originalW, originalH);
                if (parent == null) continue;
                lines.add(parent);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream stream = getContext().getAssets().open(id + ".webp")) {
                working = BitmapFactory.decodeStream(stream, null, options);
            }
            if (working == null || (long) working.getWidth() * working.getHeight() > NameplateAssetPolicy.WORK_PIXELS)
                throw new IllegalStateException("work pixels");
            int w = working.getWidth(), h = working.getHeight();
            List<OcrLine> mapped = new ArrayList<>();
            for (OcrLine line : lines) mapped.add(mapLine(line, originalW, originalH, w, h));
            long identityStart = SystemClock.elapsedRealtime();
            Text identityText = Tasks.await(recognizer.process(InputImage.fromBitmap(working, 0)), 12, TimeUnit.SECONDS);
            long identityElapsed = SystemClock.elapsedRealtime() - identityStart;
            List<NotificationIdentityParser.TextRow> rows = new ArrayList<>();
            List<OcrLine> identityLines = new ArrayList<>();
            for (Text.TextBlock block : identityText.getTextBlocks()) for (Text.Line line : block.getLines()) {
                OcrLine parent = validLine(line.getText(), line.getBoundingBox(), w, h);
                if (parent == null) continue;
                identityLines.add(parent);
                List<OcrLine> elements = new ArrayList<>();
                for (Text.Element element : line.getElements()) {
                    OcrLine child = validLine(element.getText(), element.getBoundingBox(), w, h);
                    if (child != null) elements.add(child);
                }
                rows.add(new NotificationIdentityParser.TextRow(parent, elements));
            }
            int[] pixels = new int[w * h]; working.getPixels(pixels, 0, w, 0, 0, w, h);
            AvatarLocator.Bounds portrait = AvatarLocator.locateVisual(pixels, w, h);
            AvatarLocator.Bounds strict = AvatarLocator.locate(pixels, w, h, identityLines);
            NameplateLocator.Bounds plate = NameplateLocator.locate(pixels, w, h, mapped);
            boolean completeBadge = strict != null && strict.left + 1.20 * (strict.right - strict.left) <= w
                    && strict.top + .88 * (strict.right - strict.left) <= h
                    && strict.left + .68 * (strict.right - strict.left) >= 0
                    && strict.top + .44 * (strict.right - strict.left) >= 0;
            String label = NotificationIdentityParser.parseRows(rows, strict, w, h, strict != null, completeBadge).text();
            java.util.regex.Matcher levelMatch = java.util.regex.Pattern.compile("\\(([0-9]+)级\\)$").matcher(label);
            boolean level = levelMatch.find();
            String levelValue = level ? levelMatch.group(1) : "none";
            String name = level ? label.substring(0, levelMatch.start()) : label;
            boolean nameAvailable = !name.isEmpty() && !name.equals("好友");
            Long countdown = OcrFrameParser.countdown(lines);
            if (portrait != null) saveCrop(working, portrait.left, portrait.top, portrait.right, portrait.bottom, new File(dir, id + "-portrait.png"), false);
            boolean plateStorable = plate != null && NameplateAssetPolicy.wide(plate.right - plate.left, plate.bottom - plate.top);
            if (plateStorable) saveCrop(working, plate.left, plate.top, plate.right, plate.bottom, new File(dir, id + "-plate.png"), true);
            output.append(id).append(" original=").append(dimensions.outWidth).append('x').append(dimensions.outHeight)
                    .append(" working=").append(w).append('x').append(h).append(" lines=").append(lines.size())
                    .append(" countdownPresent=").append(countdown != null).append(" countdownMs=").append(countdown == null ? "none" : countdown)
                    .append(" ocrMs=").append(elapsed).append(" identityOcrMs=").append(identityElapsed)
                    .append(" friendConfirmedIdentityOcr=").append(strict != null)
                    .append(" parserFriendAvailable=").append(OcrFrameParser.friend(lines, originalW, originalH) != null)
                    .append(" nameAvailable=").append(nameAvailable).append(" levelAvailable=").append(level).append(" level=").append(levelValue)
                    .append(" rectSpace=working visualPortrait=").append(portrait != null).append(" portraitRect=")
                    .append(portrait == null ? "none" : rect(portrait.left, portrait.top, portrait.right, portrait.bottom))
                    .append(" visualHeader=").append(plate != null).append(" plateStorable=").append(plateStorable).append(" plateRect=")
                    .append(plate == null ? "none" : rect(plate.left, plate.top, plate.right, plate.bottom)).append('\n');
            appendLevelDiagnostics(output, id, rows, strict, w, h, completeBadge);
            if ("true".equals(args.getString("badgeProbe")))
                probeBadge(output, id, working);
        } finally { if (working != null && working != bitmap) working.recycle(); bitmap.recycle(); }
    }

    /** Replays the complete production identity pipeline with an owned working bitmap. */
    private static void probeBadge(StringBuilder output, String id, Bitmap working) {
        Bitmap owned = working.copy(Bitmap.Config.ARGB_8888, false);
        if (owned == null) throw new IllegalStateException("bitmap copy");
        long start = SystemClock.elapsedRealtime();
        NotificationIdentityLabel label = NotificationIdentityOcr.readBitmap(owned, start + 2250);
        String text = label.text();
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\(([0-9]+)级\\)$").matcher(text);
        boolean level = match.find();
        output.append(id).append(" badgeProbe productionReadBitmap=true levelAvailable=").append(level)
                .append(" level=").append(level ? match.group(1) : "none")
                .append(" elapsedMs=").append(SystemClock.elapsedRealtime() - start).append('\n');
    }
    private static void appendLevelDiagnostics(StringBuilder output, String id,
            List<NotificationIdentityParser.TextRow> rows, AvatarLocator.Bounds avatar,
            int width, int height, boolean completeBadge) {
        // Test-only reproduction of parseRows' private Region formula; retain doubles (no rounding).
        double left = 0, top = 0, right = 0, bottom = 0;
        if (avatar != null) {
            double d = (double) avatar.right - avatar.left;
            left = avatar.left + .68 * d; top = avatar.top + .44 * d;
            right = avatar.left + 1.20 * d; bottom = avatar.top + .88 * d;
        }
        output.append(id).append(" diagnosticLevel badgeRect=")
                .append(avatar == null ? "none" : left + "," + top + "," + right + "," + bottom)
                .append(" strictAvatarRect=")
                .append(avatar == null ? "none" : rect(avatar.left, avatar.top, avatar.right, avatar.bottom))
                .append(" completeBadge=").append(completeBadge).append('\n');
        int rowCount = 0, elementCount = 0, omittedRows = 0, omittedElements = 0;
        for (NotificationIdentityParser.TextRow row : rows) {
            if (!headerIntersects(row.line, width, height)) continue;
            if (rowCount >= 20) { omittedRows++; continue; }
            int rowId = rowCount++;
            appendLevelLine(output, id, "parent", rowId, -1, row.line, avatar != null,
                    left, top, right, bottom);
            output.append(" children=").append(row.elements.size()).append('\n');
            for (int i = 0; i < row.elements.size(); i++) {
                OcrLine child = row.elements.get(i);
                if (!headerIntersects(child, width, height) || elementCount >= 80) { omittedElements++; continue; }
                elementCount++;
                appendLevelLine(output, id, "child", rowId, i, child, avatar != null,
                        left, top, right, bottom);
                output.append('\n');
            }
        }
        output.append(id).append(" diagnosticLevel omittedRows=").append(omittedRows)
                .append(" omittedElements=").append(omittedElements).append('\n');
    }

    private static boolean headerIntersects(OcrLine line, int width, int height) {
        return line.left < .40 * width && line.top < .16 * height && line.right > 0 && line.bottom > 0;
    }

    private static void appendLevelLine(StringBuilder output, String id, String kind, int row, int child,
            OcrLine line, boolean hasBadge, double left, double top, double right, double bottom) {
        output.append(id).append(" diagnosticLevel ").append(kind).append(" row=").append(row);
        if (child >= 0) output.append(" child=").append(child);
        output.append(" rect=").append(rect(line.left, line.top, line.right, line.bottom))
                .append(" whollyInBadge=").append(hasBadge && line.left >= left && line.top >= top
                        && line.right <= right && line.bottom <= bottom)
                .append(" text=").append(redactLevelText(line.text));
    }

    private static String redactLevelText(String text) {
        StringBuilder safe = new StringBuilder();
        int end = Math.min(text.length(), 256);
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            safe.append(c >= '0' && c <= '9' || c == '/' || c == ':' || c == '万' || c == '亿' || c == '级' ? c : '?');
        }
        if (text.length() > end) safe.append("...");
        return safe.toString();
    }

    // NameplateStore uses floor for left/top and ceil for right/bottom.
    private static OcrLine mapLine(OcrLine line, int ow, int oh, int w, int h) {
        return new OcrLine(line.text, (int) ((long) line.left * w / ow), (int) ((long) line.top * h / oh),
                (int) (((long) line.right * w + ow - 1) / ow), (int) (((long) line.bottom * h + oh - 1) / oh));
    }

    private static OcrLine validLine(String text, Rect box, int w, int h) {
        return box != null && box.left >= 0 && box.top >= 0 && box.right > box.left && box.bottom > box.top
                && box.right <= w && box.bottom <= h ? new OcrLine(text, box.left, box.top, box.right, box.bottom) : null;
    }
    private static String rect(int l, int t, int r, int b) { return l + "," + t + "," + r + "," + b; }
    private static void saveCrop(Bitmap source, int l, int t, int r, int b, File file, boolean fitPlate) throws Exception {
        Bitmap crop = Bitmap.createBitmap(source, l, t, r - l, b - t);
        Bitmap scaled = crop;
        if (fitPlate) {
            NameplateAssetPolicy.Size size = NameplateAssetPolicy.fit(crop.getWidth(), crop.getHeight(), NameplateAssetPolicy.MAX_EDGE,
                    NameplateAssetPolicy.MAX_EDGE, NameplateAssetPolicy.MAX_PIXELS);
            if (size == null || !NameplateAssetPolicy.stored(size.width, size.height)) throw new IllegalStateException("plate size");
            if (size.width != crop.getWidth() || size.height != crop.getHeight())
                scaled = Bitmap.createScaledBitmap(crop, size.width, size.height, true);
        }
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("png");
        } finally { if (scaled != crop && scaled != source) scaled.recycle(); if (crop != source) crop.recycle(); }
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
