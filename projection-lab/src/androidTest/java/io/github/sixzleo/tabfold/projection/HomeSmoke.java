package io.github.sixzleo.tabfold.projection;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Default tests preserve settings. Opt-in navigation_smoke enables gestures only with pre-authorized HOME and accessibility. */
public final class HomeSmoke extends Instrumentation {
    private boolean clearRecents,capture,catalogOnly,recentsProbe,navigationSmoke,dataOnly,pagingOnly,widgetBindingOnly,widgetCancelOnly;
    private volatile Activity resumedHome;
    private String widgetProvider;
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof DuoHomeActivity)resumedHome=activity;}
    private static void check(boolean okay,String label){if(!okay)throw new AssertionError(label);}
    @Override public void runOnMainSync(Runnable action){
        Throwable[] failure={null};
        super.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Error)throw (Error)failure[0];
        if(failure[0] instanceof RuntimeException)throw (RuntimeException)failure[0];
        if(failure[0]!=null)throw new RuntimeException(failure[0]);
    }
    @Override public void onCreate(Bundle args){
        super.onCreate(args);clearRecents=flag(args,"clear_recents");capture=flag(args,"capture");catalogOnly=flag(args,"catalog_only");recentsProbe=flag(args,"recents_probe");
        navigationSmoke=flag(args,"navigation_smoke");dataOnly=flag(args,"data_only");pagingOnly=flag(args,"paging_only");
        widgetBindingOnly=flag(args,"widget_binding_only");widgetCancelOnly=flag(args,"widget_cancel_only");
        widgetProvider=args==null?null:args.getString("widget_provider");start();
    }
    private static boolean flag(Bundle args,String name){return args!=null&&"true".equals(args.getString(name));}
    @Override public void onStart(){
        Bundle result=new Bundle();Activity home=null;
        try{
            Context target=getTargetContext();
            if(catalogOnly){result.putString("stream",HomePanelsSmoke.catalog(target)+"\n");finish(Activity.RESULT_OK,result);return;}
            if(!target.getSystemService(PowerManager.class).isInteractive()||target.getSystemService(KeyguardManager.class).isKeyguardLocked()){
                result.putString("stream","NEEDS_UNLOCK: unlock the phone before desktop/keyboard interaction tests. No UI tests performed.\n");
                finish(Activity.RESULT_CANCELED,result);return;
            }
            if(recentsProbe){result.putString("stream",RecentsWindowProbe.run(this));finish(Activity.RESULT_OK,result);return;}
            if(navigationSmoke){result.putString("stream",HomeNavigationSmoke.run(this,clearRecents));finish(Activity.RESULT_OK,result);return;}
            if(pagingOnly||widgetBindingOnly||widgetCancelOnly){
                getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                target.startActivity(new Intent(target,DuoHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                for(int i=0;i<100&&resumedHome==null;i++)SystemClock.sleep(100);
                Activity pageHome=resumedHome;check(pageHome!=null,"HOME resumed for paging test");
                for(int i=0;i<100;i++){
                    boolean[] ready={false};runOnMainSync(()->{HomePager pager=pageHome.getWindow().getDecorView().findViewWithTag("home-pager");ViewGroup grid=pageHome.getWindow().getDecorView().findViewWithTag("home-app-grid");ready[0]=pageHome.hasWindowFocus()&&grid!=null&&grid.getChildCount()>0&&pager!=null&&pager.getAdapter()!=null&&pager.getAdapter().getCount()>1;});
                    if(ready[0])break;SystemClock.sleep(100);
                }
                runOnMainSync(()->pageHome.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
                SystemClock.sleep(2000);waitForIdleSync();
                if(widgetBindingOnly||widgetCancelOnly){result.putString("stream",HomeWidgetBindingSmoke.run(this,(DuoHomeActivity)pageHome,widgetCancelOnly,widgetProvider));finish(Activity.RESULT_OK,result);return;}
                checkPanelSwitch(pageHome);
                String paging=HomePagingSmoke.run(this,pageHome);
                result.putString("stream","PASS: diagonal/horizontal paging, vertical routing, cancellation and stable sidebars; "+paging+"\n"+HomeCatalogRefreshSmoke.run(this,(DuoHomeActivity)pageHome));finish(Activity.RESULT_OK,result);return;
            }
            Context isolated=new ContextWrapper(target){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("test_home_"+name,mode);}
            };
            target.deleteSharedPreferences("test_home_duo_home");HomeStore store=new HomeStore(isolated);HomeLayout model=store.read();
            model.discover(Arrays.asList("a/A@0","b/B@0","c/C@10"));String first=model.items.get(0).id;
            model.merge(first,model.items.get(1).id);model.items.get(0).title="测试文件夹";model.pin("c/C@10");store.save(model);
            HomeLayout restored=store.read();check(restored.items.size()==2&&restored.items.get(0).folder(),"folder persistence");
            check(restored.items.get(0).title.equals("测试文件夹")&&restored.dock.get(0).equals("c/C@10"),"names and profile keys persist");
            restored.addWidget(101,0,4,2);store.save(restored);
            HomeLayout widgetRestored=store.read();HomeLayout.Item savedWidget=widgetRestored.items.get(0);
            check(savedWidget.widgetId==101&&savedWidget.spanX==4&&savedWidget.spanY==2,"workspace widget placement survives persistence");
            store.widgets(Arrays.asList(17,22));store.pendingWidget(23);store.widgetHeight(17,240);
            HomeStore reopened=new HomeStore(isolated);check(reopened.widgets().equals(Arrays.asList(17,22))&&reopened.pendingWidget()==23&&reopened.widgetHeight(17)==240,"widget transaction survives recreation");
            check(reopened.beginWidget(24,1),"widget transaction durably begins");
            HomeStore interrupted=new HomeStore(isolated);check(interrupted.pendingWidget()==24&&interrupted.pendingWidgetPage()==1,"pending ID and destination restored together");
            check(!interrupted.finishWidget(23),"stale result cannot complete a newer widget");
            check(interrupted.finishWidget(24,4,2),"widget completion commits");
            HomeStore completed=new HomeStore(isolated);check(completed.widgets().contains(24)&&completed.pendingWidget()==-1&&completed.pendingWidgetPage()==-1,"host ID and cleared pending state committed together");
            check(completed.read().items.stream().anyMatch(item->item.widgetId==24&&item.spanX==4&&item.spanY==2),"completed widget has persistent provider-sized page placement");
            check(!completed.finishWidget(24),"duplicate completion is ignored");
            check(completed.beginWidget(25,2)&&completed.cancelWidget(),"pending widget cancellation commits");
            HomeStore cancelled=new HomeStore(isolated);check(cancelled.pendingWidget()==-1&&cancelled.pendingWidgetPage()==-1&&!cancelled.finishWidget(25),"cancelled widget cannot be completed after recreation");
            check(cancelled.widgets().equals(completed.widgets()),"cancel preserves existing hosted widgets");
            check(HomeWidgets.defaultSpan(4,120,100,4)==4,"provider grid preference wins");
            check(HomeWidgets.defaultSpan(0,197,100,4)==3,"legacy pixel size rounds up including margins");
            check(HomeWidgets.defaultSpan(0,0,100,4)==1&&HomeWidgets.defaultSpan(9,900,100,4)==4,"default widget span remains within grid");
            completed.reconcileWidgets(new HashSet<>(Arrays.asList(17,22)));
            HomeStore reconciled=new HomeStore(isolated);
            check(reconciled.widgets().equals(Arrays.asList(17,22)),"system-deleted widget registry entries removed");
            check(reconciled.read().items.stream().noneMatch(item->item.widgetId==24||item.widgetId==101),"orphan workspace placements removed together");
            check(reconciled.read().items.stream().anyMatch(item->item.folder()&&item.title.equals("测试文件夹")),"reconciliation preserves app folders");
            target.deleteSharedPreferences("test_home_duo_home");
            if(dataOnly){result.putString("stream","PASS: widget transaction completion, cancellation, stale results, recreation and provider default sizing.\n");finish(Activity.RESULT_OK,result);return;}
            progress("Persistence checks passed; loading real app catalog");
            List<HomeApps.App> apps=HomeApps.load(target);check(!apps.isEmpty(),"real launcher catalog");Set<String> keys=new HashSet<>();
            for(HomeApps.App app:apps)check(keys.add(app.key)&&app.icon.getWidth()>=96&&app.icon.getWidth()<=192,"unique component/profile and bounded icon bitmap");
            int providers=AppWidgetManager.getInstance(target).getInstalledProviders().size();
            progress("Catalog ready: "+apps.size()+" apps / "+providers+" widgets; opening settings entry");
            ActivityMonitor entryMonitor=addMonitor(DesktopActivity.class.getName(),null,false);
            ActivityMonitor monitor=addMonitor(DuoHomeActivity.class.getName(),null,false);
            try(ParcelFileDescriptor command=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "+target.getPackageName()+"/.DesktopActivity")){
                try(java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(command)){input.readAllBytes();}
            }
            Activity entry=waitForMonitorWithTimeout(entryMonitor,10000);removeMonitor(entryMonitor);
            check(entry!=null,"launcher settings entry starts with installed preview version and cached update state");
            waitForIdleSync();
            runOnMainSync(()->{
                UpdateCoordinator.get(target).hasUpdate(); // The previously crashing observer path.
                View preview=findText(entry.getWindow().getDecorView(),"打开桌面预览");
                check(preview!=null,"preview button reachable from real launcher entry");preview.performClick();
            });
            home=waitForMonitorWithTimeout(monitor,10000);removeMonitor(monitor);check(home!=null,"preview activity starts");
            final Activity activity=home;boolean ready=false;
            for(int attempt=0;attempt<100;attempt++){
                final boolean[] hasTiles={false};runOnMainSync(()->hasTiles[0]=hasGrid(activity.getWindow().getDecorView()));
                if(hasTiles[0]){ready=true;break;}SystemClock.sleep(100);
            }
            check(ready,"home renders real app grid");
            waitForIdleSync();
            runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            boolean focused=false;
            for(int attempt=0;attempt<50;attempt++){
                boolean[] current={false};runOnMainSync(()->current[0]=activity.hasWindowFocus()&&!target.getSystemService(KeyguardManager.class).isKeyguardLocked());
                if(current[0]){focused=true;break;}SystemClock.sleep(100);
            }
            check(focused,"preview must be focused and phone unlocked before visual interaction tests");
            runOnMainSync(()->checkChrome(activity));
            progress(HomePagingSmoke.run(this,activity));
            progress("Paging passed: touch swipe, cancellation, vertical gesture, edge, interruption, accessibility and stable sidebars");
            HomePanelsSmoke.run(this,(DuoHomeActivity)activity,apps,capture);
            progress("Panels passed: folder icons, catalog search, empty/clear states, real keyboard insets, single selection and stable desktop");
            progress(HomeWorkspaceSmoke.run(this,(DuoHomeActivity)activity,capture));
            runOnMainSync(()->{
                View edit=findText(activity.getWindow().getDecorView(),"编辑");check(edit!=null,"edit control visible");edit.performClick();
                check(findText(activity.getWindow().getDecorView(),"完成")!=null,"edit state toggles");
                findText(activity.getWindow().getDecorView(),"完成").performClick();
                View search=findDescription(activity.getWindow().getDecorView(),"搜索应用");check(search!=null,"search reachable");
            });
            waitForIdleSync();
            runOnMainSync(()->checkChrome(activity));
            runOnMainSync(()->{
                View dock=activity.getWindow().getDecorView().findViewWithTag("home-dock");
                progress("Dock background: "+dock.getBackground().getClass().getName()+"; padding="+dock.getPaddingBottom());
            });
            if(capture){
                SystemClock.sleep(1000);
                runOnMainSync(()->check(activity.hasWindowFocus(),"preview focused for visual capture"));
                android.graphics.Bitmap screenshot=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
                check(screenshot!=null,"preview screenshot available");
                try(java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(target.getCacheDir(),"home-smoke.png"))){
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
                }finally{screenshot.recycle();}
            }
            runOnMainSync(activity::finish);waitForIdleSync();
            runOnMainSync(()->{
                check(!entry.isDestroyed()&&findText(entry.getWindow().getDecorView(),"打开桌面预览")!=null,"settings survives return from preview");
                UpdateCoordinator.get(target).hasUpdate();
            });
            result.putString("stream","PASS: launcher settings entry, preview button, return to settings and update observer with installed preview version; desktop/folder/profile persistence, widget pending transaction, "+apps.size()+" real app entries, "+providers+" widget providers, native grid and edit controls, fully visible aligned columns and no duplicate header clock. Default HOME unchanged.\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("stream",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}
    }
    private void checkPanelSwitch(Activity activity){
        View[] retained=new View[2];boolean[] narrow={false};
        runOnMainSync(()->{View decor=activity.getWindow().getDecorView();retained[0]=decor.findViewWithTag("home-root");retained[1]=decor.findViewWithTag("home-dock");narrow[0]=decor.findViewWithTag("home-panel-switch")!=null;});
        if(!narrow[0])return;
        for(String label:new String[]{"小组件","应用"}){
            runOnMainSync(()->findText(retained[0].findViewWithTag("home-panel-switch"),label).performClick());
            boolean intermediate=false;
            for(int i=0;i<30;i++){
                boolean[] frame={false};runOnMainSync(()->{ViewGroup body=retained[0].findViewWithTag("home-body");float alpha=body.getChildAt(0).getAlpha();frame[0]=alpha>0&&alpha<1;});intermediate|=frame[0];SystemClock.sleep(16);
            }
            check(intermediate,"panel switch renders intermediate frames");
            runOnMainSync(()->{View decor=activity.getWindow().getDecorView();check(decor.findViewWithTag("home-root")==retained[0]&&decor.findViewWithTag("home-dock")==retained[1],"switch preserves root and Dock");ViewGroup body=decor.findViewWithTag("home-body");check(body.getChildAt(0).getAlpha()==1f&&body.getChildAt(0).getTranslationX()==0f,"panel settles fully visible");check(decor.findViewWithTag(label.equals("应用")?"home-pager":"home-widgets")!=null,"target panel exists");});
        }
        progress("PASS: panel switch animates both directions and preserves Dock/root");
    }
    private static void checkChrome(Activity activity){
        View decor=activity.getWindow().getDecorView();
        ViewGroup root=decor.findViewWithTag("home-root");
        ViewGroup header=decor.findViewWithTag("home-header");
        ViewGroup body=decor.findViewWithTag("home-body");
        check(root!=null&&header!=null&&body!=null&&body.getHeight()>0,"chrome laid out");
        for(int i=0;i<header.getChildCount();i++)check(!(header.getChildAt(i) instanceof TextClock),"header clock removed");
        check(body.getBottom()<=root.getHeight()-root.getPaddingBottom(),"body above bottom safe area");
        for(int i=0;i<body.getChildCount();i++){
            View column=body.getChildAt(i);Rect visible=new Rect();
            check(column.getTop()==body.getPaddingTop(),"column top aligned: "+i);
            check(column.getBottom()==body.getHeight()-body.getPaddingBottom(),"column bottom aligned: "+i);
            check(column.getLocalVisibleRect(visible)&&visible.equals(new Rect(0,0,column.getWidth(),column.getHeight())),"entire column visible: "+i);
        }
        ViewGroup dock=decor.findViewWithTag("home-dock-icons");
        if(findText(decor,"编辑")!=null){
            check(dock!=null&&dock.getChildCount()>0,"Dock populated with common applications");
            for(int i=0;i<dock.getChildCount();i++)check(dock.getChildAt(i) instanceof ImageView,"normal Dock contains only app icons");
        }
        if(body.getChildCount()==3){
            HomePager pager=decor.findViewWithTag("home-pager");
            for(int p=0;p<pager.getChildCount();p++){
                GridLayout grid=pager.getChildAt(p).findViewWithTag("home-app-grid");if(grid==null)continue;
                View viewport=(View)grid.getParent();
                check(grid.getColumnCount()==4,"right screen keeps four app columns");
                check(grid.getHeight()<=viewport.getHeight(),"four app rows fit without vertical scrolling");
                for(int i=0;i<grid.getChildCount();i++){
                    if(((HomeLayout.Cell)grid.getChildAt(i).getTag()).item.widget()){
                        check(grid.getChildAt(i).getBottom()<=viewport.getHeight(),"widget inside page");continue;
                    }
                    ViewGroup tile=(ViewGroup)grid.getChildAt(i);TextView name=(TextView)tile.getChildAt(1);
                    check(tile.getBottom()<=viewport.getHeight()&&name.getBottom()<=tile.getHeight()-tile.getPaddingBottom(),"last-row app name fully contained");
                }
            }
        }
    }
    private void progress(String value){Bundle b=new Bundle();b.putString("stream",value+"\n");sendStatus(1,b);}
    private static boolean hasGrid(View view){if(view instanceof GridLayout&&"home-app-grid".equals(view.getTag())&&((GridLayout)view).getChildCount()>0)return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasGrid(((ViewGroup)view).getChildAt(i)))return true;return false;}
    private static View findText(View view,String text){if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())||view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static View findDescription(View view,String text){if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findDescription(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
}
