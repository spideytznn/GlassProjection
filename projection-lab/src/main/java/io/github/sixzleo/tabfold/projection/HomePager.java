package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
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
    private VelocityTracker tracker;
    /** Finger velocity (px/s) at the last UP; consumed by the settle it launches. A tween
     * that starts from zero velocity right after a moving release reads as a hitch, so the
     * settle interpolator is an exponential decay whose initial slope matches it. */
    private float releaseVelocity;
    private int pageCount=1;
    /** Position curve for the settle. With velocity it is the closed form of a decaying
     *  velocity (x=D(1-e^-st)/(1-e^-s), initial slope matched to the release); without
     *  it a smooth ease-in-out — iOS paging uses a critically damped spring (~0.25-0.3s
     *  per page) and never a linear tween, which reads as sluggish. */
    private static final class Friction implements Interpolator{
        private double s;
        /** Solve s/(1-e^-s)=K (Newton from s=K); K<=1 degenerates to the ease curve. */
        void slope(float K){
            if(K<=1f){s=0;return;}
            double x=K;
            for(int i=0;i<4;i++){double e=Math.exp(-x),d=1-e;
                x-=(x/d-K)*d*d/(d-x*e);}
            s=Math.max(0,x);
        }
        @Override public float getInterpolation(float t){
            if(s<.01)return t*t*(3-2*t);
            return (float)((1-Math.exp(-s*t))/(1-Math.exp(-s)));
        }
    }
    private final Friction friction=new Friction();
    private final class PhysicsScroller extends Scroller{
        PhysicsScroller(){super(HomePager.this.getContext(),friction);}
        @Override public void startScroll(int startX,int startY,int dx,int dy,int duration){
            int distance=Math.abs(dx);
            float v0=Math.abs(releaseVelocity);
            // Continuity only makes sense when the settle runs with the release direction.
            boolean withVelocity=distance>0&&v0>=120&&releaseVelocity*dx>0;
            int dur;
            if(withVelocity){
                dur=Math.round(.9f*1000f*distance/v0);
                dur=Math.min(Math.max(dur,180),380);
                float K=v0*dur/(1000f*distance);
                if(K>=1f)friction.slope(K);
                else{friction.slope(0);dur=Math.max(180,Math.min(280,distance));}
            }else{friction.slope(0);dur=Math.max(180,Math.min(280,distance));}
            releaseVelocity=0;
            super.startScroll(startX,startY,dx,dy,dur);
        }
    }
    HomePager(Context context){
        super(context);
        directionSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        try{
            java.lang.reflect.Field scroller=ViewPager.class.getDeclaredField("mScroller");
            scroller.setAccessible(true);
            scroller.set(this,new PhysicsScroller());
        }catch(ReflectiveOperationException ignored){}
        // Parallax slide plus an edge fade: the incoming page trails the finger slightly
        // for a quiet depth cue, and through the last stretch of travel both pages
        // dissolve, so icons never show a hard cut line at the screen edge. Both the
        // translation and the alpha collapse to their rest values at position 0,
        // keeping the settled layout pixel exact.
        setPageTransformer(false,(page,position)->{
            page.setTranslationX(position>-1f&&position<1f?-.10f*position*page.getWidth():0f);
            float away=Math.abs(position);
            page.setAlpha(away<=.72f?1f:Math.max(0f,(1f-away)/.28f));
        });
        addOnPageChangeListener(new SimpleOnPageChangeListener(){
            @Override public void onPageScrollStateChanged(int state){scrollState=state;}
            @Override public void onPageScrolled(int position,float offset,int offsetPx){
                // Native launchers drift the wallpaper with paging; a static backdrop is
                // half of what makes a pager feel flat.
                if(pageCount>1)try{
                    android.app.WallpaperManager.getInstance(getContext())
                        .setWallpaperOffsets(getWindowToken(),(position+offset)/(pageCount-1),.5f);
                }catch(Exception ignored){}
            }
        });
    }
    void setPageCount(int count){pageCount=Math.max(1,count);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){tracker=VelocityTracker.obtain();downX=event.getX();downY=event.getY();direction=0;touching=true;}
        if(tracker!=null)tracker.addMovement(event);
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
            if(action==MotionEvent.ACTION_UP&&tracker!=null){
                tracker.computeCurrentVelocity(1000,8000);
                releaseVelocity=tracker.getXVelocity();
            }
            boolean handled=super.dispatchTouchEvent(routed);
            if(commit)setCurrentItem(getCurrentItem()+(dx>0?-1:1),true);
            return handled;
        }
        finally{
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                direction=0;touching=false;
                if(tracker!=null){tracker.recycle();tracker=null;}
            }
            if(routed!=event)routed.recycle();
        }
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
