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
