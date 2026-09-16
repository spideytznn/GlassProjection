package io.github.sixzleo.tabfold.projection;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;

/** Three transparent accessibility-overlay strips implement compatible full-screen navigation. */
final class GestureNavigationOverlay {
    private static final long RECENTS_HOLD_MS=430;
    private final AccessibilityService service;
    private final WindowManager manager;
    private final Handler main=new Handler(Looper.getMainLooper());
    private EdgeView left,right;
    private BottomView bottom;
    private boolean shown;
    GestureNavigationOverlay(AccessibilityService service){this.service=service;manager=service.getSystemService(WindowManager.class);}
    private int dp(float value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    void update(){
        boolean available=!FixedDualSession.active()&&GestureNavigation.enabled(service)
            &&service.getSystemService(PowerManager.class).isInteractive()
            &&!service.getSystemService(KeyguardManager.class).isKeyguardLocked();
        if(available&&!shown)show();else if(!available&&shown)close();
        if(shown){int edgeVisibility=DuoHomeActivity.foreground?View.GONE:View.VISIBLE;left.setVisibility(edgeVisibility);right.setVisibility(edgeVisibility);}
    }
    void refresh(){if(shown){close();update();}else update();}
    private WindowManager.LayoutParams params(int width,int height,int gravity,String title){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        p.gravity=gravity;p.setFitInsetsTypes(0);p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;p.setTitle(title);return p;
    }
    private void show(){
        left=new EdgeView(true);right=new EdgeView(false);bottom=new BottomView();
        try{
            manager.addView(left,params(dp(22),-1,Gravity.LEFT|Gravity.TOP,"Glass gesture back left"));
            manager.addView(right,params(dp(22),-1,Gravity.RIGHT|Gravity.TOP,"Glass gesture back right"));
            manager.addView(bottom,params(-1,dp(40),Gravity.LEFT|Gravity.BOTTOM,"Glass gesture home"));shown=true;
        }catch(RuntimeException e){android.util.Log.e("GlassGestures","Unable to add gesture overlays",e);close();}
    }
    void close(){
        if(bottom!=null)bottom.cancel();
        View[] views={left,right,bottom};left=null;right=null;bottom=null;shown=false;
        for(View view:views)if(view!=null)try{manager.removeViewImmediate(view);}catch(RuntimeException ignored){}
    }
    private void act(NavigationGestureGate.Action action,View source){
        if(action==NavigationGestureGate.Action.BACK&&DuoHomeActivity.foreground)return;
        if(action==NavigationGestureGate.Action.BACK&&ProjectionService.recentsVisible())action=NavigationGestureGate.Action.HOME;
        if(action==NavigationGestureGate.Action.HOME)DuoHomeActivity.requestHomeEntrance();
        int global=action==NavigationGestureGate.Action.BACK?AccessibilityService.GLOBAL_ACTION_BACK:
            action==NavigationGestureGate.Action.HOME?AccessibilityService.GLOBAL_ACTION_HOME:AccessibilityService.GLOBAL_ACTION_RECENTS;
        boolean accepted=service.performGlobalAction(global);
        if(accepted){source.performHapticFeedback(HapticFeedbackConstants.CONFIRM);if(action==NavigationGestureGate.Action.RECENTS)ProjectionService.recentsRequested();}
        android.util.Log.i("GlassGestures","ACTION "+action+" accepted="+accepted);
    }
    private final class EdgeView extends View {
        private final boolean fromLeft;private final Paint paint=new Paint(3);private final NavigationGestureGate gate=new NavigationGestureGate();private float progress;
        EdgeView(boolean left){super(service);fromLeft=left;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);paint.setColor(0xd9ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2.2f));paint.setStrokeCap(Paint.Cap.ROUND);}
        @Override public boolean onTouchEvent(android.view.MotionEvent event){
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){gate.down(fromLeft?NavigationGestureGate.Origin.LEFT:NavigationGestureGate.Origin.RIGHT,event.getRawX(),event.getRawY(),event.getEventTime());progress=0;invalidate();return true;}
            if(action==MotionEvent.ACTION_MOVE){gate.move(event.getRawX(),event.getRawY());progress=gate.progress(dp(54));invalidate();return true;}
            if(action==MotionEvent.ACTION_UP){NavigationGestureGate.Action result=gate.up(event.getRawX(),event.getRawY(),event.getEventTime(),dp(54),RECENTS_HOLD_MS);progress=0;invalidate();if(result!=NavigationGestureGate.Action.NONE)act(result,this);return true;}
            if(action==MotionEvent.ACTION_CANCEL){gate.reset();progress=0;invalidate();return true;}return true;
        }
        @Override protected void onDraw(Canvas canvas){if(progress<=0)return;float x=fromLeft?dp(5):getWidth()-dp(5),y=getHeight()/2f,span=dp(7+5*progress);paint.setAlpha((int)(80+175*progress));Path p=new Path();p.moveTo(fromLeft?x+span:x-span,y-span);p.lineTo(x,y);p.lineTo(fromLeft?x+span:x-span,y+span);canvas.drawPath(p,paint);}
    }
    private final class BottomView extends View {
        private final Paint paint=new Paint(3);private final NavigationGestureGate gate=new NavigationGestureGate();private float progress;private boolean triggered;
        private final Runnable hold=()->{if(!triggered&&gate.recentsReady(SystemClock.uptimeMillis(),dp(62),RECENTS_HOLD_MS)){triggered=true;progress=1;invalidate();act(NavigationGestureGate.Action.RECENTS,this);}};
        BottomView(){super(service);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);paint.setColor(0xe6ffffff);paint.setStyle(Paint.Style.FILL);setLayerType(View.LAYER_TYPE_HARDWARE,null);}
        void cancel(){main.removeCallbacks(hold);gate.reset();triggered=false;progress=0;invalidate();}
        @Override protected void onDetachedFromWindow(){cancel();super.onDetachedFromWindow();}
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){triggered=false;gate.down(NavigationGestureGate.Origin.BOTTOM,event.getRawX(),event.getRawY(),event.getEventTime());main.removeCallbacks(hold);main.postDelayed(hold,RECENTS_HOLD_MS);progress=0;invalidate();return true;}
            if(action==MotionEvent.ACTION_MOVE){gate.move(event.getRawX(),event.getRawY());progress=gate.progress(dp(62));invalidate();if(!triggered&&gate.recentsReady(event.getEventTime(),dp(62),RECENTS_HOLD_MS))hold.run();return true;}
            if(action==MotionEvent.ACTION_UP){main.removeCallbacks(hold);NavigationGestureGate.Action result=gate.up(event.getRawX(),event.getRawY(),event.getEventTime(),dp(62),RECENTS_HOLD_MS);progress=0;invalidate();if(!triggered&&result!=NavigationGestureGate.Action.NONE)act(result,this);triggered=false;return true;}
            if(action==MotionEvent.ACTION_CANCEL){cancel();return true;}return true;
        }
        @Override protected void onDraw(Canvas canvas){if(progress<=0)return;float width=dp(92+28*progress),height=dp(4+2*progress);paint.setAlpha((int)(130+100*progress));float cx=getWidth()/2f,cy=getHeight()-dp(7+8*progress);canvas.drawRoundRect(cx-width/2,cy-height/2,cx+width/2,cy+height/2,height/2,height/2,paint);}
    }
}
