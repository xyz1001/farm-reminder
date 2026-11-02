package com.local.farmreminder;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

final class RoiBitmap {
    static Bitmap create(Bitmap source, RoiLayout layout) {
        Bitmap out = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        for (RoiLayout.Tile t : layout.tiles) {
            Rect src = new Rect(t.left, t.top, t.right, t.bottom);
            Rect dst = new Rect(RoiLayout.MARGIN, t.atlasTop, RoiLayout.MARGIN + src.width(), t.atlasTop + src.height());
            canvas.drawBitmap(source, src, dst, null);
        }
        return out;
    }
}
