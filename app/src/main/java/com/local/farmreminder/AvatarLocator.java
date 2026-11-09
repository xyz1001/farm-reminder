package com.local.farmreminder;

import java.util.List;

/** Optional portrait crop; visual geometry never establishes friend identity. */
final class AvatarLocator {
    static final class Bounds {
        final int left, top, right, bottom;
        Bounds(int left, int top, int right, int bottom) {
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
    }
    private AvatarLocator() { }

    static Bounds locate(int[] p,int w,int h,List<OcrLine> lines) {
        if(p==null || lines==null || w<160 || h<w || (long)w*h>p.length) return null;
        int top=0,bottom=h;
        while(top<h/5 && blackRow(p,w,top)) top++;
        while(bottom>top && h-bottom<h/5 && blackRow(p,w,bottom-1)) bottom--;
        int vh=bottom-top;
        if(vh<w || vh>w*3L) return null;
        boolean home=false;
        for(OcrLine l:lines) if(l!=null && l.left>=0 && l.top>=0 && l.right>l.left && l.bottom>l.top
                && l.right<=w && l.bottom<=h && "回家".equals(l.text.trim()) && l.left>=w*.75
                && l.top>=top+vh*.70 && l.bottom<=top+vh*.90
                && l.right-l.left<=w*.16 && l.bottom-l.top<=w*.10) home=true;
        if(!home) return null;
        // Friend-scene anchors are separate from header skin validation and retain their existing limitations.
        if(warmFraction(p,w,h,w/20,top+(int)(vh*.865),w*19/20,top+(int)(vh*.94),8)<.35
                || brightness(p,w,w/3,top+w/30,w*2/3,top+w/12,8)<105) return null;
        return locateVisual(p,w,h);
    }
    static Bounds locateVisual(int[] p,int w,int h) {
        HeaderLayout.Result result=HeaderLayout.locate(p,w,h);
        if(result==null) return null;
        HeaderLayout.Bounds b=result.portrait;
        return new Bounds(b.left,b.top,b.right,b.bottom);
    }
    private static boolean blackRow(int[] p,int w,int y) {
        for(int x=0;x<w;x++) if((p[y*w+x]&0xffffff)!=0) return false; return true;
    }
    private static int luma(int c) { return (((c>>16)&255)*3+((c>>8)&255)*6+(c&255))/10; }
    private static boolean warm(int c) {
        int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
        return r>85 && r*100>g*104 && g*100>b*112 && r*10>b*13;
    }
    private static double warmFraction(int[] p,int w,int h,int l,int t,int r,int b,int step) {
        if(l<0 || t<0 || r>w || b>h || l>=r || t>=b) return 0;
        int n=0,m=0; for(int y=t;y<b;y+=step) for(int x=l;x<r;x+=step) { n++; if(warm(p[y*w+x])) m++; }
        return n==0?0:(double)m/n;
    }
    private static int brightness(int[] p,int w,int l,int t,int r,int b,int step) {
        int n=0; long sum=0; for(int y=t;y<b;y+=step) for(int x=l;x<r;x+=step) { n++; sum+=luma(p[y*w+x]); }
        return n==0?0:(int)(sum/n);
    }
}
