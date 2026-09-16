package io.github.sixzleo.tabfold.projection;

import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native-layout migration over the accessibility tree, no root needed.
 *
 * SCAN (MIUI home foreground): swipe pages with dispatchGesture, open folders
 * and read their icons, then emit a compact line protocol to logcat:
 *   PAGE n / ICON label x y w h / DOCK label ... / FOLDER label ... / WIDGET label ...
 *   CONTENT folder :: app|app|app
 *
 * APPLY (our home default again): broadcast the same text in extra "layout".
 * Labels resolve through HomeApps, widgets match installed providers by label
 * and bind through our AppWidgetHost, then the HomeLayout is saved+rendered.
 */
final class HomeMigrator {
    private static final String TAG="DuoMigrate";
    /** Migration labels to installed-provider labels (CJK names have no separator). */
    private static final Map<String,String> ALIASES=Map.of("音乐","小米音乐");
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private HomeMigrator(){}

    static void scanAsync(ProjectionService service){worker.execute(()->scan(service));}
    static void applyAsync(ProjectionService service,String layout){worker.execute(()->apply(service,layout));}

    // ---------------- SCAN ----------------
    private static void scan(ProjectionService service){
        if(service==null){Log.i(TAG,"service unavailable");return;}
        StringBuilder out=new StringBuilder();
        Map<String,List<String>> folderContents=new LinkedHashMap<>();
        Map<String,Rect> pendingFolders=new LinkedHashMap<>();
        Set<String> previous=null;
        for(int page=0;page<10;page++){
            AccessibilityNodeInfo root=miuiRoot(service);
            if(root==null){Log.i(TAG,"NO_MIUI_HOME — 请切到 MIUI 桌面前台后再触发");return;}
            Rect screen=new Rect();root.getBoundsInScreen(screen);
            List<String> lines=new ArrayList<>();
            Map<String,Rect> folders=new LinkedHashMap<>();
            Set<String> signature=new HashSet<>();
            scanPage(root,screen,lines,folders,signature);
            if(signature.isEmpty()||signature.equals(previous))break; // wrapped to first page
            boolean empty=true;
            for(String line:lines)if(!line.startsWith("DOCK "))empty=false;
            if(empty)break; // a page with only the dock means the workspace is over
            previous=signature;
            pendingFolders.putAll(folders);
            out.append("PAGE ").append(page).append('\n');
            for(String line:lines)out.append(line).append('\n');
            swipeLeft(service,screen);
        }
        // Only after every page has been swiped do we open folders — an open folder
        // turns swipes into drags, which would rearrange the user's real desktop.
        if(!pendingFolders.isEmpty()){
            MobileHelper.svcSync("input keyevent 3"); // HOME: back to the first page
            sleep(1200);
        }
        for(Map.Entry<String,Rect> folder:pendingFolders.entrySet()){
            List<String> apps=readFolder(service,folder.getValue());
            Log.i(TAG,"FOLDER_READ "+folder.getKey()+" apps="+apps.size());
            if(!apps.isEmpty())folderContents.put(folder.getKey(),apps);
        }
        for(Map.Entry<String,List<String>> folder:folderContents.entrySet())
            out.append("CONTENT ").append(folder.getKey()).append(" :: ")
                .append(String.join("|",folder.getValue())).append('\n');
        Log.i(TAG,"SCAN_BEGIN");
        for(int start=0;start<out.length();start+=3000)
            Log.i(TAG,out.substring(start,Math.min(out.length(),start+3000)));
        Log.i(TAG,"SCAN_END");
    }
    private static AccessibilityNodeInfo miuiRoot(ProjectionService service){
        for(AccessibilityWindowInfo window:service.getWindows()){
            if(window==null)continue;
            AccessibilityNodeInfo root=window.getRoot();
            if(root!=null&&root.getPackageName()!=null
                &&"com.miui.home".equals(root.getPackageName().toString()))return root;
        }
        return null;
    }
    private static void scanPage(AccessibilityNodeInfo node,Rect screen,List<String> out,
        Map<String,Rect> folders,Set<String> signature){
        if(node==null)return;
        CharSequence desc=node.getContentDescription();
        String id=node.getViewIdResourceName();
        if(desc!=null&&id!=null){
            String label=desc.toString().split(",")[0].trim();
            Rect b=new Rect();node.getBoundsInScreen(b);
            if(id.endsWith("icon_icon")){
                out.add((b.top>screen.height()*0.78f?"DOCK ":"ICON ")+label+' '
                    +b.left+' '+b.top+' '+b.width()+' '+b.height());
                signature.add(label);
            }else if(id.endsWith("folder")){
                out.add("FOLDER "+label+' '+b.left+' '+b.top+' '+b.width()+' '+b.height());
                folders.put(label,b);
                signature.add("folder:"+label);
            }else if(id.endsWith("widget_container")){
                out.add("WIDGET "+label+' '+b.left+' '+b.top+' '+b.width()+' '+b.height());
                signature.add("widget:"+label);
            }
        }
        for(int i=0;i<node.getChildCount();i++)scanPage(node.getChild(i),screen,out,folders,signature);
    }
    /** Open the folder, collect every labeled icon-sized node inside, then close. */
    private static List<String> readFolder(ProjectionService service,Rect box){
        // MIUI big-folder preview thumbnails launch their apps directly — only the
        // title strip near the container bottom opens the folder itself.
        tapAt(service,Math.round(box.exactCenterX()),box.bottom-25);
        sleep(1200);
        AccessibilityNodeInfo root=miuiRoot(service);
        List<String> apps=new ArrayList<>();
        if(root!=null){
            collectIcons(root,apps,new HashSet<>());
            Log.i(TAG,"FOLDER_ROOT children="+root.getChildCount());
        }
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        sleep(800);
        return apps;
    }
    private static void collectIcons(AccessibilityNodeInfo node,List<String> out,Set<Rect> seen){
        if(node==null)return;
        CharSequence desc=node.getContentDescription();
        if(desc!=null&&desc.length()>0&&desc.length()<=12){
            // Inside an open folder the app cells carry no stable view id; a label on
            // a small square region below the status bar is a folder app.
            Rect b=new Rect();node.getBoundsInScreen(b);
            if(b.width()>=60&&b.width()<=320&&b.height()>=60&&b.height()<=320
                &&b.top>150&&seen.add(b))
                out.add(desc.toString().split(",")[0].trim());
        }
        for(int i=0;i<node.getChildCount();i++)collectIcons(node.getChild(i),out,seen);
    }
    private static void swipeLeft(ProjectionService service,Rect screen){
        float y=screen.height()*0.72f,half=screen.width()/2f;
        String out=MobileHelper.svcSync(String.format(Locale.US,
            "input swipe %d %d %d %d 260",
            Math.round(half+screen.width()*0.36f),Math.round(y),
            Math.round(half-screen.width()*0.36f),Math.round(y)));
        if(out.startsWith("ERROR")){ // a11y gesture fallback
            Path path=new Path();
            path.moveTo(half+screen.width()*0.36f,y);
            path.lineTo(half-screen.width()*0.36f,y);
            service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0,260)).build(),null,null);
        }
        sleep(800);
    }
    private static void tapAt(ProjectionService service,int x,int y){
        String out=MobileHelper.svcSync("input tap "+x+' '+y);
        if(out.startsWith("ERROR")){
            Path path=new Path();
            path.moveTo(x,y);
            service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0,60)).build(),null,null);
        }
        sleep(400);
    }
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}

    // ---------------- APPLY ----------------
    private static void apply(ProjectionService service,String text){
        if(service==null||text==null||text.trim().isEmpty()){Log.i(TAG,"APPLY nothing");return;}
        Map<String,HomeApps.App> byLabel=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(HomeApps.App app:HomeApps.load(service.getApplicationContext()))byLabel.put(app.label,app);
        HomeLayout layout=new HomeLayout();
        Map<String,HomeLayout.Item> folderItems=new LinkedHashMap<>();
        List<String[]> widgets=new ArrayList<>(); // label,page,spanX,spanY
        int page=0;
        for(String raw:text.split("\n")){
            String line=raw.trim();
            if(line.isEmpty())continue;
            String[] part=line.split(" ");
            switch(part[0]){
                case "PAGE":page=part.length>1?Integer.parseInt(part[1]):0;break;
                case "ICON":case "DOCK":{
                    HomeApps.App app=byLabel.get(part.length>5?part[1]:"");
                    if(app==null){Log.i(TAG,"APPLY unknown label "+(part.length>1?part[1]:"?"));break;}
                    if(part[0].equals("DOCK"))layout.pin(app.key);
                    else layout.items.add(new HomeLayout.Item(UUID.randomUUID().toString(),"",List.of(app.key)));
                    break;
                }
                case "FOLDER":{
                    HomeLayout.Item item=new HomeLayout.Item(UUID.randomUUID().toString(),part.length>5?part[1]:"文件夹",new ArrayList<>());
                    layout.items.add(item);
                    if(part.length>5)folderItems.put(part[1],item);
                    break;
                }
                case "WIDGET":{
                    if(part.length<6)break;
                    widgets.add(new String[]{part[1],String.valueOf(page),part[4],part[5]});
                    break;
                }
            }
        }
        for(String raw:text.split("\n")){
            String line=raw.trim();
            if(!line.startsWith("CONTENT "))continue;
            String[] split=line.substring(8).split(" :: ");
            if(split.length!=2)continue;
            HomeLayout.Item folder=folderItems.get(split[0].trim());
            if(folder==null)continue;
            for(String label:split[1].split("\\|")){
                HomeApps.App app=byLabel.get(label.trim());
                if(app!=null)folder.apps.add(app.key);
            }
            if(folder.apps.isEmpty())layout.items.remove(folder);
        }
        layout.items.removeIf(item->item.apps.isEmpty()&&item.widgetId<0);
        layout.clamp();
        new Handler(Looper.getMainLooper()).post(()->bindAndCommit(service,layout,widgets));
    }
    /** Widgets bind on the main thread through our host; spans derive from cell units. */
    private static void bindAndCommit(ProjectionService service,HomeLayout layout,List<String[]> widgets){
        DuoHomeActivity host=null;
        for(DuoHomeActivity activity:DuoHomeActivity.instancesSnapshot()){
            android.view.Display display=activity.getDisplay();
            if(display!=null&&display.getDisplayId()==0&&!activity.isDestroyed()){host=activity;break;}
        }
        if(host!=null&&!widgets.isEmpty()){
            HomeWidgets widgetsHost=host.homeWidgets();
            Map<String,android.appwidget.AppWidgetProviderInfo> providers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for(android.appwidget.AppWidgetProviderInfo info:widgetsHost.manager.getInstalledProviders()){
                String label=info.loadLabel(host.getPackageManager());
                providers.put(label,info);
                // System widgets speak several label dialects ("时钟" vs "小米时钟");
                // index a short form too so migration labels can find them.
                for(String token:label.split("[\\s·_-]+"))
                    if(token.length()>=2&&!providers.containsKey(token))providers.put(token,info);
            }
            if(!widgets.isEmpty()){
                StringBuilder list=new StringBuilder("PROVIDERS n="+providers.size());
                for(String label:providers.keySet())list.append(" [").append(label).append(']');
                Log.i(TAG,list.toString());
            }
            int unit=cellUnitPx(host);
            for(String[] widget:widgets){
                android.appwidget.AppWidgetProviderInfo info=providers.get(widget[0]);
                if(info==null)info=providers.get(ALIASES.getOrDefault(widget[0],widget[0]));
                if(info==null){Log.i(TAG,"APPLY no provider for "+widget[0]);continue;}
                int page=Math.max(0,Math.min(Integer.parseInt(widget[1]),layout.pages()-1));
                int id=widgetsHost.host.allocateAppWidgetId();
                Bundle options=new Bundle();
                options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
                boolean bound=false;
                try{bound=widgetsHost.manager.bindAppWidgetIdIfAllowed(id,info.getProfile(),info.provider,options);}
                catch(RuntimeException e){Log.i(TAG,"APPLY bind error "+widget[0]+" "+e);}
                if(!bound){
                    widgetsHost.host.deleteAppWidgetId(id);
                    // Silent binding is denied on HyperOS — fall into the standard flow,
                    // which opens the system "allow widget" confirmation for the user.
                    Log.i(TAG,"APPLY bind denied "+widget[0]+" — asking the user");
                    widgetsHost.addForMigration(info,page);
                    continue;
                }
                widgetsHost.ids.add(id);
                widgetsHost.store().widgets(widgetsHost.ids);
                int spanX=unit>0?Math.max(1,Math.min(4,Math.round(Float.parseFloat(widget[2])/unit))):2;
                int spanY=unit>0?Math.max(1,Math.min(4,Math.round(Float.parseFloat(widget[3])/unit))):2;
                layout.addWidget(id,page,spanX,spanY);
            }
        }
        layout.clamp();
        if(host!=null)host.applyMigratedLayout(layout);
        else{
            new HomeStore(service.getApplicationContext()).save(layout);
            Log.i(TAG,"APPLY saved to store (no live activity to render)");
        }
        Log.i(TAG,"APPLY committed pages="+layout.pages()+" items="+layout.items.size()
            +" dock="+layout.dock.size()+" widgets="+widgets.size());
    }
    /** Icon pitch in px on our grid: one dock icon width is a good unit approximation. */
    private static int cellUnitPx(DuoHomeActivity host){
        android.util.DisplayMetrics metrics=host.getResources().getDisplayMetrics();
        return Math.round(metrics.widthPixels/4f);
    }
}
