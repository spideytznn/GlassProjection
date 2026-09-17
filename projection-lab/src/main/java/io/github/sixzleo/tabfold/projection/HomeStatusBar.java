package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.widget.LinearLayout;

/**
 * Shade pull gesture over the native status bar: keeps the native bar visible
 * but takes over its touch. A pull past the threshold opens the shade pinned to
 * the finger (drag events track 1:1; release with velocity settles or springs
 * shut); a plain tap opens fully animated.
 */
interface ShadePullListener {
    /** The pull crossed the threshold (or a tap landed); fingerY is the touch's screen y. */
    void onPullFired(int side,float fingerY);
    /** Screen y of the ongoing gesture's finger. */
    void onPullDrag(float fingerY);
    /** Gesture ended; velocity in px/ms, down positive. Only after a fired pull. */
    void onPullRelease(float velocityPxPerMs);
}

final class HomeStatusBar extends LinearLayout {
    static final int SIDE_NOTIFICATIONS=0,SIDE_CONTROL=1;
    private final ShadePullListener pull;
    private float pullStart,pullSide;
    private boolean pullFired;
    private VelocityTracker velocity;
    HomeStatusBar(Context context,ShadePullListener pull){
        super(context);
        this.pull=pull;
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    pullStart=event.getY();pullSide=event.getX();pullFired=false;
                    velocity=VelocityTracker.obtain();velocity.addMovement(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(velocity!=null)velocity.addMovement(event);
                    if(!pullFired){
                        if(event.getY()-pullStart>dp(16))fire(event.getY());
                    }else pull.onPullDrag(event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float release=0;
                    if(velocity!=null){
                        velocity.addMovement(event);
                        velocity.computeCurrentVelocity(1000,20000);
                        release=velocity.getYVelocity()/1000f;
                        velocity.recycle();velocity=null;
                    }
                    if(pullFired)pull.onPullRelease(release);
                    else if(event.getActionMasked()==MotionEvent.ACTION_UP)fire(event.getY()); // tap
                    return true;
                default:return true;
            }
        });
        setClickable(true);setFocusable(true);
    }
    private void fire(float fingerY){
        pullFired=true;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        pull.onPullFired(pullSide<getWidth()/2f?SIDE_NOTIFICATIONS:SIDE_CONTROL,fingerY);
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
