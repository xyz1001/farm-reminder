package com.local.farmreminder;

/** OCR text and its rectangle in the captured frame, in pixels. */
public final class OcrLine {
    public final String text;
    public final int left, top, right, bottom;
    public OcrLine(String text, int left, int top, int right, int bottom) {
        this.text = text == null ? "" : text; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
}
