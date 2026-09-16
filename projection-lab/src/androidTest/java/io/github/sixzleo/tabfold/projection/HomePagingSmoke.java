package io.github.sixzleo.tabfold.projection;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.viewpager.widget.ViewPager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Exercises touch routing and view identity, rather than just calling the page model. */
final class HomePagingSmoke {
    private final Instrumentation test;
    private final Activity activity;
    private HomePager pager;
    private View root,dock,widgets;
    private int original;
    private boolean intermediate;
    private final List<Long> frameTimes=new ArrayList<>();
    private int lateFrames;
    private final Window.OnFrameMetricsAvailableListener frameTiming=(window,frame,drop)->{
        if(frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME)!=0)return;
        long duration=frame.getMetric(FrameMetrics.TOTAL_DURATION),deadline=frame.getMetric(FrameMetrics.DEADLINE);
        if(duration>0){frameTimes.add(duration);if(deadline>0&&duration>deadline)lateFrames++;}
    };
    private HomePagingSmoke(Instrumentation test,Activity activity){this.test=test;this.activity=activity;}
    static String run(Instrumentation test,Activity activity){HomePagingSmoke smoke=new HomePagingSmoke(test,activity);smoke.run();return smoke.timing();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void run(){
        test.runOnMainSync(()->{
            View decor=activity.getWindow().getDecorView();pager=decor.findViewWithTag("home-pager");
            check(pager!=null&&pager.getAdapter().getCount()>1,"multiple real pages available");
            root=decor.findViewWithTag("home-root");dock=decor.findViewWithTag("home-dock");widgets=decor.findViewWithTag("home-widgets");
            original=pager.getCurrentItem();
        });
        ViewPager.SimpleOnPageChangeListener listener=new ViewPager.SimpleOnPageChangeListener(){
            @Override public void onPageScrolled(int page,float offset,int pixels){if(offset>.01f&&offset<.99f)intermediate=true;}
        };
        test.runOnMainSync(()->pager.addOnPageChangeListener(listener));
        test.runOnMainSync(()->activity.getWindow().addOnFrameMetricsAvailableListener(frameTiming,new Handler(Looper.getMainLooper())));
        try{
            final int[] target={0};
            test.runOnMainSync(()->{
                int direction=original<pager.getAdapter().getCount()-1?1:-1;target[0]=original+direction;
                View arrow=findDescription(root,direction>0?"下一页":"上一页");check(arrow!=null,"paging arrow exists");arrow.performClick();
            });
            settled(target[0]);
            // Return by dragging the visible application page.
            boolean forward=original>target[0];
            swipe(forward?.8f:.2f,forward?.2f:.8f,.02f,.02f,MotionEvent.ACTION_UP);
            settled(original);
            swipe(.5f,.46f,.02f,.02f,MotionEvent.ACTION_CANCEL);settled(original);
            swipe(.02f,.02f,.75f,.3f,MotionEvent.ACTION_UP);settled(original);
            test.runOnMainSync(()->HomePageMotion.select(pager,0,false));settled(0);
            // Slanted drags must page in both directions, including over application labels.
            swipe(.8f,.2f,.25f,.43f,MotionEvent.ACTION_UP);settled(1);
            swipe(.2f,.8f,.43f,.25f,MotionEvent.ACTION_UP);settled(0);
            swipe(.2f,.8f,.02f,.02f,MotionEvent.ACTION_UP);settled(0);
            test.runOnMainSync(()->check(pager.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null),"accessible forward action"));
            settled(1);
            test.runOnMainSync(()->HomePageMotion.select(pager,0,true));SystemClock.sleep(60);
            test.runOnMainSync(()->HomePageMotion.select(pager,1,false));settled(1);
            test.runOnMainSync(()->{
                check(intermediate,"pages rendered intermediate motion, not an instant replacement");
                test.callActivityOnNewIntent(activity,new Intent(activity,DuoHomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
                check(activity.getWindow().getDecorView().findViewWithTag("home-root")==root,"swipes retain root");
                check(root.findViewWithTag("home-dock")==dock,"swipes retain Dock and its blur background");
                check(root.findViewWithTag("home-widgets")==widgets,"swipes retain widget host tree");
            });
        }finally{
            test.runOnMainSync(()->{activity.getWindow().removeOnFrameMetricsAvailableListener(frameTiming);pager.removeOnPageChangeListener(listener);HomePageMotion.select(pager,original,false);});
        }
        settled(original);
        check(new HomeStore(activity).read().page==original,"original page restored in persisted layout");
    }
    private String timing(){
        if(frameTimes.isEmpty())return "No frame timing samples available";
        Collections.sort(frameTimes);
        return String.format(Locale.ROOT,"Paging FrameMetrics: %d frames, p50 %.2f ms, p90 %.2f ms, p95 %.2f ms, %d over reported deadline",
            frameTimes.size(),percentile(.5),percentile(.9),percentile(.95),lateFrames);
    }
    private double percentile(double value){return frameTimes.get(Math.min(frameTimes.size()-1,(int)Math.ceil(frameTimes.size()*value)-1))/1_000_000d;}
    private void settled(int page){
        for(int i=0;i<100;i++){
            boolean[] idle={false};test.runOnMainSync(()->idle[0]=pager.getCurrentItem()==page&&pager.getScrollState()==ViewPager.SCROLL_STATE_IDLE);
            if(idle[0]){test.waitForIdleSync();return;}SystemClock.sleep(30);
        }
        int[] actual={-1,-1};test.runOnMainSync(()->{actual[0]=pager.getCurrentItem();actual[1]=pager.getScrollState();});
        throw new AssertionError("pager did not settle on "+page+"; current="+actual[0]+", state="+actual[1]);
    }
    private void swipe(float fromX,float toX,float fromY,float toY,int ending){
        long down=SystemClock.uptimeMillis();
        for(int step=0;step<=18;step++){
            final int index=step;final float fraction=step/18f;
            test.runOnMainSync(()->{
                int action=index==0?MotionEvent.ACTION_DOWN:index==18?ending:MotionEvent.ACTION_MOVE;
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,
                    (fromX+(toX-fromX)*fraction)*pager.getWidth(),(fromY+(toY-fromY)*fraction)*pager.getHeight(),0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try{pager.dispatchTouchEvent(event);}finally{event.recycle();}
            });
            SystemClock.sleep(16);
        }
    }
    private static View findDescription(View view,String value){
        if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View found=findDescription(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;
        }
        return null;
    }
}
