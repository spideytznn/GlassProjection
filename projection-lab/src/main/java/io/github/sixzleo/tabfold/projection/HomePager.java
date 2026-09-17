package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.widget.Scroller;
import androidx.viewpager.widget.ViewPager;

/** Native paging with observable settling state for interaction and regression checks. */
final class HomePager extends ViewPager {
    private int scrollState=SCROLL_STATE_IDLE;
    private final int directionSlop;
    private float downX,downY;
    private int direction; // 0 undecided, 1 paging, 2 vertical or multi-touch
    private boolean touching;
    /** Same quintic ease-out the library uses, but settles clamp to 160-320ms instead of 600. */
    private static final Interpolator SETTLE=new Interpolator(){
        @Override public float getInterpolation(float t){float x=t-1;return x*x*x*x*x+1;}
    };
    HomePager(Context context){
        super(context);
        directionSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        try{
            java.lang.reflect.Field scroller=ViewPager.class.getDeclaredField("mScroller");
            scroller.setAccessible(true);
            scroller.set(this,new Scroller(context,SETTLE){
                @Override public void startScroll(int startX,int startY,int dx,int dy,int duration){
                    super.startScroll(startX,startY,dx,dy,Math.max(160,Math.min(320,Math.round(duration*0.55f))));
                }
            });
        }catch(ReflectiveOperationException ignored){}
        addOnPageChangeListener(new SimpleOnPageChangeListener(){
            @Override public void onPageScrollStateChanged(int state){scrollState=state;}
        });
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();direction=0;touching=true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN)direction=2;
        if(action==MotionEvent.ACTION_MOVE&&direction==0){
            float dx=Math.abs(event.getX()-downX),dy=Math.abs(event.getY()-downY);
            if(Math.max(dx,dy)>directionSlop)direction=dx>=dy*.85f?1:2;
        }
        // ViewPager rejects verticals once vertical travel exceeds its slop. Decide intent
        // before nested ScrollViews do, then keep the horizontal stream level. Native
        // paging, velocity, child horizontal scrolling and cancellation remain intact.
        MotionEvent routed=event;
        boolean commit=false;float dx=0;
        if(direction==1){
            dx=event.getX()-downX;
            if(action==MotionEvent.ACTION_UP&&Math.abs(dx)>=0.2f*getWidth()){
                // The library commits only past its half-page rule. Commit at 20% travel by
                // ending the drag ourselves (CANCEL settles nothing) and paging explicitly,
                // so the velocity tracker never sees a synthetic jump.
                routed=MotionEvent.obtain(event);routed.setAction(MotionEvent.ACTION_CANCEL);
                commit=true;
            }else{routed=MotionEvent.obtain(event);routed.setLocation(event.getX(),downY);}
        }
        try{
            boolean handled=super.dispatchTouchEvent(routed);
            if(commit)setCurrentItem(getCurrentItem()+(dx>0?-1:1),true);
            return handled;
        }
        finally{if(routed!=event)routed.recycle();if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){direction=0;touching=false;}}
    }
    boolean isInteracting(){return touching||scrollState!=SCROLL_STATE_IDLE;}
    @Override protected void onDetachedFromWindow(){touching=false;super.onDetachedFromWindow();}
    int getScrollState(){return scrollState;}
    @Override protected boolean canScroll(android.view.View view,boolean checkView,int dx,int x,int y){
        // Ellipsized application labels can report a horizontal text scroll range.
        // Only widget content owns nested scrolling; app tiles always belong to paging.
        if(view.getTag() instanceof HomeLayout.Cell&&!((HomeLayout.Cell)view.getTag()).item.widget())return false;
        return super.canScroll(view,checkView,dx,x,y);
    }
}
