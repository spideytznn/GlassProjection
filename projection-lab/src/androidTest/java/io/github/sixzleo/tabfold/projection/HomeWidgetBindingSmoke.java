package io.github.sixzleo.tabfold.projection;

import android.app.Instrumentation;
import android.appwidget.*;
import android.os.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;

/** Opt-in: allocate one temporary instance of an existing provider, then restore the layout. */
final class HomeWidgetBindingSmoke {
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    static String run(Instrumentation test,DuoHomeActivity home,boolean cancel,String requestedProvider)throws Exception{
        HomeStore store=new HomeStore(home,home.panelStore());check(store.pendingWidget()<0,"do not interrupt an existing widget addition");
        Field modelField=DuoHomeActivity.class.getDeclaredField("layout");modelField.setAccessible(true);
        Field widgetsField=DuoHomeActivity.class.getDeclaredField("widgets");widgetsField.setAccessible(true);
        Method render=DuoHomeActivity.class.getDeclaredMethod("render");render.setAccessible(true);
        HomeWidgets widgets=(HomeWidgets)widgetsField.get(home);
        check(widgets.ids.equals(store.widgets()),"widget test must use the active panel's registry");
        AppWidgetProviderInfo provider=null;
        if(requestedProvider!=null){
            for(AppWidgetProviderInfo candidate:widgets.manager.getInstalledProviders())if(candidate.provider.flattenToString().equals(requestedProvider)){provider=candidate;break;}
        }else for(int id:store.widgets()){AppWidgetProviderInfo candidate=widgets.manager.getAppWidgetInfo(id);if(candidate!=null&&candidate.configure==null){provider=candidate;break;}}
        check(provider!=null,"requested provider must exist, or an existing no-configuration provider must be available");
        HomeLayout original=store.read();List<Integer> originalIds=store.widgets();Set<Integer> allocated=new HashSet<>();for(int id:widgets.host.getAppWidgetIds())allocated.add(id);
        final AppWidgetProviderInfo selected=provider;int[] added={-1};
        Method bind=HomeWidgets.class.getDeclaredMethod("bind",AppWidgetProviderInfo.class,int.class);bind.setAccessible(true);
        try{
            test.runOnMainSync(()->{try{bind.invoke(widgets,selected,0);for(int id:widgets.host.getAppWidgetIds())if(!allocated.contains(id))added[0]=id;}catch(Exception e){throw new RuntimeException(e);}});
            check(added[0]>=0,"one temporary widget allocated");
            Bundle status=new Bundle();status.putString("stream","WIDGET_BIND_STARTED: temporary ID="+added[0]+"; configurable="+(selected.configure!=null)+"; waiting for normal system result (90 seconds).\n");test.sendStatus(0,status);
            for(int i=0;i<900&&store.pendingWidget()==added[0];i++)SystemClock.sleep(100);
            if(cancel){
                check(store.pendingWidget()==-1&&store.pendingWidgetPage()==-1,"system cancellation clears pending destination");
                check(store.widgets().equals(originalIds),"system cancellation preserves existing widgets");
                for(int id:widgets.host.getAppWidgetIds())check(id!=added[0],"system cancellation releases allocated ID");
                HomeLayout restored=store.read();check(restored.page==original.page&&restored.items.size()==original.items.size(),"system cancellation preserves page and item count");
                for(int i=0;i<original.items.size();i++)check(restored.items.get(i).id.equals(original.items.get(i).id),"system cancellation preserves ordering");
                return "PASS: real system binding cancellation releases allocation, clears pending state and preserves original layout";
            }
            check(store.widgets().contains(added[0])&&store.pendingWidget()==-1,"system binding result completed persistent addition");
            SystemClock.sleep(500);test.waitForIdleSync();
            test.runOnMainSync(()->{
                check(home.hasWindowFocus(),"returned to desktop after system authorization");
                check(contains(home.getWindow().getDecorView(),added[0]),"real new AppWidgetHostView appears in application page");
            });
            HomeLayout.Item item=store.read().items.stream().filter(value->value.widgetId==added[0]).findFirst().orElseThrow();
            check(item.spanX>=1&&item.spanX<=4&&item.spanY>=1&&item.spanY<=4,"new provider default size persisted");
            test.runOnMainSync(()->widgets.remove(added[0]));
            check(!store.widgets().contains(added[0])&&widgets.manager.getAppWidgetInfo(added[0])==null,"normal removal releases temporary binding");
            return "PASS: real widget allocation, system result, application-page host, persistent provider size and removal; original layout restored";
        }finally{
            test.runOnMainSync(()->{try{
                if(added[0]>=0){
                    if(widgets.ids.contains(added[0]))widgets.remove(added[0]);
                    else widgets.host.deleteAppWidgetId(added[0]);
                    if(store.pendingWidget()==added[0])store.cancelWidget();
                }
                widgets.ids.clear();widgets.ids.addAll(originalIds);
                store.widgets(originalIds);store.save(original);modelField.set(home,original);render.invoke(home);
            }catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    private static boolean contains(View view,int id){
        if(view instanceof AppWidgetHostView&&((AppWidgetHostView)view).getAppWidgetId()==id)return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),id))return true;
        return false;
    }
}
