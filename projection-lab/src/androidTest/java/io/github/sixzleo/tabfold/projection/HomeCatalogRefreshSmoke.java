package io.github.sixzleo.tabfold.projection;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;

/** Delivers an actual catalog snapshot while the page is under the user's finger. */
final class HomeCatalogRefreshSmoke {
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    static String run(Instrumentation test,DuoHomeActivity home)throws Exception{
        Method queue=DuoHomeActivity.class.getDeclaredMethod("queueCatalog",List.class);queue.setAccessible(true);
        Field apps=DuoHomeActivity.class.getDeclaredField("apps");apps.setAccessible(true);
        List<HomeApps.App> snapshot=new ArrayList<>(((Map<String,HomeApps.App>)apps.get(home)).values());
        HomePager[] pager={null};View[] root={null};int[] original={0};
        test.runOnMainSync(()->{root[0]=home.getWindow().getDecorView().findViewWithTag("home-root");pager[0]=root[0].findViewWithTag("home-pager");original[0]=pager[0].getCurrentItem();});
        boolean forward=original[0]<pager[0].getAdapter().getCount()-1;int target=original[0]+(forward?1:-1);
        long down=SystemClock.uptimeMillis();float start=forward?.8f:.2f,end=forward?.2f:.8f;
        try{
            touch(test,pager[0],down,MotionEvent.ACTION_DOWN,start);
            for(int i=1;i<=6;i++){SystemClock.sleep(16);touch(test,pager[0],down,MotionEvent.ACTION_MOVE,start+(end-start)*i/12f);}
            test.runOnMainSync(()->{try{queue.invoke(home,snapshot);}catch(Exception e){throw new RuntimeException(e);}});
            SystemClock.sleep(200);
            test.runOnMainSync(()->check(home.getWindow().getDecorView().findViewWithTag("home-root")==root[0],"catalog does not replace desktop during drag"));
            for(int i=7;i<=12;i++){SystemClock.sleep(16);touch(test,pager[0],down,MotionEvent.ACTION_MOVE,start+(end-start)*i/12f);}
            touch(test,pager[0],down,MotionEvent.ACTION_UP,end);
            for(int i=0;i<100;i++){
                boolean[] changed={false};test.runOnMainSync(()->changed[0]=home.getWindow().getDecorView().findViewWithTag("home-root")!=root[0]);
                if(changed[0])break;SystemClock.sleep(50);
            }
            test.runOnMainSync(()->{
                HomePager current=home.getWindow().getDecorView().findViewWithTag("home-pager");
                check(current!=pager[0],"queued catalog eventually displayed after settling");
                check(current.getCurrentItem()==target,"refresh preserves completed swipe destination");
                check(current.getAdapter().getCount()==pager[0].getAdapter().getCount(),"catalog refresh retains layout pages");
            });
            return "PASS: catalog arriving during drag defers rebuilding until settle, retains target page and widget tree during touch";
        }finally{
            touch(test,pager[0],down,MotionEvent.ACTION_CANCEL,end);
            test.runOnMainSync(()->{HomePager current=home.getWindow().getDecorView().findViewWithTag("home-pager");HomePageMotion.select(current,original[0],false);});
        }
    }
    private static void touch(Instrumentation test,HomePager pager,long down,int action,float x){
        test.runOnMainSync(()->{MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,pager.getWidth()*x,pager.getHeight()*.02f,0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{pager.dispatchTouchEvent(event);}finally{event.recycle();}});
    }
}
