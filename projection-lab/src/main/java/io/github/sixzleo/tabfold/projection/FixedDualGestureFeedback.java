package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.animation.ValueAnimator;

/** Uses the existing navigation recognizer and visual/haptic feedback on each physical output. */
final class FixedDualGestureFeedback extends View {
    private final NavigationGestureGate gate=new NavigationGestureGate();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final java.util.function.IntConsumer action;
    private final float density;
    private NavigationGestureGate.Origin origin;
    private boolean tracking,triggered;
    private float progress;
    private float touchY;
    private boolean swallowing;
    private ValueAnimator retract;
    private final Runnable hold=()->{if(tracking&&!triggered&&gate.recentsReady(SystemClock.uptimeMillis(),dp(62),430)){triggered=true;commit(NavigationGestureGate.Action.RECENTS);}};
    FixedDualGestureFeedback(Context context,int density,java.util.function.IntConsumer action){super(context);this.density=density/160f;this.action=action;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    private float dp(float n){return n*density;}
    boolean touch(MotionEvent event,boolean desktop){
        int type=event.getActionMasked();float x=event.getX(),y=event.getY();
        if(type==MotionEvent.ACTION_DOWN){
            cancel();touchY=y;origin=y>getHeight()-dp(28)?NavigationGestureGate.Origin.BOTTOM:y<dp(48)?null:!desktop&&x<dp(22)?NavigationGestureGate.Origin.LEFT:!desktop&&x>getWidth()-dp(22)?NavigationGestureGate.Origin.RIGHT:null;
            if(origin==null)return false;tracking=true;gate.down(origin,x,y,event.getEventTime());
            if(origin==NavigationGestureGate.Origin.BOTTOM)main.postDelayed(hold,430);return true;
        }
        if(swallowing){if(type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL)swallowing=false;return true;}
        if(!tracking)return false;
        if(type==MotionEvent.ACTION_POINTER_DOWN||type==MotionEvent.ACTION_CANCEL){finish();swallowing=type==MotionEvent.ACTION_POINTER_DOWN;return true;}
        float threshold=dp(origin==NavigationGestureGate.Origin.BOTTOM?62:54);
        if(type==MotionEvent.ACTION_MOVE){gate.move(x,y);touchY+=(y-touchY)*.35f;progress=gate.progress(threshold);invalidate();if(origin==NavigationGestureGate.Origin.BOTTOM)hold.run();}
        else if(type==MotionEvent.ACTION_UP){NavigationGestureGate.Action result=gate.up(x,y,event.getEventTime(),threshold,430);boolean already=triggered;finish();if(!already&&result!=NavigationGestureGate.Action.NONE)commit(result);}
        return true;
    }
    private void commit(NavigationGestureGate.Action result){
        action.accept(result==NavigationGestureGate.Action.BACK?KeyEvent.KEYCODE_BACK:result==NavigationGestureGate.Action.HOME?KeyEvent.KEYCODE_HOME:KeyEvent.KEYCODE_APP_SWITCH);
        boolean haptic=performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        android.util.Log.i("DuoGesture","ACTION "+result+" feedback="+haptic);
    }
    private void finish(){
        main.removeCallbacks(hold);gate.reset();tracking=false;triggered=false;
        if(retract!=null)retract.cancel();
        retract=ValueAnimator.ofFloat(progress,0);retract.setDuration(180);
        retract.setInterpolator(new android.view.animation.DecelerateInterpolator());
        retract.addUpdateListener(a->{progress=(Float)a.getAnimatedValue();invalidate();});retract.start();
    }
    void cancel(){if(retract!=null){retract.cancel();retract=null;}main.removeCallbacks(hold);gate.reset();tracking=false;swallowing=false;triggered=false;progress=0;invalidate();}
    @Override protected void onDetachedFromWindow(){cancel();super.onDetachedFromWindow();}
    @Override protected void onDraw(Canvas canvas){
        if(progress<=0)return;
        paint.setColor(Color.WHITE);
        if(origin==NavigationGestureGate.Origin.BOTTOM){paint.setStyle(Paint.Style.FILL);paint.setAlpha((int)(130+100*progress));float w=dp(92+28*progress),h=dp(4+2*progress),cy=getHeight()-dp(7+8*progress);canvas.drawRoundRect(getWidth()/2f-w/2,cy-h/2,getWidth()/2f+w/2,cy+h/2,h/2,h/2,paint);}
        else{
            boolean left=origin==NavigationGestureGate.Origin.LEFT;
            float extent=dp(38)*progress,half=dp(66+18*progress);
            float cy=Math.max(half,Math.min(getHeight()-half,touchY));
            canvas.save();if(!left){canvas.translate(getWidth(),0);canvas.scale(-1,1);}
            Path curve=new Path();curve.moveTo(-dp(2),cy-half);
            curve.cubicTo(0,cy-half*.48f,extent,cy-half*.48f,extent,cy);
            curve.cubicTo(extent,cy+half*.48f,0,cy+half*.48f,-dp(2),cy+half);curve.close();
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(92,94,98));paint.setAlpha((int)(235*Math.min(1,progress*3)));canvas.drawPath(curve,paint);
            float cx=extent*.52f,span=dp(5)*Math.min(1,progress*2);
            Path arrow=new Path();arrow.moveTo(cx+span*.5f,cy-span);arrow.lineTo(cx-span*.5f,cy);arrow.lineTo(cx+span*.5f,cy+span);
            paint.setColor(Color.WHITE);paint.setAlpha((int)(255*Math.min(1,progress*2)));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);canvas.drawPath(arrow,paint);canvas.restore();
        }
    }
}
