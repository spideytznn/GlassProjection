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
}
