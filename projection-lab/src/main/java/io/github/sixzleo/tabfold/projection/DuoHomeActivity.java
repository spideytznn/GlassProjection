package io.github.sixzleo.tabfold.projection;

import android.app.*;
import android.app.role.RoleManager;
import android.appwidget.*;
import android.content.*;
import android.content.pm.LauncherApps;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.widget.*;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import java.util.*;
import java.util.concurrent.*;

/** Optional native HOME. Task switching, split-screen and freeform remain system-owned. */
public class DuoHomeActivity extends Activity {
    private static final int TEXT=HomeStyle.TEXT,MUTED=HomeStyle.MUTED,ACCENT=HomeStyle.ACCENT;
    static volatile boolean foreground;
    private static final Map<Integer,DuoHomeActivity> panelActivities=new HashMap<>();
    private static final List<DuoHomeActivity> homeInstances=new ArrayList<>();
    /** Re-attach status bars for resumed panels; called on accessibility connect or when mirrors stack. */
    static void refreshShadeBars(){for(DuoHomeActivity a:new ArrayList<>(homeInstances))if(a.shadeAlive())ProjectionService.updateShadeBar(a);}
    boolean shadeAlive(){return resumed&&!isDestroyed();}
    static List<DuoHomeActivity> instancesSnapshot(){return new ArrayList<>(homeInstances);}
    HomeStore store(){return store;}
    HomeWidgets homeWidgets(){return widgets;}
    /** Replace the whole layout after a migration import and redraw. */
    void applyMigratedLayout(HomeLayout imported){layout=imported;save();render();}
    static boolean barePanel(int displayId){DuoHomeActivity a=panelActivities.get(displayId);if(a==null||!a.resumed||!a.hasWindowFocus()||a.editing||a.showWidgets||a.dialogs.stream().anyMatch(Dialog::isShowing))return false;WindowInsets insets=a.getWindow().getDecorView().getRootWindowInsets();return insets==null||!insets.isVisible(WindowInsets.Type.ime());}

