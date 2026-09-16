package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import java.util.function.Consumer;

/**
 * Invisible overlay strip over the native status bar: keeps the native bar visible
 * but takes over its touch. A pull-down (or tap) opens the home shade; while the
 * shade is showing, the strip turns into the shade's dark header covering the bar.
 */
final class HomeStatusBar extends LinearLayout {
    static final int SIDE_NOTIFICATIONS=0,SIDE_CONTROL=1;
    private final Consumer<Integer> openPanel;
    private float pullStart,pullSide;
    private boolean pullFired;
    HomeStatusBar(Context context,Consumer<Integer> openPanel){
        super(context);
        this.openPanel=openPanel;
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    pullStart=event.getY();pullSide=event.getX();pullFired=false;return true;
                case MotionEvent.ACTION_MOVE:
                    if(!pullFired&&event.getY()-pullStart>dp(16))fire();
                    return true;
                case MotionEvent.ACTION_UP:
                    if(!pullFired)fire();
                    return true;
                default:return true;
            }
        });
        setClickable(true);setFocusable(true);
    }
    private void fire(){
        pullFired=true;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        openPanel.accept(pullSide<getWidth()/2f?SIDE_NOTIFICATIONS:SIDE_CONTROL);
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
