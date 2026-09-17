package io.github.sixzleo.tabfold.projection;

/** Launcher-style folder preview geometry: a shrunk 2x2 grid of app icons, at most four. */
final class FolderFan {
    private FolderFan(){}
    static int shown(int total){return Math.max(0,Math.min(4,total));}
    static int iconSize(int container){return Math.round(container*0.45f);}
    /** Top-left corner of slot index inside a square container; index is not range-checked. */
    static int[] origin(int index,int container){
        int icon=iconSize(container),gap=Math.round(container*0.04f);
        int offset=(container-(2*icon+gap))/2;
        return new int[]{offset+(index%2)*(icon+gap),offset+(index/2)*(icon+gap)};
    }
    /**
     * MiDuo-style merge hot zone, in tile-local coordinates: horizontally centered on the
     * tile, anchored to its top, wide/tall enough to cover most of the icon itself.
     */
    static boolean mergeZone(float x,float y,int tileWidth,int tileHeight,int iconPixels){
        float zoneWidth=Math.min(tileWidth*0.82f,1.35f*iconPixels);
        float zoneHeight=Math.min(tileHeight*0.78f,1.18f*iconPixels);
        return Math.abs(x-tileWidth/2f)<=zoneWidth/2&&y<=zoneHeight;
    }
}
