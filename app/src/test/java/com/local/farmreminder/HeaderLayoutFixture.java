package com.local.farmreminder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Synthetic structural headers only; no screenshots, identities, or OCR-derived image data. */
final class HeaderLayoutFixture {
    static final int W=310,H=693, SKY=0xff52b8e1, SCENE=0xffc17d4d;
    static final int BLUE=0xff244ca8, PURPLE=0xff9129ad, GRAY=0xff454545;
    static int[] scene(int s,int band,int dx,int dy,int skin) {
        int w=W*s,h=H*s+2*band; int[] p=new int[w*h]; Arrays.fill(p,0xff000000);
        rect(p,w,0,band,w,band+H*s,SKY);
        rect(p,w,0,band+601*s,w,band+652*s,SCENE);
        header(p,w,s,band,dx,dy,skin);
        rect(p,w,(10+dx)*s,band+(90+dy)*s,(32+dx)*s,band+(110+dy)*s,0xffb85548);
        return p;
    }
    static void header(int[] p,int w,int s,int band,int dx,int dy,int skin) {
        rect(p,w,(30+dx)*s,band+(41+dy)*s,(110+dx)*s,band+(77+dy)*s,skin);
        int light=0xfff2f2f2;
        for(int x=53;x<91;x+=5) rect(p,w,(x+dx)*s,band+(47+dy)*s,(x+2+dx)*s,band+(56+dy)*s,light);
        for(int x=54;x<103;x+=5) rect(p,w,(x+dx)*s,band+(62+dy)*s,(x+2+dx)*s,band+(70+dy)*s,light);
        for(int y=41;y<=79;y++) for(int x=11;x<=49;x++) {
            int q=(x-30)*(x-30)+(y-60)*(y-60);
            if(q<=19*19) {
                int c=q>=16*16?skin:(((x/3+y/3)&1)==0?light:0xff303030);
                rect(p,w,(x+dx)*s,band+(y+dy)*s,(x+dx+1)*s,band+(y+dy+1)*s,c);
            }
        }
    }
    static void rect(int[] p,int w,int l,int t,int r,int b,int c) {
        for(int y=Math.max(0,t);y<Math.min(p.length/w,b);y++)
            for(int x=Math.max(0,l);x<Math.min(w,r);x++) p[y*w+x]=c;
    }
    static List<OcrLine> home(int s,int band) {
        return Collections.singletonList(new OcrLine("回家",265*s,band+550*s,290*s,band+560*s));
    }
    private HeaderLayoutFixture() { }
}
