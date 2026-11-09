package com.local.farmreminder;

/** Visual-only validation of one known viewport layout; never evidence of identity or a friend scene.
 * The returned rectangles cover functional content, not the outer contour of a particular skin. */
final class HeaderLayout {
    static final class Bounds {
        final int left, top, right, bottom;
        Bounds(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
    }
    static final class Result {
        final Bounds portrait, plate;
        Result(Bounds portrait, Bounds plate) { this.portrait=portrait; this.plate=plate; }
    }
    private static final int SW=310;
    private HeaderLayout() { }

    static Result locate(int[] pixels, int width, int height) {
        if (pixels == null || width < 160 || height < width || (long) width*height > pixels.length) return null;
        if (blackColumn(pixels,width,height,0) || blackColumn(pixels,width,height,width-1)) return null;
        int top=0, bottom=height;
        while(top<height/5 && blackRow(pixels,width,top)) top++;
        while(bottom>top && height-bottom<height/5 && blackRow(pixels,width,bottom-1)) bottom--;
        int vh=bottom-top;
        if(vh<width || vh>width*3L) return null;
        int sh=(int)((long)vh*SW/width);
        if(sh<160) return null;
        // Only the bounded header and overlay-check regions are sampled.
        int[] p=new int[SW*160];
        for(int y=0;y<160;y++) for(int x=0;x<SW;x++)
            p[y*SW+x]=pixels[(top+(int)((long)y*vh/sh))*width+(int)((long)x*width/SW)];
        // An opaque dark overlay invalidates this layout; symmetric across RGB channels.
        long light=0;
        for(int y=15;y<30;y++) for(int x=100;x<200;x++) {
            int c=p[y*SW+x]; light+=(c>>16&255)+(c>>8&255)+(c&255);
        }
        if(light < 80L*3*1500) return null;

        // Search only small layout translations. Ranking favours the canonical slot;
        // unlike portrait-score ranking, a text patch cannot relocate the portrait.
        int bestDx=0,bestDy=0,bestDistance=Integer.MAX_VALUE;
        int[] offsets=new int[2048]; int count=0;
        for(int dy=-8;dy<=50;dy++) for(int dx=-3;dx<=15;dx++) {
            int distance=dx*dx+dy*dy;
            if(!header(p,dx,dy)) continue;
            offsets[count++]=(dy+8)*32+(dx+3);
            if(distance<bestDistance) { bestDistance=distance; bestDx=dx; bestDy=dy; }
        }
        if(bestDistance==Integer.MAX_VALUE) return null;
        for(int i=0;i<count;i++) {
            int dy=offsets[i]/32-8,dx=offsets[i]%32-3;
            if(Math.abs(dx-bestDx)>10 || Math.abs(dy-bestDy)>12) return null;
        }
        Bounds portrait=map(11+bestDx,41+bestDy,50+bestDx,79+bestDy,width,vh,sh,top);
        Bounds plate=map(3+bestDx,33+bestDy,115+bestDx,83+bestDy,width,vh,sh,top);
        return new Result(portrait,plate);
    }
    private static boolean header(int[] p,int dx,int dy) {
        if(!horizontal(p,53+dx,105+dx,41+dy,42+dy)
                || !horizontal(p,53+dx,105+dx,76+dy,77+dy)
                || !vertical(p,109+dx,110+dx,49+dy,71+dy)) return false;
        if(edges(p,50+dx,46+dy,102+dx,57+dy)<25
                || edges(p,50+dx,60+dy,105+dx,71+dy)<35) return false;
        int cx=30+dx,cy=60+dy,r=19;
        int sectors=0;
        for(int sector=0;sector<8;sector++) {
            int supported=0;
            for(int j=0;j<8;j++) {
                double angle=(sector*8+j)*Math.PI/32;
                double co=Math.cos(angle),si=Math.sin(angle);
                boolean found=false;
                for(int off=-2;off<=2;off++) {
                    int x1=(int)Math.round(cx+co*(r+off-1)),y1=(int)Math.round(cy+si*(r+off-1));
                    int x2=(int)Math.round(cx+co*(r+off+1)),y2=(int)Math.round(cy+si*(r+off+1));
                    if(diff(p[y1*SW+x1],p[y2*SW+x2])>=55) { found=true; break; }
                }
                if(found) supported++;
            }
            if(supported>=3) sectors++;
        }
        return sectors>=7;
    }
    private static boolean horizontal(int[] p,int l,int r,int from,int to) {
        for(int y=from;y<=to;y++) { int support=0;
            for(int x=l;x<r;x++) if(diff(p[(y-1)*SW+x],p[(y+1)*SW+x])>=50) support++;
            if(support*4 >= (r-l)*3) return true;
        } return false;
    }
    private static boolean vertical(int[] p,int from,int to,int t,int b) {
        for(int x=from;x<=to;x++) { int support=0;
            for(int y=t;y<b;y++) if(diff(p[y*SW+x-1],p[y*SW+x+1])>=50) support++;
            if(support*4 >= (b-t)*3) return true;
        } return false;
    }
    private static int edges(int[] p,int l,int t,int r,int b) {
        int n=0; for(int y=t;y<b;y++) for(int x=l;x<r;x++)
            if(diff(p[y*SW+x],p[y*SW+x+1])>=35 || diff(p[y*SW+x],p[(y+1)*SW+x])>=35) n++;
        return n;
    }
    private static int diff(int a,int b) {
        return Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));
    }
    private static Bounds map(int l,int t,int r,int b,int w,int vh,int sh,int top) {
        return new Bounds((int)Math.floor(l*(double)w/SW),top+(int)Math.floor(t*(double)vh/sh),
                (int)Math.ceil(r*(double)w/SW),top+(int)Math.ceil(b*(double)vh/sh));
    }
    private static boolean blackRow(int[] p,int w,int y) {
        for(int x=0;x<w;x++) if((p[y*w+x]&0xffffff)!=0) return false; return true;
    }
    private static boolean blackColumn(int[] p,int w,int h,int x) {
        for(int y=0;y<h;y++) if((p[y*w+x]&0xffffff)!=0) return false; return true;
    }
}
