package io.github.sixzleo.tabfold.projection;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Exercises real dialog windows and IME insets without editing the user's layout. */
final class HomePanelsSmoke {
    private static void check(boolean okay,String label){if(!okay)throw new AssertionError(label);}
    static String catalog(Context context){
        long start=SystemClock.elapsedRealtime();List<HomeWidgetPicker.Entry> entries=HomeWidgetPicker.catalog(context);
        long elapsed=SystemClock.elapsedRealtime()-start;
        Set<String> expected=new HashSet<>(),actual=new HashSet<>();Set<HomeWidgetPicker.Icon> icons=Collections.newSetFromMap(new IdentityHashMap<>());
        for(android.os.UserHandle profile:context.getSystemService(android.os.UserManager.class).getUserProfiles()){
            try{for(AppWidgetProviderInfo provider:AppWidgetManager.getInstance(context).getInstalledProvidersForProfile(profile))expected.add(provider.provider+"@"+provider.getProfile());}
            catch(SecurityException unavailable){}
        }
        int labeled=0,bytes=0;
        for(HomeWidgetPicker.Entry entry:entries){
            check(actual.add(entry.provider.provider+"@"+entry.provider.getProfile()),"unique provider/profile");
            check(entry.icon!=null&&entry.icon.bitmap==null,"catalog does not eagerly decode provider icons");icons.add(entry.icon);
            if(!entry.application.equals(entry.provider.provider.getPackageName()))labeled++;
        }
        check(!entries.isEmpty()&&expected.equals(actual),"picker contains complete accessible standard widget catalog");
        check(labeled>0,"system application display names resolve");
        long iconStart=SystemClock.elapsedRealtime();
        for(HomeWidgetPicker.Icon icon:icons){Bitmap bitmap=HomeWidgetPicker.loadIcon(context,icon);
            check(bitmap.getWidth()<=128&&bitmap.getHeight()<=128,"bounded decoded icon");bytes+=bitmap.getAllocationByteCount();bitmap.recycle();}
        StringBuilder configured=new StringBuilder();int count=0;
        for(HomeWidgetPicker.Entry entry:entries)if(entry.provider.configure!=null&&count<24){
            configured.append("\nCONFIGURABLE ").append(entry.title).append(" ").append(entry.provider.provider.flattenToString()).append(" -> ").append(entry.provider.configure.flattenToString());count++;
        }
        return "PASS: widget catalog "+entries.size()+" providers, "+labeled+" with application names, metadata "+elapsed+" ms; "+icons.size()+" shared lazy icons all decode / "+bytes+" bytes in "+(SystemClock.elapsedRealtime()-iconStart)+" ms. No widget allocated or bound; UI not tested."+configured;
    }
    static void run(Instrumentation test,DuoHomeActivity home,List<HomeApps.App> apps,boolean capture)throws Exception{
        HomeSheet[] sheet={null};View[] original={null};String[] selected={null};int[] calls={0};
        test.runOnMainSync(()->original[0]=home.getWindow().getDecorView().findViewWithTag("home-root"));
        try{
            test.runOnMainSync(()->sheet[0]=home.folder(new HomeLayout.Item("panel-test","常用应用",
                Arrays.asList(apps.get(0).key,apps.get(1).key,apps.get(2).key,apps.get(3).key,apps.get(4).key))));
            settle(test);test.runOnMainSync(()->{
                bounds(sheet[0]);GridLayout grid=sheet[0].content.findViewWithTag("home-folder-grid");
                check(grid.getChildCount()==5,"folder contains all five real shortcuts");
                for(int i=0;i<5;i++)check(((ViewGroup)grid.getChildAt(i)).getChildAt(0) instanceof ImageView,"folder icon");
            });
            if(capture)capture(test,"home-folder.png");
            test.runOnMainSync(()->sheet[0].dismiss());
            test.runOnMainSync(()->sheet[0]=home.pickApp("搜索应用",(app,source)->{selected[0]=app.key;calls[0]++;}));
            settle(test);
            EditText query=sheet[0].content.findViewWithTag("home-search-query");
            ListView list=sheet[0].content.findViewWithTag("home-search-results");
            test.runOnMainSync(()->{
                bounds(sheet[0]);check(list.getCount()==apps.size(),"unfiltered app catalog");
                query.setText("__no_such_app_987654__");check(list.getCount()==0,"empty search");
                check(sheet[0].content.findViewWithTag("home-search-empty").getVisibility()==View.VISIBLE,"visible empty state");
                findDescription(sheet[0].content,"清空搜索").performClick();check(list.getCount()==apps.size(),"clear restores catalog");
                query.requestFocus();home.getSystemService(InputMethodManager.class).showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);
            });
            boolean visible=false;
            for(int i=0;i<50;i++){
                boolean[] found={false};test.runOnMainSync(()->{WindowInsets insets=query.getRootWindowInsets();found[0]=insets!=null&&insets.isVisible(WindowInsets.Type.ime());});
                if(found[0]){visible=true;break;}SystemClock.sleep(100);
            }
            check(visible,"real keyboard becomes visible");settle(test);
            test.runOnMainSync(()->{bounds(sheet[0]);check(list.getChildCount()>0&&list.getHeight()>=list.getChildAt(0).getHeight(),"one complete result stays visible above keyboard");});
            if(capture)capture(test,"home-search-keyboard.png");
            test.runOnMainSync(()->home.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(),0));
            settle(test);
            HomeApps.App unique=null;
            for(HomeApps.App app:apps){int count=0;for(HomeApps.App other:apps)if(other.search.contains(app.search))count++;if(count==1){unique=app;break;}}
            check(unique!=null,"unique query available");HomeApps.App chosen=unique;
            test.runOnMainSync(()->{query.setText(chosen.search);check(list.getCount()==1,"label/package query");query.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
                check(calls[0]==1&&chosen.key.equals(selected[0])&&!sheet[0].isShowing(),"keyboard search selects exactly once");});
            settle(test);
            test.runOnMainSync(()->sheet[0]=home.pickApp("搜索应用",(app,source)->{
                check(source instanceof ImageView&&source.isAttachedToWindow(),"live icon source for system launch");calls[0]++;
            }));
            settle(test);
            test.runOnMainSync(()->{ListView rows=sheet[0].content.findViewWithTag("home-search-results");rows.getChildAt(0).performClick();
                check(calls[0]==2&&!sheet[0].isShowing(),"touch result selects exactly once");
                check(original[0]==home.getWindow().getDecorView().findViewWithTag("home-root"),"panels preserve underlying desktop");});
            widgetPicker(test,home,capture);
        }finally{test.runOnMainSync(()->{if(sheet[0]!=null)sheet[0].dismiss();});}
    }
    private static void widgetPicker(Instrumentation test,DuoHomeActivity home,boolean capture)throws Exception{
        HomeWidgetPicker[] picker={null};AppWidgetProviderInfo[] chosen={null};int[] selections={0};
        List<Integer> before=new HomeStore(home).widgets();
        try{
            test.runOnMainSync(()->{picker[0]=new HomeWidgetPicker(home,provider->{chosen[0]=provider;selections[0]++;});picker[0].show();});
            ListView list=picker[0].sheet.content.findViewWithTag("home-widget-results");
            boolean ready=false;
            for(int i=0;i<100;i++){boolean[] loaded={false};test.runOnMainSync(()->loaded[0]=list.getCount()>0);if(loaded[0]){ready=true;break;}SystemClock.sleep(100);}
            check(ready,"async widget directory ready");settle(test);
            EditText query=picker[0].sheet.content.findViewWithTag("home-widget-query");
            HomeWidgetPicker.Entry[] entry={null};int[] total={0};
            test.runOnMainSync(()->{
                bounds(picker[0].sheet);total[0]=list.getCount();entry[0]=(HomeWidgetPicker.Entry)list.getItemAtPosition(0);
                query.setText(entry[0].application);check(list.getCount()>0,"application-name widget search");
                query.setText("__absent_widget_987654__");check(list.getCount()==0,"widget empty search");
                findDescription(picker[0].sheet.content,"清空搜索").performClick();check(list.getCount()==total[0],"widget clear restores catalog");
            });settle(test);
            if(capture)capture(test,"home-widget-picker.png");
            test.runOnMainSync(()->{query.requestFocus();home.getSystemService(InputMethodManager.class).showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);});
            boolean keyboard=false;
            for(int i=0;i<50;i++){
                boolean[] shown={false};test.runOnMainSync(()->shown[0]=query.getRootWindowInsets()!=null&&query.getRootWindowInsets().isVisible(WindowInsets.Type.ime()));
                if(shown[0]){keyboard=true;break;}SystemClock.sleep(100);
            }
            check(keyboard,"real keyboard in widget picker");settle(test);
            test.runOnMainSync(()->{bounds(picker[0].sheet);check(list.getChildCount()>0&&list.getHeight()>=list.getChildAt(0).getHeight(),"one complete widget remains above keyboard");});
            if(capture)capture(test,"home-widget-keyboard.png");
            test.runOnMainSync(()->home.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(),0));settle(test);
            test.runOnMainSync(()->{View row=list.getChildAt(0);check(row!=null,"widget row visible");list.performItemClick(row,0,0);
                check(chosen[0]==entry[0].provider&&selections[0]==1&&!picker[0].sheet.isShowing(),"selected provider identity and single callback");});
            check(before.equals(new HomeStore(home).widgets()),"browsing picker does not bind or remove widgets");
            test.runOnMainSync(()->{picker[0]=new HomeWidgetPicker(home,provider->{throw new AssertionError("canceled picker selected provider");});picker[0].show();picker[0].dismiss();});
            settle(test);test.runOnMainSync(()->check(!picker[0].sheet.isShowing(),"dismiss during load stays dismissed"));
        }finally{test.runOnMainSync(()->{if(picker[0]!=null)picker[0].dismiss();});}
    }
    private static void bounds(HomeSheet sheet){
        View panel=sheet.content;View parent=(View)panel.getParent();
        check(panel.getWidth()>0&&panel.getHeight()>0,"panel laid out");
        check(panel.getLeft()>=parent.getPaddingLeft()&&panel.getRight()<=parent.getWidth()-parent.getPaddingRight(),"panel horizontal safe area");
        check(panel.getTop()>=parent.getPaddingTop()&&panel.getBottom()<=parent.getHeight()-parent.getPaddingBottom(),"panel keyboard/system safe area");
    }
    private static View findDescription(View v,String text){
        if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View result=findDescription(((ViewGroup)v).getChildAt(i),text);if(result!=null)return result;}
        return null;
    }
    private static void settle(Instrumentation test){SystemClock.sleep(650);test.waitForIdleSync();}
    private static void capture(Instrumentation test,String name)throws Exception{
        Bitmap bitmap=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();check(bitmap!=null,"panel screenshot");
        try(OutputStream output=new FileOutputStream(new File(test.getTargetContext().getCacheDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}finally{bitmap.recycle();}
    }
}