    private boolean resumed;
    private boolean touching;
    private List<HomeApps.App> pendingCatalog;
    private final Runnable deliverCatalog=this::applyPendingCatalog;
    private static volatile long entranceRequestedAt;
    private static volatile boolean returningFromRecents;
    private boolean entrancePending,windowEntranceComplete,entranceRunning,wasAway;
    private long panelEntranceRequestedAt;
    private final Runnable entranceFallback=()->{windowEntranceComplete=true;playPendingEntrance();};
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,HomeApps.App> apps=new LinkedHashMap<>();
    private HomeStore store;
    private HomeLayout layout;
    private HomeWidgets widgets;
    private boolean editing,showWidgets,loaded,loading,reloadPending,wide;
    private LinearLayout root;
    private HomePager pager;
    private ScrollView widgetScroll;
    private int widgetScrollY;
    private TextView loadLabel;
    private final List<Dialog> dialogs=new ArrayList<>();
    /** Debug hook so adb can drive the shade and the migration on any display. */
    private final BroadcastReceiver shadeDebug=new BroadcastReceiver(){
        public void onReceive(Context context,Intent intent){
            String action=intent.getAction();
            if(action==null)return;
            if(action.endsWith(".MIGRATE_SCAN")||action.endsWith(".MIGRATE_APPLY")){
                // Migration must run exactly once; a second bind flow cancels the
                // first one's pending widget and closes the user's confirm dialog.
                android.view.Display display=getDisplay();
                if(display==null||display.getDisplayId()!=0)return;
                if(action.endsWith(".MIGRATE_SCAN"))HomeMigrator.scanAsync(ProjectionService.instance);
                else HomeMigrator.applyAsync(ProjectionService.instance,intent.getStringExtra("layout"));
                return;
            }
            if(action.endsWith(".OPEN_SEARCH")){search();return;}
            if(action.endsWith(".OPEN_RECENTS")){
                android.view.Display display=getDisplay();
                if(display!=null&&display.getDisplayId()==intent.getIntExtra("display",-1))recentsSheet();
                return;
            }
            openShade(intent.getIntExtra("side",HomeStatusBar.SIDE_NOTIFICATIONS));
        }
    };
    private final List<Runnable> catalogObservers=new ArrayList<>();
    private final LauncherApps.Callback appChanges=new LauncherApps.Callback(){
        public void onPackageRemoved(String p,UserHandle u){reload();}
        public void onPackageAdded(String p,UserHandle u){reload();}
        public void onPackageChanged(String p,UserHandle u){reload();}
        public void onPackagesAvailable(String[] p,UserHandle u,boolean replacing){reload();}
        public void onPackagesUnavailable(String[] p,UserHandle u,boolean replacing){reload();}
    };
    boolean dualPanel(){return this instanceof DuoInnerActivity||this instanceof DuoCoverActivity;}
    /**
     * Sessions rebuild on fold changes; secondary homes left on stale virtual displays keep
     * those displays in "removing" forever, and MIUI then routes task restores and resumes
     * onto the dead displays (apps never show, recents feels stuck). Only clean up when NO
     * session exists: during startup the live set is incomplete and killing "strays" would
     * murder the just-launched secondary home and collapse the whole session.
     */
    static void validateSecondaryHomes(){
        if(FixedDualSession.active())return;
        for(DuoHomeActivity a:new ArrayList<>(homeInstances)){
            if(!(a instanceof DuoSecondaryActivity)||a.isDestroyed()||a.isFinishing())continue;
            android.view.Display display=a.getDisplay();
            android.util.Log.i("DuoFixed","Finishing orphaned secondary home on display "
                +(display==null?-1:display.getDisplayId())+" (no live session)");
            a.finish();
        }
    }
    String panelStore(){return this instanceof DuoInnerActivity?"duo_inner":this instanceof DuoCoverActivity?"duo_cover":"duo_home";}
    int widgetHostId(){return this instanceof DuoInnerActivity?2702:this instanceof DuoCoverActivity?2703:2701;}
    /** Display-name mapping shared by real secondary-home instances across panels. */
    static String storeForDisplay(String name){
        if("Duo inner content".equals(name))return "duo_inner";
        if("Duo cover content".equals(name))return "duo_cover";
        return "duo_external_"+Integer.toHexString(name.hashCode());
    }
    static int hostIdForDisplay(String name){
        if("Duo inner content".equals(name))return 2702;
        if("Duo cover content".equals(name))return 2703;
        return 10000+(name.hashCode()&0x3fffffff);
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private int widgetDp(int pixels){return Math.round(pixels/getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);homeInstances.add(this);if(dualPanel())panelActivities.put(getDisplay().getDisplayId(),this);store=new HomeStore(this,panelStore());layout=store.read();
        if(saved!=null){showWidgets=saved.getBoolean("widgets");editing=saved.getBoolean("editing");widgetScrollY=saved.getInt("scroll");}
        widgets=new HomeWidgets(this,store,()->{if(!isDestroyed()){
            layout=store.read();
            layout.items.removeIf(item->item.widget()&&!widgets.ids.contains(item.widgetId));layout.clamp();save();render();
        }});
        layout=store.read(); // Hosting startup removes IDs no longer allocated by the system.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView(); // HyperOS does not create DecorView in getInsetsController().
        WindowInsetsController bars=getWindow().getInsetsController();
        if(bars!=null)bars.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        getSystemService(LauncherApps.class).registerCallback(appChanges,main);
        IntentFilter shadeActions=new IntentFilter();
        shadeActions.addAction(getPackageName()+".OPEN_SHADE");
        shadeActions.addAction(getPackageName()+".OPEN_SEARCH");
        shadeActions.addAction(getPackageName()+".OPEN_RECENTS");
        shadeActions.addAction(getPackageName()+".MIGRATE_SCAN");
        shadeActions.addAction(getPackageName()+".MIGRATE_APPLY");
        registerReceiver(shadeDebug,shadeActions,Context.RECEIVER_EXPORTED);
        render();reload();
    }
    @Override protected void onStart(){super.onStart();widgets.start();}
    @Override protected void onResume(){super.onResume();if(wasAway){if(dualPanel())panelEntranceRequestedAt=SystemClock.uptimeMillis();else requestHomeEntrance();wasAway=false;}resumed=true;foreground=hasWindowFocus();ProjectionService.refreshHomeScope();animateEntranceIfRequested();
        ProjectionService.updateShadeBar(this);
        // Bottom-edge up-swipes belong to the system home gesture; arriving home
        // should fold an open shade away, mirroring the native shade behavior.
        android.view.Display resumedDisplay=getDisplay();
        if(resumedDisplay!=null)HomeControlPanel.closeIfOpen(resumedDisplay.getDisplayId());}
    @Override protected void onPause(){entrancePending=false;entranceRunning=false;if(root!=null){root.animate().cancel();root.setAlpha(1f);root.setScaleX(1f);root.setScaleY(1f);root.setTranslationY(0); }wasAway=true;touching=false;resumed=false;foreground=false;windowEntranceComplete=false;main.removeCallbacks(entranceFallback);ProjectionService.refreshHomeScope();
        save();super.onPause();}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN)touching=true;
        try{return super.dispatchTouchEvent(event);}
        finally{if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)touching=false;}
    }
    @Override protected void onStop(){widgets.stop();super.onStop();}
    @Override protected void onDestroy(){
        homeInstances.remove(this);
        // When the desktop itself dies, its display's touch strip must go too —
        // otherwise we hijack the native shade after the user leaves our home.
        ProjectionService.removeShadeBar(this);
        if(dualPanel())panelActivities.remove(getDisplay().getDisplayId(),this);
        widgets.close();
        try{unregisterReceiver(shadeDebug);}catch(IllegalArgumentException ignored){}
        getSystemService(LauncherApps.class).unregisterCallback(appChanges);loader.shutdownNow();main.removeCallbacksAndMessages(null);
        for(Dialog d:dialogs)if(d.isShowing())d.dismiss();dialogs.clear();super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);render();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(!dualPanel()&&FixedDualSession.active())FixedDualSession.nativeHomeRequested();
        for(Dialog d:dialogs)if(d.isShowing())d.dismiss();
        widgets.dismissPicker();
        boolean changed=editing||showWidgets;editing=false;showWidgets=false;if(changed)render();animateEntranceIfRequested();}
    @Override protected void onSaveInstanceState(Bundle state){save();state.putBoolean("widgets",showWidgets);state.putBoolean("editing",editing);
        state.putInt("scroll",widgetScroll==null?widgetScrollY:widgetScroll.getScrollY());super.onSaveInstanceState(state);}
    @Override public void onBackPressed(){
        if(editing||showWidgets){editing=false;showWidgets=false;render();}
        else if(!isDefaultHome())finish();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(widgets.result(request,result,data))return;
        if(request==4201){ProjectionService.refreshHomeScope();render();}
    }
    void openShade(int side){
        if(getDisplay()==null)return;
        HomeControlPanel.open(getDisplay().getDisplayId(),side==HomeStatusBar.SIDE_CONTROL);
    }
    private void reload(){
        if(isDestroyed()||loader.isShutdown())return;
        if(loading){reloadPending=true;return;}loading=true;
        loader.execute(()->{
            try{
                List<HomeApps.App> found=HomeApps.load(getApplicationContext());
                main.post(()->queueCatalog(found));
            }catch(RuntimeException e){main.post(()->{if(isDestroyed())return;loading=false;notifyCatalog();message("应用列表暂时无法加载，可在菜单中刷新");});}
        });
    }
    private void queueCatalog(List<HomeApps.App> found){
        if(isDestroyed())return;
        pendingCatalog=found;main.removeCallbacks(deliverCatalog);applyPendingCatalog();
    }
    private void applyPendingCatalog(){
        if(isDestroyed()||pendingCatalog==null)return;
        // Keep the page and native widget views alive for the complete touch/settle sequence.
        if(resumed&&(entrancePending||entranceRunning||panelChanging||touching||(pager!=null&&pager.isInteracting()))){main.postDelayed(deliverCatalog,80);return;}
        List<HomeApps.App> found=pendingCatalog;pendingCatalog=null;
        apps.clear();for(HomeApps.App app:found)apps.put(app.key,app);
        boolean fresh=layout.items.isEmpty();
        layout.discover(apps.keySet());
        // MiDuo-style first-launch classification: only on a virgin desktop, never over a
        // migrated or hand-arranged layout.
        if(fresh&&!layout.items.isEmpty()&&!store.classifiedSeeded()){HomeClassify.apply(layout,apps);store.markClassifiedSeeded();}
        if(!store.dockSeeded()){if(layout.dock.isEmpty())seedDock();if(!layout.dock.isEmpty())store.markDockSeeded();}
        loaded=true;loading=false;save();render();notifyCatalog();if(reloadPending){reloadPending=false;reload();}
    }
    private void notifyCatalog(){for(Runnable observer:new ArrayList<>(catalogObservers))observer.run();}
    private void save(){if(store!=null&&layout!=null)store.save(layout);}
    static void requestHomeEntrance(){entranceRequestedAt=SystemClock.uptimeMillis();}
    static void observeRecents(){returningFromRecents=true;}
    static void leaveRecentsForApplication(){returningFromRecents=false;}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);foreground=resumed&&focused;ProjectionService.refreshHomeScope();if(foreground)animateEntranceIfRequested();
        // The accessibility service may reconnect after this activity resumed;
        // retry installing the shade bar whenever focus returns.
        if(focused&&resumed)ProjectionService.updateShadeBar(this);}
    private void animateEntranceIfRequested(){
        // A real secondary HOME already participates in WindowManager's home
        // transition. Hiding its tree until that transition ends leaves a blank
        // wallpaper interval, then plays an unnecessary second entrance.
        if(this instanceof DuoSecondaryActivity){panelEntranceRequestedAt=0;return;}
        if(!hasWindowFocus())return;
        if(entrancePending){playPendingEntrance();return;}
        long requested=dualPanel()?panelEntranceRequestedAt:entranceRequestedAt;
        if((dualPanel()||!returningFromRecents)&&(requested==0||SystemClock.uptimeMillis()-requested>1200))return;
        if(dualPanel())panelEntranceRequestedAt=0;
        else{ returningFromRecents=false;entranceRequestedAt=0; }
        if(!android.animation.ValueAnimator.areAnimatorsEnabled())return;
        entrancePending=true;
        root.animate().cancel();root.setAlpha(0f);root.setScaleX(.965f);root.setScaleY(.965f);root.setTranslationY(dp(18));
        android.util.Log.i("DuoEntrance","armed; systemComplete="+windowEntranceComplete);
        if(windowEntranceComplete)playPendingEntrance();
        else {main.removeCallbacks(entranceFallback);main.postDelayed(entranceFallback,700);}
    }
    @Override public void onEnterAnimationComplete(){
        super.onEnterAnimationComplete();windowEntranceComplete=true;
        android.util.Log.i("DuoEntrance","system transition complete");
        playPendingEntrance();
    }
    private void playPendingEntrance(){
        if(!entrancePending||!resumed||!hasWindowFocus()||!windowEntranceComplete||root==null)return;
        entrancePending=false;entranceRunning=true;main.removeCallbacks(entranceFallback);
        // Wait for the system's app transition, then animate the current tree rather
        // than a tree that a late catalog update may detach during the transition.
        root.postOnAnimation(()->{
            if(!resumed||!hasWindowFocus()||isDestroyed()){entranceRunning=false;return;}
            LinearLayout target=root;
            android.util.Log.i("DuoEntrance","visible entrance start");
            target.animate().cancel();target.setAlpha(0f);target.setScaleX(.965f);target.setScaleY(.965f);target.setTranslationY(dp(18));
            target.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(260)
                .setInterpolator(new PathInterpolator(.2f,0f,0f,1f)).withEndAction(()->entranceRunning=false).start();
        });
    }
    private void seedDock(){
        Intent[] intents={new Intent(Intent.ACTION_DIAL),new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)};
        String[][] fallback={{"com.android.contacts","com.google.android.dialer"},{"com.android.browser","com.mi.globalbrowser","com.android.chrome"},
            {"com.android.mms","com.google.android.apps.messaging"},{"com.android.camera","com.google.android.GoogleCamera"}};
        for(int i=0;i<intents.length;i++){
            List<String> packages=new ArrayList<>();android.content.pm.ResolveInfo resolved=getPackageManager().resolveActivity(intents[i],0);
            if(resolved!=null&&resolved.activityInfo!=null)packages.add(resolved.activityInfo.packageName);packages.addAll(Arrays.asList(fallback[i]));
            boolean found=false;for(String pkg:packages){for(HomeApps.App app:apps.values())if(app.user.equals(android.os.Process.myUserHandle())&&app.component.getPackageName().equals(pkg)){
                layout.pin(app.key);found=true;break;}if(found)break;}
        }
    }
    private void render(){
        if(isDestroyed())return;
        panelChanging=false;
        pager=null; // Detached pagers must not write a stale selection during a rebuild.
        if(widgetScroll!=null)widgetScrollY=widgetScroll.getScrollY();widgetScroll=null;
        Rect bounds=getWindowManager().getCurrentWindowMetrics().getBounds();
        float density=getResources().getDisplayMetrics().density;
        wide=bounds.width()/density>=600;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setTag("home-root");
        root.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout content=new FrameLayout(this);
        content.addView(root,new FrameLayout.LayoutParams(-1,-1));
        if(editing)content.addView(removeOverlay());
        setContentView(content);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            // Task displays run full height and the gesture gate owns the bottom strip on
            // every panel while the dual session runs; keep the desktop content above it.
            int band=FixedDualSession.active()?Math.round(FixedDualGestureFeedback.GESTURE_BAND_DP*density):0;
            v.setPadding(bars.left+dp(wide?24:12),bars.top+dp(8),bars.right+dp(wide?24:12),bars.bottom+dp(12)+band);return insets;
        });
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
        header.setTag("home-header");
        if(!wide)header.addView(panelSwitch(showWidgets),new LinearLayout.LayoutParams(dp(142),dp(44)));
        header.addView(new View(this),new LinearLayout.LayoutParams(0,dp(48),1));
        if(!isDefaultHome()){TextView mode=text("桌面预览",12,TEXT);mode.setPadding(dp(12),0,dp(12),0);mode.setShadowLayer(dp(2),0,dp(1),0x88000000);header.addView(mode);}
        LinearLayout actions=row();actions.setGravity(Gravity.CENTER_VERTICAL);actions.setTag("home-actions");
        actions.addView(actionIcon(editing?"完成":"编辑",()->{editing=!editing;render();}),new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout.LayoutParams settingsSize=new LinearLayout.LayoutParams(dp(44),dp(44));settingsSize.leftMargin=dp(8);
        actions.addView(actionIcon("设置",this::menu),settingsSize);
        header.addView(actions,new LinearLayout.LayoutParams(-2,dp(48)));
        root.addView(header);
        // All three columns share one inset content rectangle. MATCH_PARENT plus top
        // margins could make weighted columns extend below the body's clipping edge.
        LinearLayout body=row();body.setTag("home-body");body.setBaselineAligned(false);body.setGravity(Gravity.TOP);
        body.setPadding(0,dp(8),0,dp(8));root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if(wide){View panel=widgetPanel();LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-1,.95f);wp.setMargins(0,0,dp(18),0);body.addView(panel,wp);}
        View workspace=!wide&&showWidgets?widgetPanel():workspace();
        body.addView(workspace,new LinearLayout.LayoutParams(0,-1,1.1f));
        LinearLayout.LayoutParams dockSize=new LinearLayout.LayoutParams(dp(68),-1);dockSize.setMargins(dp(wide?16:8),0,0,0);
        body.addView(dockColumn(),dockSize);
        root.requestApplyInsets();
    }
    /** Drag-to-remove target shown while an edit-mode drag is in flight (MiDuo-style drop zone). */
    private FrameLayout removeOverlay(){
        FrameLayout overlay=new FrameLayout(this);overlay.setTag("home-remove-layer");
        Button pill=button("拖到此处移除",()->{});
        pill.setTextColor(0xffffd9d6);
        pill.setBackground(glass(0x80c2504a,26));
        pill.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams size=new FrameLayout.LayoutParams(dp(196),dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        size.topMargin=dp(60);
        overlay.addView(pill,size);
        // The empty overlay passes ordinary touches through; it only watches drag events.
        overlay.setOnDragListener((v,event)->{
            if(!(event.getLocalState() instanceof String))return false;
            switch(event.getAction()){
                case DragEvent.ACTION_DRAG_STARTED:pill.setVisibility(View.VISIBLE);return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    boolean over=event.getX()>=pill.getLeft()&&event.getX()<=pill.getRight()&&event.getY()>=pill.getTop()&&event.getY()<=pill.getBottom();
                    pill.setBackground(glass(over?0xccd95550:0x80c2504a,26));return true;
                case DragEvent.ACTION_DROP:
                    if(event.getX()>=pill.getLeft()&&event.getX()<=pill.getRight()&&event.getY()>=pill.getTop()&&event.getY()<=pill.getBottom()){
                        HomeLayout.Item dropped=layout.find((String)event.getLocalState());
                        if(dropped!=null){
                            if(dropped.widget())widgets.remove(dropped.widgetId);else{layout.remove(dropped.id);message("已从桌面移除");}
                            save();main.post(this::render);
                        }
                    }
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:pill.setVisibility(View.INVISIBLE);pill.setBackground(glass(0x80c2504a,26));return true;
                default:return true;
            }
        });
        return overlay;
    }
    private View workspace(){
        LinearLayout panel=column();panel.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout title=row();title.setGravity(Gravity.CENTER_VERTICAL);
        if(editing){TextView label=text("长按拖动 · 中心合并为文件夹",15,MUTED);title.addView(label,new LinearLayout.LayoutParams(0,dp(32),1));}
        if(editing)title.addView(button("＋组件",()->{save();widgets.choose(layout.page);}),new LinearLayout.LayoutParams(dp(80),dp(44)));
        if(editing)panel.addView(title);
        HomePager pagesView=new HomePager(this);pager=pagesView;pagesView.setTag("home-pager");
        pagesView.setSaveFromParentEnabled(false);pagesView.setOffscreenPageLimit(1);
        pagesView.setPageMargin(dp(12));
        pagesView.setAdapter(new PagerAdapter(){
            @Override public int getCount(){return layout.pages();}
            @Override public boolean isViewFromObject(View view,Object item){return view==item;}
            @Override public Object instantiateItem(ViewGroup container,int position){
                View page=appPage(position);container.addView(page);return page;
            }
            @Override public void destroyItem(ViewGroup container,int position,Object item){container.removeView((View)item);}
        });
        pagesView.setCurrentItem(layout.page,false);panel.addView(pagesView,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout pages=row();pages.setGravity(Gravity.CENTER_VERTICAL);
        Button previous=button("‹",()->turn(-1));previous.setContentDescription("上一页");previous.setEnabled(layout.page>0);
        Button next=button("›",()->turn(1));next.setContentDescription("下一页");next.setEnabled(layout.page<layout.pages()-1);
        pageDrop(previous,-1);pageDrop(next,1);
        pages.addView(previous,new LinearLayout.LayoutParams(dp(44),dp(48)));
        TextView counter=text(pageDots(layout.page,layout.pages()),13,MUTED);counter.setTag("home-page-counter");counter.setGravity(Gravity.CENTER);
        counter.setContentDescription("第 "+(layout.page+1)+" 页，共 "+layout.pages()+" 页，点击跳转");counter.setFocusable(true);
        counter.setOnClickListener(v->choosePage());pages.addView(counter,new LinearLayout.LayoutParams(0,dp(48),1));
        pages.addView(next,new LinearLayout.LayoutParams(dp(44),dp(48)));panel.addView(pages);
        pagesView.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener(){
            @Override public void onPageSelected(int position){
                if(pager!=pagesView)return;
                layout.page=position;save();
                counter.setText(pageDots(position,layout.pages()));
                counter.setContentDescription("第 "+(position+1)+" 页，共 "+layout.pages()+" 页，点击跳转");
                previous.setEnabled(position>0);next.setEnabled(position<layout.pages()-1);
            }
        });
        return panel;
    }
    private String pageDots(int page,int count){
        int shown=Math.min(7,count),first=Math.max(0,Math.min(page-shown/2,count-shown));StringBuilder dots=new StringBuilder();
        for(int i=0;i<shown;i++){if(i>0)dots.append("  ");dots.append(first+i==page?'●':'○');}return dots.toString();
    }
    private View appPage(int page){
        FrameLayout frame=new FrameLayout(this);
        FrameLayout scroll=new FrameLayout(this);scroll.setClipToPadding(false);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        GridLayout grid=new GridLayout(this);grid.setTag("home-app-grid");int cols=HomeLayout.COLUMNS;
        grid.setColumnCount(cols);grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);scroll.addView(grid,new FrameLayout.LayoutParams(-1,-2));
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fitAppRows(scroll,grid,cols));
        int count=0;
        for(HomeLayout.Cell cell:layout.cells()){
            if(!loaded||cell.page!=page)continue;count++;
            HomeLayout.Item item=cell.item;
            int slot=cell.page*HomeLayout.PAGE_SIZE+cell.y*HomeLayout.COLUMNS+cell.x;
            View tile=item.widget()?workspaceWidget(item):tile(item);
            tile.setOnDragListener((v,event)->{
                if(!(event.getLocalState() instanceof String))return false;
                String source=(String)event.getLocalState();
                switch(event.getAction()){
                    case DragEvent.ACTION_DRAG_STARTED:return !source.equals(item.id);
                    case DragEvent.ACTION_DRAG_ENTERED:v.setBackground(glass(0x667fe1d0,20));return true;
                    case DragEvent.ACTION_DRAG_EXITED:case DragEvent.ACTION_DRAG_ENDED:v.setBackground(null);return true;
                    case DragEvent.ACTION_DROP:
                        int target=slot+(event.getX()>v.getWidth()*.5f?1:0);
                        if(source.startsWith("dock:"))layout.undock(source.substring(5),target);
                        else{
                            HomeLayout.Item dragged=layout.find(source);
                            if(dragged!=null&&!dragged.widget()&&!item.widget()&&FolderFan.mergeZone(event.getX(),event.getY(),v.getWidth(),v.getHeight(),dp(46)))layout.merge(source,item.id);
                            else layout.move(source,target);
                        }
                        save();main.post(this::render);return true;
                    default:return true;
                }
            });
            tile.setTag(cell);
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams(GridLayout.spec(cell.y,cell.height),GridLayout.spec(cell.x,cell.width));
            lp.width=dp(60)*cell.width;lp.height=dp(96)*cell.height;lp.setMargins(dp(2),dp(2),dp(2),dp(2));grid.addView(tile,lp);
        }
        if(!loaded||count==0){loadLabel=text(loaded?"此页没有应用\n点击下方搜索添加":"正在加载应用…",16,MUTED);loadLabel.setGravity(Gravity.CENTER);frame.addView(loadLabel,new FrameLayout.LayoutParams(-1,-1));}
        return frame;
    }
    private void fitAppRows(View viewport,GridLayout grid,int columns){
        if(viewport.getHeight()<=0||grid.getChildCount()==0)return;
        int rows=HomeLayout.ROWS;
        // The cell plus its two 2 dp margins must fit every row exactly.
        int cell=Math.max(1,Math.min(dp(96),viewport.getHeight()/rows-dp(4)));
        for(int i=0;i<grid.getChildCount();i++){
            View child=grid.getChildAt(i);HomeLayout.Cell placement=(HomeLayout.Cell)child.getTag();
            GridLayout.LayoutParams size=(GridLayout.LayoutParams)child.getLayoutParams();
            int height=(cell+dp(4))*placement.height-dp(4),width=viewport.getWidth()*placement.width/columns-dp(4);
            if(size.height!=height||size.width!=width){size.height=height;size.width=Math.max(1,width);child.setLayoutParams(size);}
        }
    }
    private void turn(int delta){if(pager!=null)goToPage(pager.getCurrentItem()+delta);}
    private void goToPage(int page){if(pager!=null)HomePageMotion.select(pager,page,android.animation.ValueAnimator.areAnimatorsEnabled());}
    private void choosePage(){String[] choices=new String[layout.pages()];for(int i=0;i<choices.length;i++)choices[i]="第 "+(i+1)+" 页";
        show(new AlertDialog.Builder(this).setTitle("跳转页面").setItems(choices,(d,n)->goToPage(n)).create());}
    private void pageDrop(View target,int direction){target.setOnDragListener((v,e)->{
        if(!(e.getLocalState() instanceof String))return false;
        if(e.getAction()==DragEvent.ACTION_DROP){
            String source=(String)e.getLocalState();int page=Math.max(0,Math.min(layout.pages(),layout.page+direction));
            if(source.startsWith("dock:"))layout.undock(source.substring(5),page*HomeLayout.PAGE_SIZE);
            else layout.move(source,page*HomeLayout.PAGE_SIZE+(direction>0?0:HomeLayout.PAGE_SIZE-1));
            layout.page=page;layout.clamp();save();main.post(this::render);}
        return true;
    });}
    private View tile(HomeLayout.Item item){
        LinearLayout tile=column();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(3),dp(4),dp(3),dp(4));
        if(item.folder()){
            View fan=new HomeFolderIcon(this,previews(item.apps));
            fan.setBackground(glass(0x668ca3b9,14));
            tile.addView(fan,new LinearLayout.LayoutParams(dp(48),dp(48)));
        }else tile.addView(icon(item.apps.get(0)),new LinearLayout.LayoutParams(dp(46),dp(46)));
        TextView label=text(item.folder()?item.title:appLabel(item.apps.get(0)),11,TEXT);label.setGravity(Gravity.CENTER);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(dp(2),0,dp(1),0xb0000000);label.setPadding(0,dp(4),0,0);tile.addView(label,new LinearLayout.LayoutParams(-1,-2));
        tile.setContentDescription(label.getText());tile.setFocusable(true);
        tile.setOnClickListener(v->{if(editing)itemMenu(item);else if(item.folder())folder(item);else launch(item.apps.get(0),tile.getChildAt(0));});
        tile.setOnLongClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return v.startDragAndDrop(ClipData.newPlainText("桌面图标",item.id),new View.DragShadowBuilder(v),item.id,0);});
        return tile;
    }
    private View dock(){
        LinearLayout rail=column();rail.setTag("home-dock");rail.setPadding(dp(4),dp(12),dp(4),dp(12));rail.setGravity(Gravity.CENTER_HORIZONTAL);
        HomeStyle.glass(rail,HomeStyle.PANEL_RADIUS,HomeStyle.ROLE_DOCK);
        LinearLayout icons=column();icons.setTag("home-dock-icons");icons.setGravity(Gravity.CENTER);rail.addView(icons,new LinearLayout.LayoutParams(-1,-1));
        for(String key:layout.dock){ImageView icon=icon(key);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(48));lp.setMargins(0,dp(6),0,dp(6));icons.addView(icon,lp);
            icon.setContentDescription(appLabel(key));icon.setFocusable(true);icon.setOnClickListener(v->{if(editing)dockMenu(key);else launch(key,icon);});
            // MiDuo dock behaviour: long-press drags the icon out; editing mode keeps the menu.
            icon.setOnLongClickListener(v->{if(editing){dockMenu(key);return true;}
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return v.startDragAndDrop(ClipData.newPlainText("Dock图标","dock:"+key),new View.DragShadowBuilder(v),"dock:"+key,0);});}
        if(editing&&layout.dock.size()<HomeLayout.DOCK_SIZE){Button add=button("＋",()->pickApp("添加到 Dock",(app,source)->{if(layout.pin(app.key)){save();render();}}));add.setContentDescription("添加到侧边 Dock");icons.addView(add,new LinearLayout.LayoutParams(-1,dp(52)));}
        rail.setOnDragListener((v,e)->{if(!(e.getLocalState() instanceof String))return false;
            if(e.getAction()==DragEvent.ACTION_DROP){HomeLayout.Item item=layout.find((String)e.getLocalState());
                if(item!=null&&!item.widget()&&!item.folder()){if(layout.pin(item.apps.get(0))){save();main.post(this::render);}else message("Dock 已满，请先移除一个图标");}
                else message("请将单个应用添加到 Dock");}return true;});
        return rail;
    }
    private View dockColumn(){
        LinearLayout column=column();column.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams railSize=new LinearLayout.LayoutParams(-1,dp(264));railSize.topMargin=dp(64);column.addView(dock(),railSize);
        column.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        Button search=button("⌕",this::search);search.setContentDescription("搜索应用");search.setTextSize(22);
        HomeStyle.glass(search,26,HomeStyle.ROLE_FLOATING);
        column.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));return column;
    }
    private View widgetPanel(){
        LinearLayout panel=column();panel.setTag("home-widgets");panel.setPadding(dp(12),dp(12),dp(12),dp(12));
        HomeStyle.glass(panel,HomeStyle.PANEL_RADIUS,HomeStyle.ROLE_FRAME);
        LinearLayout heading=row();
        TextView title=text("今天",20,TEXT);heading.addView(title,new LinearLayout.LayoutParams(0,dp(46),1));
        heading.addView(button("＋",widgets::choose),new LinearLayout.LayoutParams(dp(48),dp(46)));panel.addView(heading);
        TextClock date=new TextClock(this);date.setFormat12Hour("M月d日 EEEE");date.setFormat24Hour("M月d日 EEEE");date.setTextColor(MUTED);date.setTextSize(13);panel.addView(date);
        widgetScroll=new ScrollView(this);widgetScroll.setClipToPadding(false);widgetScroll.setPadding(0,dp(14),0,0);panel.addView(widgetScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=column();widgetScroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        if(widgets.ids.isEmpty()){
            TextView empty=text("把常用的小组件放在这里",18,TEXT);empty.setPadding(dp(8),dp(28),dp(8),dp(12));content.addView(empty);
            TextView hint=text("添加手机已有的天气、日历、音乐等组件，保留它们原有的内容与操作。",14,MUTED);hint.setPadding(dp(8),0,dp(8),dp(24));content.addView(hint);
            content.addView(button("添加小组件",widgets::choose));
        }
        for(int id:new ArrayList<>(widgets.ids)){
            boolean inWorkspace=false;for(HomeLayout.Item item:layout.items)if(item.widgetId==id)inWorkspace=true;if(inWorkspace)continue;
            LinearLayout card=column();LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,dp(14));content.addView(card,cp);
            AppWidgetProviderInfo info=widgets.manager.getAppWidgetInfo(id);
            if(info==null){card.addView(text("组件暂不可用",14,MUTED));card.addView(button("移除",()->widgets.remove(id)));continue;}
            if(editing){LinearLayout controls=row();TextView label=text(info.loadLabel(getPackageManager()),12,MUTED);controls.addView(label,new LinearLayout.LayoutParams(0,dp(44),1));
                controls.addView(button("设置",()->widgetMenu(id)),new LinearLayout.LayoutParams(dp(60),dp(44)));card.addView(controls);}
            try{
                AppWidgetHostView view=widgets.view(id,info);if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);
                HomeWidgetScale holder=new HomeWidgetScale(this,view,info);
                HomeStyle.rounded(holder,HomeStyle.PANEL_RADIUS);
                int minimum=widgetDp(info.minResizeHeight>0?info.minResizeHeight:info.minHeight);
                int defaultHeight=Math.max(120,widgetDp(info.minHeight));int height=Math.max(minimum,store.widgetHeight(id)==0?defaultHeight:store.widgetHeight(id));
                card.addView(holder,new LinearLayout.LayoutParams(-1,dp(height)));
                holder.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
                    if(r-l>0&&b-t>0&&(r-l!=or-ol||b-t!=ob-ot)){
                        float scale=getResources().getDisplayMetrics().density;
                        try{view.updateAppWidgetSize(new Bundle(),Collections.singletonList(new android.util.SizeF((r-l)/scale,(b-t)/scale)));}
                        catch(RuntimeException failure){android.util.Log.w("GlassHome","Widget size update unavailable: "+id,failure);}
                    }
                });
            }catch(RuntimeException e){card.addView(text("这个小组件暂时无法显示",14,MUTED));card.addView(button("移除",()->widgets.remove(id)));}
        }
        ScrollView scroller=widgetScroll;scroller.post(()->scroller.scrollTo(0,widgetScrollY));return panel;
    }
    private View panelSwitch(boolean widgetsPage){
        LinearLayout switcher=row();switcher.setGravity(Gravity.CENTER);switcher.setPadding(dp(3),dp(3),dp(3),dp(3));
        switcher.setTag("home-panel-switch");
        Button applications=button("应用",()->switchPanel(false));
        Button widgetButton=button("小组件",()->switchPanel(true));
        applications.setTextSize(13);widgetButton.setTextSize(13);
        applications.setTextColor(widgetsPage?MUTED:TEXT);widgetButton.setTextColor(widgetsPage?TEXT:MUTED);
        HomeStyle.glass(widgetsPage?widgetButton:applications,17,HomeStyle.ROLE_CONTROL);
        switcher.addView(applications,new LinearLayout.LayoutParams(0,-1,1));
        switcher.addView(widgetButton,new LinearLayout.LayoutParams(0,-1,1));return switcher;
    }
    private View actionIcon(String label,Runnable action){
        Button icon=new Button(this){
            final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){
                super.onDraw(c);c.save();c.translate(getWidth()/2f-dp(12),getHeight()/2f-dp(12));c.scale(dp(24)/24f,dp(24)/24f);
                ink.setColor(TEXT);ink.setStrokeWidth(1.7f);ink.setStyle(Paint.Style.STROKE);ink.setStrokeCap(Paint.Cap.ROUND);ink.setStrokeJoin(Paint.Join.ROUND);
                if(label.equals("编辑")){Path p=new Path();p.moveTo(5,16);p.lineTo(16,5);p.lineTo(19,8);p.lineTo(8,19);p.lineTo(4,20);p.close();c.drawPath(p,ink);c.drawLine(13.5f,7.5f,16.5f,10.5f,ink);}
                else if(label.equals("设置")){for(int i=0;i<3;i++){float y=6+i*6,x=i==1?15:9;c.drawLine(4,y,x-2,y,ink);c.drawLine(x+2,y,20,y,ink);c.drawCircle(x,y,2,ink);}}
                else{Path p=new Path();p.moveTo(5,12);p.lineTo(10,17);p.lineTo(19,7);c.drawPath(p,ink);}c.restore();
            }
        };icon.setPadding(0,0,0,0);icon.setMinWidth(0);icon.setMinimumWidth(0);icon.setOnClickListener(v->action.run());icon.setContentDescription(label);icon.setTooltipText(label);
        HomeStyle.glass(icon,22,HomeStyle.ROLE_FLOATING);icon.setForeground(HomeStyle.ripple(icon,22));return icon;
    }
    private boolean panelChanging;
    private void switchPanel(boolean target){
        if(wide||showWidgets==target||panelChanging)return;
        LinearLayout body=root.findViewWithTag("home-body"),owner=root;View outgoing=body.getChildAt(0);
        panelChanging=true;
        Runnable replace=()->{
            if(root!=owner){panelChanging=false;return;}
            if(widgetScroll!=null)widgetScrollY=widgetScroll.getScrollY();widgetScroll=null;pager=null;
            showWidgets=target;
            LinearLayout header=root.findViewWithTag("home-header");View previous=header.findViewWithTag("home-panel-switch");int at=header.indexOfChild(previous);header.removeView(previous);header.addView(panelSwitch(target),at,new LinearLayout.LayoutParams(dp(142),dp(44)));
            body.removeView(outgoing);View incoming=target?widgetPanel():workspace();
            body.addView(incoming,0,new LinearLayout.LayoutParams(0,-1,1.1f));
            if(android.animation.ValueAnimator.areAnimatorsEnabled()){
                incoming.setAlpha(0);incoming.setTranslationX(dp(target?16:-16));
                incoming.animate().alpha(1).translationX(0).setDuration(220).setInterpolator(new PathInterpolator(.2f,0,0,1)).withEndAction(()->panelChanging=false).start();
            }else panelChanging=false;
        };
        if(android.animation.ValueAnimator.areAnimatorsEnabled())outgoing.animate().alpha(0).translationX(dp(target?-10:10)).setDuration(110).withEndAction(replace).start();else replace.run();
    }
    private void widgetMenu(int id){
        show(new AlertDialog.Builder(this).setTitle("小组件").setItems(new String[]{"组件设置","调整高度","上移","下移","移除","放到当前应用页"},(d,n)->{
            if(n==0)widgets.reconfigure(id);
            else if(n==1){AppWidgetProviderInfo info=widgets.manager.getAppWidgetInfo(id);if(info==null)return;
                if((info.resizeMode&AppWidgetProviderInfo.RESIZE_VERTICAL)==0){message("这个组件不支持调整高度");return;}
                int min=Math.max(1,widgetDp(info.minResizeHeight>0?Math.min(info.minHeight,info.minResizeHeight):info.minHeight));
                int max=info.maxResizeHeight>=info.minHeight&&info.maxResizeHeight>0?Math.max(min,widgetDp(info.maxResizeHeight)):Math.max(min,600);
                LinearLayout box=column();box.setPadding(dp(24),dp(16),dp(24),dp(16));TextView value=text("",16,TEXT);SeekBar seek=new SeekBar(this);
                seek.setMax(max-min);seek.setProgress(Math.max(0,(store.widgetHeight(id)>0?store.widgetHeight(id):Math.max(120,widgetDp(info.minHeight)))-min));
                value.setText((seek.getProgress()+min)+" dp");seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int p,boolean user){value.setText((p+min)+" dp");}});
                box.addView(value);box.addView(seek);show(new AlertDialog.Builder(this).setTitle("组件高度").setView(box).setPositiveButton("保存",(a,b)->{store.widgetHeight(id,seek.getProgress()+min);render();}).setNegativeButton("取消",null).create());
            }else if(n==2)widgets.move(id,-1);else if(n==3)widgets.move(id,1);
            else if(n==5){AppWidgetProviderInfo info=widgets.manager.getAppWidgetInfo(id);if(info==null)return;int[] span=widgets.defaultSpan(info);
                HomeLayout.Item item=layout.addWidget(id,layout.page,span[0],span[1]);layout.page=layout.pageOf(item.id);showWidgets=false;save();render();}
            else widgets.remove(id);
        }).create());
    }
    private View workspaceWidget(HomeLayout.Item item){
        FrameLayout card=new FrameLayout(this);card.setContentDescription("桌面小组件");
        HomeStyle.rounded(card,HomeStyle.PANEL_RADIUS);
        AppWidgetProviderInfo info=widgets.manager.getAppWidgetInfo(item.widgetId);
        if(info!=null)try{
            AppWidgetHostView hosted=widgets.view(item.widgetId,info);
            if(hosted.getParent() instanceof ViewGroup)((ViewGroup)hosted.getParent()).removeView(hosted);
            HomeWidgetScale holder=new HomeWidgetScale(this,hosted,info);
            card.addView(holder,new FrameLayout.LayoutParams(-1,-1));
            holder.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
                if(r>l&&b>t&&(r-l!=or-ol||b-t!=ob-ot))try{
                    float density=getResources().getDisplayMetrics().density;
                    hosted.updateAppWidgetSize(new Bundle(),Collections.singletonList(new android.util.SizeF((r-l)/density,(b-t)/density)));
                }catch(RuntimeException failure){android.util.Log.w("GlassHome","Widget size unavailable",failure);}
            });
        }catch(RuntimeException failure){card.addView(text("组件暂不可用",14,MUTED));}
        else card.addView(text("组件暂不可用",14,MUTED));
        if(editing){
            card.setForeground(HomeStyle.outline(card,0x8cbcefe3,HomeStyle.PANEL_RADIUS));
            LinearLayout bar=row();bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(4),0,dp(4),0);
            bar.setBackground(HomeStyle.surface(bar,0xcc252b32,18));
            Button resize=button("尺寸",()->resizeWidget(item));resize.setContentDescription("调整组件尺寸");
            Button settings=button("设置",()->widgets.reconfigure(item.widgetId));
            Button more=button("⋯",()->workspaceWidgetMenu(item));
            bar.addView(resize,new LinearLayout.LayoutParams(0,dp(40),1));
            bar.addView(settings,new LinearLayout.LayoutParams(0,dp(40),1));
            bar.addView(more,new LinearLayout.LayoutParams(dp(48),dp(40)));
            FrameLayout.LayoutParams barSize=new FrameLayout.LayoutParams(-1,dp(44),Gravity.BOTTOM);
            int margin=dp(6);barSize.setMargins(margin,margin,margin,margin);
            bar.setOnLongClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return bar.startDragAndDrop(ClipData.newPlainText("小组件",item.id),new View.DragShadowBuilder(card),item.id,0);});
            card.addView(bar,barSize);
        }else card.setForeground(null);
        return card;
    }
    private void resizeWidget(HomeLayout.Item item){
        AppWidgetProviderInfo info=widgets.manager.getAppWidgetInfo(item.widgetId);if(info==null){message("组件暂不可用");return;}
        List<int[]> choices=widgets.resizeChoices(info,item);
        if(choices.size()<2){message("这个组件在当前页面没有其他可用尺寸");return;}
        String[] labels=new String[choices.size()];int selected=0;
        for(int i=0;i<choices.size();i++){int[] size=choices.get(i);labels[i]=size[0]+" × "+size[1];if(size[0]==item.spanX&&size[1]==item.spanY)selected=i;}
        final int[] chosen={selected};
        show(new AlertDialog.Builder(this).setTitle("组件尺寸").setSingleChoiceItems(labels,selected,(dialog,index)->chosen[0]=index)
            .setPositiveButton("应用",(dialog,index)->{int[] size=choices.get(chosen[0]);if(item.spanX==size[0]&&item.spanY==size[1])return;
                if(!layout.resize(item.id,size[0],size[1])){message("这个尺寸会与其他内容重叠");return;}
                layout.page=layout.pageOf(item.id);save();render();})
            .setNegativeButton("取消",null).create());
    }
    private void workspaceWidgetMenu(HomeLayout.Item item){
        show(new AlertDialog.Builder(this).setTitle("应用页小组件").setItems(new String[]{"组件设置","前移","后移","移动到页面","移回组件栏","移除"},(d,n)->{
            if(n==0){widgets.reconfigure(item.widgetId);return;}
            if(n==3){moveToPage(item);return;}
            if(n==5){widgets.remove(item.widgetId);return;}
            if(n==1||n==2)layout.move(item.id,Math.max(0,item.slot)+(n==1?-1:1)*HomeLayout.COLUMNS);
            else if(n==4)layout.remove(item.id);
            layout.clamp();save();render();
        }).create());
    }
    private void itemMenu(HomeLayout.Item item){
        List<String> actions=new ArrayList<>(Arrays.asList("前移","后移","移动到页面",item.folder()?"重命名文件夹":"添加到 Dock",item.folder()?"解散文件夹":"与其他图标创建文件夹","从桌面移除"));
        show(new AlertDialog.Builder(this).setTitle(item.folder()?item.title:appLabel(item.apps.get(0))).setItems(actions.toArray(new String[0]),(d,n)->{
            if(n==0)layout.move(item.id,Math.max(0,item.slot)-1);else if(n==1)layout.move(item.id,Math.max(0,item.slot)+1);
            else if(n==2){moveToPage(item);return;}
            else if(n==3){if(item.folder()){rename(item);return;}if(!layout.pin(item.apps.get(0)))message("Dock 已满，请先移除一个图标");}
            else if(n==4){if(item.folder())layout.dissolve(item.id);else{mergePicker(item);return;}}
            else layout.remove(item.id);save();render();
        }).create());
    }
    private void moveToPage(HomeLayout.Item item){
        int count=layout.pages();String[] pages=new String[count];for(int i=0;i<count;i++)pages[i]="第 "+(i+1)+" 页";
        show(new AlertDialog.Builder(this).setTitle("移动到页面").setItems(pages,(d,n)->{
            layout.move(item.id,n*HomeLayout.PAGE_SIZE);layout.page=layout.pageOf(item.id);save();render();
        }).create());
    }
    private void mergePicker(HomeLayout.Item item){List<HomeLayout.Item> targets=new ArrayList<>();for(HomeLayout.Item i:layout.items)if(i!=item&&!i.widget())targets.add(i);
        if(targets.isEmpty()){message("先添加另一个应用");return;}
        String[] names=new String[targets.size()];for(int i=0;i<names.length;i++){HomeLayout.Item target=targets.get(i);names[i]=target.folder()?target.title:appLabel(target.apps.get(0));}
        show(new AlertDialog.Builder(this).setTitle("合并到文件夹").setItems(names,(d,n)->{layout.merge(item.id,targets.get(n).id);save();render();}).create());}
    private void rename(HomeLayout.Item item){EditText input=new EditText(this);input.setTextColor(TEXT);input.setSingleLine(true);input.setText(item.title);input.setSelectAllOnFocus(true);
        show(new AlertDialog.Builder(this).setTitle("文件夹名称").setView(input).setPositiveButton("保存",(d,n)->{String name=input.getText().toString().trim();if(!name.isEmpty())item.title=name;save();render();}).setNegativeButton("取消",null).create());}
    private List<Bitmap> previews(List<String> keys){
        List<Bitmap> result=new ArrayList<>();
        for(String key:keys){HomeApps.App app=apps.get(key);result.add(app==null?null:app.icon);}
        return result;
    }
    HomeSheet folder(HomeLayout.Item item){
        List<String> keys=new ArrayList<>(item.apps);int columns=3,perPage=columns*columns;
        int pages=(keys.size()+perPage-1)/perPage;
        int rows=Math.min(columns,(keys.size()+columns-1)/columns);
        // Fullscreen borderless blur (user preference): the folder fills the window.
        HomeSheet dialog=new HomeSheet(this,360,560,false,true);
        LinearLayout header=dialog.header(item.title);
        View fan=new HomeFolderIcon(this,previews(keys));fan.setBackground(glass(0x668ca3b9,13));
        LinearLayout.LayoutParams fanSize=new LinearLayout.LayoutParams(dp(44),dp(44));fanSize.rightMargin=dp(10);
        header.addView(fan,0,fanSize);
        header.addView(button("重命名",()->{dialog.dismiss();rename(item);}),header.getChildCount()-1,new LinearLayout.LayoutParams(dp(68),dp(48)));
        TextView hint=text(keys.size()+" 个应用 · 长按图标管理",12,MUTED);hint.setPadding(dp(4),0,0,dp(12));dialog.content.addView(hint);
        if(pages<=1){
            ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);dialog.content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            scroll.addView(folderPage(dialog,item,keys,0,keys.size(),columns),new ScrollView.LayoutParams(-1,-2));
            blankTapCloses(scroll,dialog);
        }else{
            // MiDuo-style folder panel: 3x3 pages with dot indicators.
            HomePager pagesView=new HomePager(this);pagesView.setTag("home-folder-pager");
            pagesView.setAdapter(new PagerAdapter(){
                @Override public int getCount(){return pages;}
                @Override public boolean isViewFromObject(View view,Object page){return view==page;}
                @Override public Object instantiateItem(ViewGroup container,int position){
                    GridLayout page=folderPage(dialog,item,keys,position*perPage,Math.min(keys.size(),(position+1)*perPage),columns);
                    container.addView(page);return page;
                }
                @Override public void destroyItem(ViewGroup container,int position,Object page){container.removeView((View)page);}
            });
            TextView counter=text(pageDots(0,pages),13,MUTED);counter.setGravity(Gravity.CENTER);
            pagesView.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener(){
                @Override public void onPageSelected(int position){counter.setText(pageDots(position,pages));}
            });
            dialog.content.addView(pagesView,new LinearLayout.LayoutParams(-1,0,1));
            blankTapCloses(pagesView,dialog);
            counter.setPadding(0,dp(8),0,dp(4));dialog.content.addView(counter,new LinearLayout.LayoutParams(-1,-2));
        }
        show(dialog);return dialog;
    }
    /** Fullscreen folder: a clean tap (no drag) on blank area closes the sheet. */
    private void blankTapCloses(View view,HomeSheet dialog){
        final float[] down=new float[2];final long[] at=new long[1];
        view.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:down[0]=e.getX();down[1]=e.getY();at[0]=e.getDownTime();break;
                case MotionEvent.ACTION_UP:
                    if(Math.abs(e.getX()-down[0])<dp(8)&&Math.abs(e.getY()-down[1])<dp(8)&&e.getEventTime()-at[0]<300)dialog.dismiss();
                    break;
            }
            return false;
        });
    }
    /** One 3-column page of a folder sheet; [from,to) is the member index range. */
    private GridLayout folderPage(HomeSheet dialog,HomeLayout.Item item,List<String> keys,int from,int to,int columns){
        GridLayout grid=new GridLayout(this);grid.setTag("home-folder-grid");grid.setColumnCount(columns);
        for(int i=from;i<to;i++){
            String key=keys.get(i);LinearLayout shortcut=column();shortcut.setGravity(Gravity.CENTER);shortcut.setPadding(dp(4),dp(6),dp(4),dp(6));
            ImageView image=icon(key);shortcut.addView(image,new LinearLayout.LayoutParams(dp(52),dp(52)));
            TextView label=text(appLabel(key),12,TEXT);label.setGravity(Gravity.CENTER);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);label.setPadding(0,dp(6),0,0);
            shortcut.addView(label,new LinearLayout.LayoutParams(-1,-2));shortcut.setContentDescription(appLabel(key));shortcut.setFocusable(true);
            shortcut.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),null,glass(0xffffffff,18)));
            shortcut.setOnClickListener(v->{launch(key,image);dialog.dismiss();});
            shortcut.setOnLongClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);dialog.dismiss();folderAppMenu(item,key);return true;});
            GridLayout.LayoutParams cell=new GridLayout.LayoutParams(GridLayout.spec((i-from)/columns),GridLayout.spec((i-from)%columns,1f));cell.width=0;cell.height=dp(108);grid.addView(shortcut,cell);
        }
        return grid;
    }
    private void folderAppMenu(HomeLayout.Item item,String key){
        show(new AlertDialog.Builder(this).setTitle(appLabel(key)).setItems(new String[]{"移出文件夹","添加到 Dock","应用信息"},(a,b)->{
            if(b==0)layout.extract(item.id,key);else if(b==1){if(!layout.pin(key))message("Dock 已满");}else appInfo(key);save();render();
        }).create());
    }
    private void dockMenu(String key){show(new AlertDialog.Builder(this).setTitle(appLabel(key)).setItems(new String[]{"上移","下移","从 Dock 移除","应用信息"},(d,n)->{
        int i=layout.dock.indexOf(key);if(n==0&&i>0)Collections.swap(layout.dock,i,i-1);else if(n==1&&i<layout.dock.size()-1)Collections.swap(layout.dock,i,i+1);
        else if(n==2)layout.dock.remove(key);else if(n==3)appInfo(key);save();render();
    }).create());}
    private void search(){pickApp("搜索应用",(app,source)->launch(app.key,source),true);}
    HomeSheet pickApp(String title,java.util.function.BiConsumer<HomeApps.App,View> action){return pickApp(title,action,false);}
    HomeSheet pickApp(String title,java.util.function.BiConsumer<HomeApps.App,View> action,boolean bottom){
        HomeSheet dialog=new HomeSheet(this,580,580,bottom);dialog.header(title);
        HomeSearchList<HomeApps.App> search=new HomeSearchList<>(this,"home-search-query","home-search-results","home-search-empty","搜索名称或包名");
        dialog.content.addView(search,new LinearLayout.LayoutParams(-1,0,1));
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return search.visible.size();}public Object getItem(int p){return search.visible.get(p);}public long getItemId(int p){return p;}
            public View getView(int p,View recycled,ViewGroup parent){
                HomeApps.App app=search.visible.get(p);AppRow row=recycled instanceof AppRow?(AppRow)recycled:new AppRow();
                row.image.setImageBitmap(app.icon);row.image.setAlpha(1f);row.name.setText(app.label);row.detail.setText(appPlacement(app));
                row.more.setContentDescription("管理 "+app.label);row.more.setOnClickListener(v->{dialog.dismiss();appMenu(app);});
                row.setOnClickListener(v->{if(dialog.isShowing()){action.accept(app,row.image);dialog.dismiss();}});
                row.setOnLongClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);dialog.dismiss();appMenu(app);return true;});
                return row;
            }
        };
        search.list.setAdapter(adapter);
        search.filter=()->{
            search.visible.clear();String term=search.query.getText().toString().trim().toLowerCase(Locale.ROOT);
            for(HomeApps.App app:apps.values())if(app.search.contains(term))search.visible.add(app);
            adapter.notifyDataSetChanged();search.count.setText(search.visible.size()+" 个应用");
            search.clear.setEnabled(search.query.length()>0);
            search.empty.setText(loaded?"没有找到应用\n试试其他名称或包名":loading?"正在加载应用…":"应用列表暂未加载，请稍后重试");
        };
        search.query.setOnEditorActionListener((v,id,event)->{
            if(id==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){if(search.visible.size()==1){action.accept(search.visible.get(0),null);dialog.dismiss();}return true;}return false;
        });
        search.list.setOnItemClickListener((p,v,n,id)->{if(dialog.isShowing()){action.accept(search.visible.get(n),v instanceof AppRow?((AppRow)v).image:v);dialog.dismiss();}});
        catalogObservers.add(search.filter);dialog.setOnDismissListener(d->catalogObservers.remove(search.filter));search.refresh();
        if(bottom)search.query.post(()->{search.query.requestFocus();search.query.setSelection(search.query.getText().length());});
        show(dialog);return dialog;
    }
    private String appPlacement(HomeApps.App app){
        String placement="未添加到桌面";
        if(layout.dock.contains(app.key))placement="已在 Dock";
        else for(int i=0;i<layout.items.size();i++)if(layout.items.get(i).apps.contains(app.key)){
            HomeLayout.Item item=layout.items.get(i);placement=item.folder()?"文件夹 · "+item.title:"桌面第 "+(layout.pageOf(item.id)+1)+" 页";break;
        }
        return (app.user.equals(android.os.Process.myUserHandle())?"":"工作资料 · ")+placement;
    }
    private void appMenu(HomeApps.App app){
        show(new AlertDialog.Builder(this).setTitle(app.label).setItems(new String[]{"添加到桌面","添加到 Dock","应用信息"},(d,which)->{
            if(which==0){layout.add(app.key);for(HomeLayout.Item i:layout.items)if(i.apps.contains(app.key)){layout.page=layout.pageOf(i.id);break;}}
            else if(which==1){if(!layout.pin(app.key))message("Dock 已满");}else appInfo(app.key);save();render();
        }).create());
    }
    private final class AppRow extends LinearLayout {
        final ImageView image;final TextView name,detail;final Button more;
        AppRow(){
            super(DuoHomeActivity.this);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(8),dp(8),0,dp(8));setMinimumHeight(dp(72));
            setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22ffffff),null,glass(0xffffffff,16)));
            image=icon("");image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(image,new LinearLayout.LayoutParams(dp(44),dp(44)));
            LinearLayout labels=column();labels.setPadding(dp(14),0,dp(4),0);name=text("",16,TEXT);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
            detail=text("",12,MUTED);detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);labels.addView(name);labels.addView(detail);
            addView(labels,new LinearLayout.LayoutParams(0,-2,1));more=button("⋯",()->{});addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
        }
    }
    private void launch(String key){launch(key,null);}
    private void launch(String key,View source){HomeApps.App app=apps.get(key);if(app==null){message("应用暂不可用，可能已卸载或工作资料已暂停");return;}
        Rect sourceBounds=null;
        if(source!=null&&source.isAttachedToWindow()){
            Rect visible=new Rect();
            if(source.getGlobalVisibleRect(visible)){
                // LauncherApps expects screen coordinates, not coordinates inside our root.
                int[] location=new int[2];source.getLocationOnScreen(location);
                sourceBounds=new Rect(location[0],location[1],location[0]+source.getWidth(),location[1]+source.getHeight());
            }
        }
        // Leave animation options to the system; source bounds do not grant MIUI's
        // private launcher/remote-transition integration.
        try{getSystemService(LauncherApps.class).startMainActivity(app.component,app.user,sourceBounds,dualPanel()?ActivityOptions.makeBasic().setLaunchDisplayId(getDisplay().getDisplayId()).toBundle():null);
        noteRecent(key);}
        catch(RuntimeException e){message("系统未能打开这个应用");}}
    private void appInfo(String key){HomeApps.App app=apps.get(key);if(app==null)return;
        try{getSystemService(LauncherApps.class).startAppDetailsActivity(app.component,app.user,null,null);}catch(RuntimeException e){message("无法打开应用信息");}}
    private String appLabel(String key){HomeApps.App app=apps.get(key);if(app!=null)return app.label;
        int slash=key.indexOf('/');return (slash<0?key:key.substring(0,slash))+"（暂不可用）";}
    private ImageView icon(String key){ImageView view=new ImageView(this);HomeApps.App app=apps.get(key);
        if(app==null){view.setImageDrawable(getPackageManager().getDefaultActivityIcon());view.setAlpha(.45f);}else view.setImageBitmap(app.icon);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),Math.min(v.getWidth(),v.getHeight())*.23f);}});
        view.setClipToOutline(true);return view;}
    private void menu(){
        // Fixed dual is permanent for users; the toggle survives only as an adb switch.
        show(new AlertDialog.Builder(this).setTitle("玻璃桌面").setItems(new String[]{"选择默认桌面","添加现有小组件","更换系统壁纸","全面屏手势","折叠动画设置","刷新应用列表","使用说明"},(d,n)->{
        if(n==0)requestHome();else if(n==1)widgets.choose();else if(n==2){try{startActivity(new Intent(Intent.ACTION_SET_WALLPAPER));}catch(ActivityNotFoundException e){message("未找到系统壁纸设置");}}
        else if(n==3||n==4)startActivity(new Intent(this,DesktopActivity.class));else if(n==5)reload();
        else show(new AlertDialog.Builder(this).setTitle("使用玻璃桌面").setMessage("左右滑动切换应用页。长按图标拖动：放到图标中心创建文件夹，放到两侧调整顺序；放到 Dock 添加常用应用。\n\n点击编辑后可调整图标、文件夹和小组件。搜索中长按应用可添加到桌面。\n\n小窗、分屏和最近任务由系统处理。可在“选择默认桌面”中切回小米桌面。").setPositiveButton("知道了",null).create());
    }).create());}
    private boolean isDefaultHome(){RoleManager roles=getSystemService(RoleManager.class);return roles!=null&&roles.isRoleHeld(RoleManager.ROLE_HOME);}
    private void requestHome(){try{
        RoleManager roles=getSystemService(RoleManager.class);
        if(roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_HOME)&&!roles.isRoleHeld(RoleManager.ROLE_HOME))startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME),4201);
        else startActivityForResult(new Intent(Settings.ACTION_HOME_SETTINGS),4201);
    }catch(RuntimeException e){message("请在系统设置中选择默认桌面");}}
    void show(Dialog dialog){dialogs.removeIf(d->!d.isShowing());dialogs.add(dialog);dialog.show();}
    void message(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    /** Display-scoped task switcher replacing MIUI recents on virtual panels. */
    static final LinkedHashMap<String,Boolean> recentsLru=new LinkedHashMap<>(){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> eldest){return size()>8;}
    };
    private static void noteRecent(String key){synchronized(recentsLru){recentsLru.remove(key);recentsLru.put(key,Boolean.TRUE);}}
    HomeSheet recentsSheet(){
        List<String> keys=new ArrayList<>();
        synchronized(recentsLru){keys.addAll(recentsLru.keySet());}
        java.util.Collections.reverse(keys);
        // Same fullscreen borderless blur base as the opened folder; cards stack on top.
        HomeSheet dialog=new HomeSheet(this,420,500,false,true);
        dialog.header("最近任务");
        if(keys.isEmpty()){
            TextView empty=text("还没有从桌面打开过的应用",15,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(12),dp(48),dp(12),dp(12));
            dialog.content.addView(empty);
        }else{
            HomePager cards=new HomePager(this);cards.setTag("home-recents-pager");
            cards.setOffscreenPageLimit(2);
            cards.setPageMargin(-dp(280));
            cards.setPageTransformer(false,(page,position)->{
                float factor=1-Math.min(1f,Math.abs(position))*0.12f;
                page.setScaleX(factor);page.setScaleY(factor);
                page.setAlpha(1f-Math.min(1f,Math.abs(position))*0.25f);
            });
            cards.setAdapter(new PagerAdapter(){
                @Override public int getCount(){return keys.size();}
                @Override public boolean isViewFromObject(View view,Object item){return view==item;}
                @Override public Object instantiateItem(ViewGroup container,int position){
                    FrameLayout page=new FrameLayout(DuoHomeActivity.this);
                    page.addView(recentsCard(keys.get(position)));
                    container.addView(page);return page;
                }
                @Override public void destroyItem(ViewGroup container,int position,Object page){container.removeView((View)page);}
            });
            dialog.content.addView(cards,new LinearLayout.LayoutParams(-1,0,1));
            blankTapCloses(cards,dialog);
        }
        show(dialog);return dialog;
    }
    /** A paper-stack card for the display-scoped task switcher. */
    private View recentsCard(String key){
        FrameLayout page=new FrameLayout(this);
        LinearLayout card=column();card.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams size=new FrameLayout.LayoutParams(dp(240),dp(340),Gravity.CENTER);
        page.addView(card,size);
        HomeStyle.glass(card,30,HomeStyle.ROLE_CARD);
        HomeApps.App app=apps.get(key);
        ImageView image=icon(key);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(image,new LinearLayout.LayoutParams(dp(96),dp(96)));
        TextView name=text(app==null?key:app.label,20,TEXT);name.setGravity(Gravity.CENTER);name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(dp(10),dp(18),dp(10),dp(4));
        card.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView detail=text(app==null?"":appPlacement(app),12,MUTED);detail.setGravity(Gravity.CENTER);
        detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(detail,new LinearLayout.LayoutParams(-1,-2));
        card.setOnClickListener(v->{launch(key,image);dismissSheets();});
        return page;
    }
    private void dismissSheets(){for(Dialog d:new ArrayList<>(dialogs))if(d.isShowing())d.dismiss();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private TextView text(String value,int size,int color){return HomeStyle.text(this,value,size,color);}
    private Button button(String label,Runnable action){return HomeStyle.button(this,label,action);}
    private GradientDrawable glass(int color,int radius){return HomeStyle.glassDrawable(this,color,radius);}
}
