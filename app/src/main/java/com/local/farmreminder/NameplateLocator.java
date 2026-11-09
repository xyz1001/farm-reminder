package com.local.farmreminder;

import java.util.List;

/** Visual-only functional header rectangle; it may include background. */
final class NameplateLocator {
    static final class Bounds {
        final int left, top, right, bottom;
        Bounds(int left,int top,int right,int bottom) { this.left=left;this.top=top;this.right=right;this.bottom=bottom; }
    }
    static Bounds locate(int[] p,int w,int h,List<OcrLine> lines) {
        return bounds(HeaderLayout.locate(p,w,h));
    }
    /** Geometry of the unpadded stored HeaderLayout plate, including mapping rounding. */
    static AvatarLocator.Bounds portraitInStoredPlate(int w, int h) {
        if (w <= 0 || h <= 0 || Math.abs((long) w * 50 - (long) h * 112) > 162L * 2) return null;
        int left = (int) Math.round(8.0 * w / 112), top = (int) Math.round(8.0 * h / 50);
        int right = left + (int) Math.round(39.0 * w / 112), bottom = top + (int) Math.round(38.0 * h / 50);
        if (right <= left || bottom <= top || right > w || bottom > h) return null;
        return new AvatarLocator.Bounds(left, top, right, bottom);
    }
    static Bounds locatePlate(int[] p,int w,int h,AvatarLocator.Bounds portrait) {
        if(portrait==null) return null;
        HeaderLayout.Result result=HeaderLayout.locate(p,w,h);
        if(result==null) return null;
        HeaderLayout.Bounds expected=result.portrait;
        int iw=Math.min(expected.right,portrait.right)-Math.max(expected.left,portrait.left);
        int ih=Math.min(expected.bottom,portrait.bottom)-Math.max(expected.top,portrait.top);
        if(iw<=0 || ih<=0 || (long)iw*ih*2 < (long)(expected.right-expected.left)*(expected.bottom-expected.top)) return null;
        return bounds(result);
    }
    private static Bounds bounds(HeaderLayout.Result result) {
        if(result==null) return null;
        HeaderLayout.Bounds b=result.plate;
        return new Bounds(b.left,b.top,b.right,b.bottom);
    }
    private NameplateLocator() { }
}
