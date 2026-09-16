package io.github.sixzleo.tabfold.projection;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Temporarily moves an already-bound widget; never allocates or grants a widget ID. */
final class HomeWorkspaceSmoke {
    static String run(Instrumentation test,DuoHomeActivity home,boolean capture)throws Exception{
        HomeStore store=new HomeStore(home);Integer id=null;
        for(int candidate:store.widgets())if(AppWidgetManager.getInstance(home).getAppWidgetInfo(candidate)!=null){id=candidate;break;}
        if(id==null)return "SKIP mixed widget UI: no existing bound provider";
        Field field=DuoHomeActivity.class.getDeclaredField("layout");field.setAccessible(true);
        Method render=DuoHomeActivity.class.getDeclaredMethod("render");render.setAccessible(true);
        HomeLayout original=(HomeLayout)field.get(home),mixed=store.read();
        final int widgetId=id;mixed.items.removeIf(item->item.widgetId==widgetId);
        HomeLayout.Item widget=mixed.addWidget(id,0,2,2);mixed.page=0;
        try{
            for(int width:new int[]{2,4}){
                test.runOnMainSync(()->{try{widget.spanX=width;field.set(home,mixed);render.invoke(home);}catch(Exception e){throw new RuntimeException(e);}});
                SystemClock.sleep(500);test.waitForIdleSync();
                test.runOnMainSync(()->{
                    HomePager pager=home.getWindow().getDecorView().findViewWithTag("home-pager");
                    boolean found=false;
                    for(int p=0;p<pager.getChildCount();p++){
                        ViewGroup grid=pager.getChildAt(p).findViewWithTag("home-app-grid");if(grid==null)continue;
                        List<Rect> rects=new ArrayList<>();
                        for(int i=0;i<grid.getChildCount();i++){
                            View view=grid.getChildAt(i);Rect rect=new Rect(view.getLeft(),view.getTop(),view.getRight(),view.getBottom());
                            for(Rect other:rects)check(!Rect.intersects(rect,other),"mixed views overlap");rects.add(rect);
                            check(rect.left>=0&&rect.right<=grid.getWidth()&&rect.bottom<=((View)grid.getParent()).getHeight(),"mixed item clipped");
                            HomeLayout.Cell cell=(HomeLayout.Cell)view.getTag();
                            if(cell.item.widgetId==widgetId){found=true;check(((ViewGroup)view).getChildAt(0) instanceof AppWidgetHostView,"real provider host retained");}
                        }
                    }
                    check(found,"widget appears in application page");
                });
                if(capture){Bitmap bitmap=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();check(bitmap!=null,"mixed screenshot");
                    try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(home.getCacheDir(),"home-mixed-"+width+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
            }
        }finally{test.runOnMainSync(()->{try{field.set(home,original);store.save(original);render.invoke(home);}catch(Exception e){throw new RuntimeException(e);}});}
        return "PASS mixed widget UI: existing provider, 2x2 / 4x2 bounds, no view overlap; original layout restored";
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
