package io.github.sixzleo.tabfold.projection;

/** Small, platform-free recognizer so edge decisions stay deterministic and testable. */
final class NavigationGestureGate {
    enum Action { NONE, BACK, HOME, RECENTS }
    enum Origin { LEFT, RIGHT, BOTTOM }
    private Origin origin;
    private float downX,downY,lastX,lastY;
    private long downAt;
    private boolean tracking,cancelled;

    void down(Origin value,float x,float y,long at){origin=value;downX=lastX=x;downY=lastY=y;downAt=at;tracking=true;cancelled=false;}
    void move(float x,float y){
        if(!tracking)return;lastX=x;lastY=y;
        float horizontal=origin==Origin.LEFT?x-downX:origin==Origin.RIGHT?downX-x:Math.abs(x-downX);
        float vertical=origin==Origin.BOTTOM?downY-y:Math.abs(y-downY);
        if(origin==Origin.BOTTOM&&horizontal>Math.max(30,vertical*1.25f))cancelled=true;
        if(origin!=Origin.BOTTOM&&vertical>Math.max(36,horizontal*1.35f))cancelled=true;
    }
    float progress(float threshold){
        if(!tracking||cancelled)return 0;
        float distance=origin==Origin.LEFT?lastX-downX:origin==Origin.RIGHT?downX-lastX:downY-lastY;
        return Math.max(0,Math.min(1,distance/threshold));
    }
    boolean recentsReady(long at,float threshold,long holdMs){return tracking&&!cancelled&&origin==Origin.BOTTOM&&progress(threshold)>=1&&at-downAt>=holdMs;}
    Action up(float x,float y,long at,float threshold,long holdMs){
        move(x,y);Action result=Action.NONE;
        if(!cancelled&&progress(threshold)>=1){
            if(origin==Origin.BOTTOM)result=at-downAt>=holdMs?Action.RECENTS:Action.HOME;
            else result=Action.BACK;
        }
        reset();return result;
    }
    void reset(){tracking=false;cancelled=false;}
}
