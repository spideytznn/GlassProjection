package io.github.sixzleo.tabfold.projection;

/** Provider limits use pixels; grid pitch includes the gap outside the hosted widget. */
final class HomeWidgetResize {
    private HomeWidgetResize(){}
    static boolean allows(int span,int current,boolean resizable,int defaultPixels,int minResize,int maxResize,float pitch,float gap){
        if(span<1||span>4)return false;
        if(span==current)return true; // Keep existing layouts usable across display changes.
        if(!resizable)return false;
        int minimum=minResize>0?Math.min(defaultPixels,minResize):defaultPixels;
        int maximum=maxResize>=defaultPixels&&maxResize>0?maxResize:Integer.MAX_VALUE;
        float pixels=pitch*span-gap;
        return pixels>=minimum&&pixels<=maximum;
    }
}
