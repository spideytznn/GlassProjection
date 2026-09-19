package io.github.sixzleo.tabfold.projection;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.hardware.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.view.accessibility.*;
import android.widget.FrameLayout;
import java.util.HashSet;
import java.util.concurrent.*;

public final class ProjectionService extends AccessibilityService implements SensorEventListener {
    static volatile ProjectionService instance;
    static volatile long updatedAt,helperAt;
    static volatile boolean allowed,primaryInner,lockScreen,standby;
    static volatile FoldPose foldPose=new FoldPose(false);
    private float hinge=Float.NaN;
    static volatile String status="未开启";
    private final Handler main=new Handler(Looper.getMainLooper());
    private static volatile Messenger rendererListener;
    private long rendererSignature=Long.MIN_VALUE;
    private long rendererRevision,displayRevision,displaySignature=Long.MIN_VALUE;
    private volatile Bundle cachedRenderFrame=new Bundle();
    private Bundle displayFrame=new Bundle();
    private boolean rendererFull;
    private long stateUpdates;
    static void listenRenderer(IBinder binder){
        rendererListener=binder==null?null:new Messenger(binder);
        ProjectionService s=instance;if(s!=null)s.main.post(()->{s.rendererFull=true;s.rendererSignature=Long.MIN_VALUE;s.signalRenderer();});
    }
    private void signalRenderer(){
        FoldPose pose=foldPose;
        long signature=Float.floatToIntBits(pose.angle());
        int flags=(allowed?1:0)|(primaryInner?2:0)|(pose.blocksProjection()?4:0)|(pose.fullyOpened()?8:0)
                |(foldHeld?16:0)|(screenFadeActive?32:0)|(standby?64:0);
        signature=31*signature+flags;signature=31*signature+coverToken;
        signature=31*signature+screenFadeMode;signature=31*signature+mirrorScene.hashCode();
        signature=31*signature+AnimationSettings.startAngle;
        signature=31*signature+AnimationSettings.blurPercent;signature=31*signature+AnimationSettings.stretchPercent;
        signature=31*signature+displayRevision;signature=31*signature+(mirrorPreview!=null?1:0);
        signature=31*signature+Float.floatToIntBits(screenDarkness);
        signature=31*signature+Float.floatToIntBits(screenFadeFrom);signature=31*signature+screenFadeAt;
        signature=31*signature+coverStarted;signature=31*signature+(coverBacking?1:0);
        if(signature==rendererSignature)return;rendererSignature=signature;
        Bundle previous=cachedRenderFrame,next=buildRenderFrame();
        long base=rendererRevision;next.putLong("_revision",++rendererRevision);next.putBoolean("_full",true);
        cachedRenderFrame=next;
        Messenger listener=rendererListener;if(listener==null)return;
        Bundle delta;
        if(rendererFull||base==0)delta=new Bundle(next);
        else{
            delta=new Bundle(next);
            for(String key:next.keySet())if(!key.startsWith("_")&&java.util.Objects.equals(next.get(key),previous.get(key)))delta.remove(key);
            delta.putBoolean("_full",false);delta.putLong("_base",base);
        }
        rendererFull=false;
        Message message=Message.obtain(null,1);message.setData(delta);
        try{listener.send(message);}
        catch(RemoteException e){if(rendererListener==listener)rendererListener=null;}
    }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final HashSet<String> homes=new HashSet<>();
    private long homesResolvedAt;
    private final FrameGate gate=new FrameGate();
    private final LockScreenGate lockGate=new LockScreenGate();
    private final FoldHoldGate holdGate=new FoldHoldGate();
    private volatile boolean foldHeld;
    private final TouchObservation touchObservation=new TouchObservation(this);
    private final FingerSwipeGate fingerSwipe=new FingerSwipeGate();
    private GestureNavigationOverlay gestureNavigation;
    private SensorManager sensors;
    private Sensor physicalFoldSensor,directContactSensor;
    private float directContactField=Float.NaN;
    private int directContactBit=-1;
    private DisplayManager displays;
    private WindowManager manager;
    private FrameLayout window;
    private boolean connected,pending,homeUncertain;
    private volatile String foregroundWindowClass="";
    private int foregroundWindowId=-1,recentsWindowId=-1;
    private long recentsRequestedAt;
    private final AppBlacklist appBlacklist=new AppBlacklist();
    private boolean appScopeDirty=true;
    private long appScopeAt;
    private volatile boolean appBlocked;
    private String scene="";
    private long retryAt,lastHomeAt,shownSignature;
    private int generation,attempts;
    private DesktopProjection overlay;
    private volatile MirrorPreview mirrorPreview;
    private volatile long mirrorUntil;
    private boolean livePreferred;
    private long liveUntil;
    private long mirrorObserveUntil;
    private String mirrorScene="";
    private final ProjectionSceneTiming entryTiming=new ProjectionSceneTiming();
    private long entrySceneStarted(int width,int height,int rotation,boolean inner){
        return entryTiming.observe(width,height,rotation,inner,SystemClock.uptimeMillis());
    }
    private boolean mirrorFold;
    private final ScreenFade screenFade=new ScreenFade();
    private volatile long coverToken,coverStarted;
    private volatile boolean coverBacking,screenFadeActive;
    private volatile float screenDarkness,screenFadeFrom;
    private volatile long screenFadeAt;
    private volatile int screenFadeMode;
    static void prepareBlackout(long id){ProjectionService s=instance;if(s!=null)s.main.post(()->{
        if(s.mirrorPreview!=null){s.mirrorPreview.prepareBlackout(id);s.mirrorPreview.setBlackout(s.coverBacking);}
    });}
    static void markCoverReady(String request){ProjectionService s=instance;if(s!=null)s.main.post(()->{
        if(request==null)return;
        int split=request.indexOf(':');if(split<0)return;
        long token;try{token=Long.parseLong(request.substring(0,split));}catch(NumberFormatException e){return;}
        Display d=s.displays.getDisplay(0);if(d==null||d.getState()!=Display.STATE_ON)return;
        Point p=new Point();d.getRealSize(p);
        String key=p.x+"x"+p.y+":"+d.getRotation()+":"+inner(d);
        if(!key.equals(request.substring(split+1)))return;
        if(s.screenFade.contentReady(token,SystemClock.uptimeMillis())){
            s.coverToken=0;
            // No visibility change here: the animation draws above a persistent black base.
            Log.i("ProjectionContinuity","COVER_CONTENT_READY backingRetained=true elapsedMs="+(SystemClock.uptimeMillis()-s.coverStarted));
        }
    });}
    static Bundle mirrorLive(boolean renew){
        Bundle result=new Bundle();ProjectionService s=instance;result.putBoolean("service",s!=null);
        if(s!=null)s.main.post(()->{
            if(!s.livePreferred){s.livePreferred=true;s.getSharedPreferences("projection",0).edit().putBoolean("live",true).apply();}
            boolean changed=s.mirrorFold!=renew||renew&&SystemClock.uptimeMillis()>=s.mirrorUntil;
            s.liveUntil=renew?SystemClock.uptimeMillis()+3000:0;
            s.mirrorFold=renew;s.mirrorUntil=s.liveUntil;
            if(changed)s.update();
        });
        return result;
    }
    static void mirrorFoldTest(int seconds){ProjectionService s=instance;if(s!=null)s.main.post(()->{
        if(s.mirrorPreview!=null){s.mirrorPreview.close();s.mirrorPreview=null;}
        s.mirrorFold=seconds>0;s.mirrorUntil=seconds<=0?0:SystemClock.uptimeMillis()+Math.min(60,seconds)*1000L;s.update();
    });}
    static Bundle mirrorFrame(){
        ProjectionService s=instance;if(s==null)return new Bundle();
        Bundle b=new Bundle(s.cachedRenderFrame);
        b.putBoolean("alive",s.mirrorPreview!=null&&SystemClock.uptimeMillis()<s.mirrorUntil);
        b.putBoolean("allowed",allowed);b.putBoolean("_full",true);putFoldPose(b);
        b.putLong("stateUpdates",s.stateUpdates);
        b.putString("foregroundPackage",s.appBlacklist.foreground());b.putBoolean("appBlacklisted",s.appBlocked);
        return b;
    }
    private void cacheDisplay(Display d){
        if(d==null){if(!displayFrame.isEmpty()){displayFrame=new Bundle();displayRevision++;displaySignature=Long.MIN_VALUE;}return;}
        Point p=new Point();d.getRealSize(p);int rotation=d.getRotation();boolean inner=inner(d);
        Display.Mode mode=d.getMode();int expectedWidth=rotation%2==0?mode.getPhysicalWidth():mode.getPhysicalHeight();
        int expectedHeight=rotation%2==0?mode.getPhysicalHeight():mode.getPhysicalWidth();
        long signature=p.x;signature=31*signature+p.y;signature=31*signature+rotation;signature=31*signature+(inner?1:0);
        signature=31*signature+d.getState();signature=31*signature+expectedWidth;signature=31*signature+expectedHeight;
        if(signature==displaySignature)return;displaySignature=signature;displayRevision++;
        Bundle b=new Bundle();b.putBoolean("inner",inner);b.putInt("rotation",rotation);
        b.putLong("sceneStartedAt",entrySceneStarted(p.x,p.y,rotation,inner));
        b.putBoolean("geometryValid",p.x==expectedWidth&&p.y==expectedHeight);
        b.putInt("screenWidth",p.x);b.putInt("screenHeight",p.y);b.putInt("state",d.getState());displayFrame=b;
    }
    private Bundle buildRenderFrame(){
        Bundle b=new Bundle(displayFrame);
        b.putBoolean("alive",mirrorPreview!=null&&SystemClock.uptimeMillis()<mirrorUntil);b.putBoolean("allowed",allowed);
        b.putBoolean("standby",standby);
        b.putLong("coverToken",coverToken);b.putLong("coverStartedAt",coverStarted);b.putBoolean("coverBacking",coverBacking);
        b.putBoolean("screenFadeActive",screenFadeActive);b.putFloat("screenDarkness",screenDarkness);
        b.putInt("screenFadeMode",screenFadeMode);b.putLong("screenFadeAt",screenFadeAt);b.putFloat("screenFadeFrom",screenFadeFrom);
        b.putBoolean("foldHeld",foldHeld);
        b.putFloat("blurStrength",AnimationSettings.blurPercent/100f);
        b.putInt("stretchPercent",AnimationSettings.stretchPercent);
        b.putInt("startAngle",AnimationSettings.startAngle);
        FoldPose pose=foldPose;b.putFloat("angle",pose.angle());b.putFloat("rawAngle",pose.rawAngle);
        b.putBoolean("projectionBlocked",pose.blocksProjection());b.putBoolean("fullyOpened",pose.fullyOpened());
        return b;
    }
    static void putFoldPose(Bundle b){
        FoldPose pose=foldPose;
        b.putFloat("angle",pose.angle());b.putFloat("rawAngle",pose.rawAngle);
        b.putInt("foldStatus",pose.foldStatus);b.putBoolean("projectionBlocked",pose.blocksProjection());
        b.putInt("contactStatus",pose.contactStatus);
        b.putBoolean("closedLatched",pose.closedLatched());
        b.putInt("directContactStatus",pose.directContactStatus);
        b.putLong("directContactAgeMs",pose.directContactAgeMs(SystemClock.elapsedRealtimeNanos()));
        ProjectionService service=instance;
        if(service!=null){b.putFloat("directContactField",service.directContactField);b.putInt("directContactBit",service.directContactBit);}
        b.putInt("postureStatus",pose.postureStatus);b.putBoolean("fullyOpened",pose.fullyOpened());
    }
    static void mirrorTest(int seconds){ProjectionService s=instance;if(s!=null)s.main.post(()->{
        s.mirrorFold=false;
        s.mirrorUntil=seconds<=0?0:SystemClock.uptimeMillis()+Math.min(30,seconds)*1000L;s.update();
    });}
    static Bundle mirrorLease(){ProjectionService s=instance;return s!=null&&s.mirrorPreview!=null?s.mirrorPreview.lease():new Bundle();}
    static void mirrorObserve(int seconds){ProjectionService s=instance;if(s!=null)s.main.post(()->{s.mirrorObserveUntil=seconds<=0?0:SystemClock.uptimeMillis()+Math.min(40,seconds)*1000L;s.update();});}
    private final Runnable tick=()->{if(connected)update();};
    private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){update();} public void onDisplayRemoved(int id){update();} public void onDisplayChanged(int id){update();}
    };
    static void refreshGestureNavigation(){ProjectionService service=instance;if(service!=null)service.main.post(()->{if(service.gestureNavigation!=null)service.gestureNavigation.refresh();});}
    /** Home-drawn status bars per display: the accessibility overlay (layer 311000) covers the dormant MIUI bar (151000). */
    private static final java.util.Map<Integer,View> shadeBars=new java.util.HashMap<>();
    private static final java.util.Map<Integer,WindowManager> shadeWindows=new java.util.HashMap<>();
    private static final java.util.Map<Integer,DuoHomeActivity> shadeHosts=new java.util.HashMap<>();
    static void updateShadeBar(DuoHomeActivity host){updateShadeBar(host,0);}
    private static void updateShadeBar(DuoHomeActivity host,int attempt){
        ProjectionService s=instance;if(s==null||host==null||host.isDestroyed())return;
        if(attempt>0&&!host.shadeAlive())return;
        s.main.post(()->{
            android.view.Display display=host.getDisplay();if(display==null)return;
            int id=display.getDisplayId();
            removeShadeBarAt(id);
            Context wc=s.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
            WindowManager wm=wc.getSystemService(WindowManager.class);
            int strip=wc.getSystemService(WindowManager.class).getCurrentWindowMetrics()
                .getWindowInsets().getInsets(WindowInsets.Type.statusBars()).top;
            if(strip<=0||strip>Math.round(80*wc.getResources().getDisplayMetrics().density))strip=Math.round(28*wc.getResources().getDisplayMetrics().density);
            HomeStatusBar bar=new HomeStatusBar(wc,new ShadePullListener(){
                @Override public void onPullFired(int side,float fingerY){HomeControlPanel.beginDrag(id,side==HomeStatusBar.SIDE_CONTROL,fingerY);}
                @Override public void onPullDrag(float fingerY){HomeControlPanel.dragOn(id,fingerY);}
                @Override public void onPullRelease(float velocityPxPerMs){HomeControlPanel.releaseOn(id,velocityPxPerMs);}
            });
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,strip,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,android.graphics.PixelFormat.TRANSLUCENT);
            p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);
            p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            p.setTitle("Duo home bar");
            try{wm.addView(bar,p);}
            catch(RuntimeException failed){
                // Display teardown/recreation can reject the add transiently; retry a few
                // times instead of leaving the panel barless until the next focus change.
                Log.w("GlassHome","Shade bar overlay unavailable",failed);
                if(attempt<3)s.main.postDelayed(()->updateShadeBar(host,attempt+1),2000);
                return;
            }
            shadeBars.put(id,bar);shadeWindows.put(id,wm);shadeHosts.put(id,host);
        });
    }
    static void removeShadeBar(DuoHomeActivity host){
        ProjectionService s=instance;if(s==null||host==null)return;
        s.main.post(()->{for(Integer id:new java.util.ArrayList<>(shadeHosts.keySet()))if(shadeHosts.get(id)==host)removeShadeBarAt(id);});
    }
    private static void removeShadeBarAt(int id){
        View bar=shadeBars.remove(id);WindowManager wm=shadeWindows.remove(id);shadeHosts.remove(id);
        if(bar!=null&&wm!=null)try{wm.removeViewImmediate(bar);}catch(IllegalArgumentException ignored){}
    }
    static void recentsRequested(){ProjectionService service=instance;if(service!=null){service.recentsRequestedAt=SystemClock.uptimeMillis();service.recentsWindowId=-1;}}
    static boolean recentsVisible(){
        ProjectionService service=instance;if(service==null)return false;
        android.app.role.RoleManager roles=service.getSystemService(android.app.role.RoleManager.class);
        if(roles==null||!roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME))return false;
        for(AccessibilityWindowInfo window:service.getWindows()){
            if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||!window.isActive())continue;
            AccessibilityNodeInfo root=window.getRoot();if(root==null)return false;
            try{
                String pkg=String.valueOf(root.getPackageName());
                if(!"com.miui.home".equals(pkg)){
                    service.recentsHadTasks=false;service.main.removeCallbacks(service.recentsWatch);
                    // Keep the return armed until HOME receives focus. Clear it when a
                    // task card opens another app, so a later unrelated HOME is not animated.
                    if(!service.getPackageName().equals(pkg))DuoHomeActivity.leaveRecentsForApplication();
                    if(service.recentsWindowId>=0){service.recentsWindowId=-1;service.recentsRequestedAt=0;}return false;
                }
                boolean named=window.getId()==service.foregroundWindowId&&service.foregroundWindowClass.toLowerCase(java.util.Locale.ROOT).contains("recents");
                boolean requested=service.recentsRequestedAt>0&&SystemClock.uptimeMillis()-service.recentsRequestedAt<2000;
                boolean hasClear=containsRecentsLabel(root,"清理任务");
                boolean empty=containsRecentsLabel(root,"近期没有任何内容");
                if(hasClear)service.recentsHadTasks=true;
                if(empty&&service.recentsHadTasks){service.recentsHadTasks=false;DuoHomeActivity.requestHomeEntrance();service.performGlobalAction(GLOBAL_ACTION_HOME);}
                if(hasClear||empty){service.main.removeCallbacks(service.recentsWatch);service.main.postDelayed(service.recentsWatch,200);named=true;}
                if(named||requested||window.getId()==service.recentsWindowId){service.recentsWindowId=window.getId();DuoHomeActivity.observeRecents();return true;}
                return false;
            }finally{root.recycle();}
        }return false;
    }
    private boolean recentsHadTasks;
    private final Runnable recentsWatch=()->recentsVisible();
    private static boolean containsRecentsLabel(AccessibilityNodeInfo node,String label){
        if(label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())||label.contentEquals(node.getText()==null?"":node.getText()))return true;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)try{if(containsRecentsLabel(child,label))return true;}finally{child.recycle();}}
        return false;
    }
    @Override protected void onServiceConnected() {
        AnimationSettings.init(this);
        instance=this;connected=true;
        gestureNavigation=new GestureNavigationOverlay(this);
        livePreferred=getSharedPreferences("projection",0).getBoolean("live",false);
        sensors=getSystemService(SensorManager.class);displays=getSystemService(DisplayManager.class);
        ResolveInfo home=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);
        if(home!=null && home.activityInfo!=null)homes.add(home.activityInfo.packageName);
        // Match the vendor sensor used by this firmware's physical fold policy.
        // Never infer closure from the active panel: our controller overrides that panel.
        for(Sensor candidate:sensors.getSensorList(Sensor.TYPE_ALL)){
            if("xiaomi.sensor.fold_status".equals(candidate.getStringType())
                    &&"fold_status FOLD_STATUS Wakeup".equals(candidate.getName()))physicalFoldSensor=candidate;
            if("lhasa".equals(Build.DEVICE)&&"xiaomi.sensor.dighall".equals(candidate.getStringType())
                    &&"ak0991x Digital Hall Sensor Non-wakeup".equals(candidate.getName()))directContactSensor=candidate;
        }
        // The coarse flag stays CLOSED until about 31 degrees on lhasa.
        // Only this device's contact and posture fields have been checked
        // against real closure, flat tilt, and folding.
        boolean contactSupported="lhasa".equals(Build.DEVICE);
        foldPose=new FoldPose(physicalFoldSensor!=null,contactSupported,directContactSensor!=null);
        if(physicalFoldSensor!=null){
            boolean registered=false;
            try{registered=sensors.registerListener(this,physicalFoldSensor,20000);}
            catch(SecurityException e){Log.w("ProjectionFold","Physical fold sensor access denied",e);}
            if(!registered){
                Log.w("ProjectionFold","Physical fold sensor registration failed");
                physicalFoldSensor=null;foldPose=new FoldPose(false,contactSupported,directContactSensor!=null);
            }
        }
        if(contactSupported&&physicalFoldSensor==null)
            Log.w("ProjectionFold","Physical fold sensor unavailable on "+Build.DEVICE+"; waiting for validated direct Hall samples");
        // The controller shell UID samples dighall; app UIDs are suspended in the background.
        Sensor sensor=sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);if(sensor!=null)sensors.registerListener(this,sensor,20000);
        displays.registerDisplayListener(displayListener,main);status="已开启，等待桌面";main.post(tick);
        Log.i("ProjectionDesktop","CONNECTED homes="+homes+" physicalFold="+(physicalFoldSensor!=null)+" directContact="+(directContactSensor!=null));
        MobileHelper.start(this);
        // Activities that resumed before the accessibility connection still lack their
        // overlay bar (updateShadeBar needs instance); focus-change retries are not
        // guaranteed, so re-apply for every resumed panel once connected.
        main.post(DuoHomeActivity::refreshShadeBars);
        // Package replaces often leave the notification listener granted but unbound;
        // give the system a moment to bind by itself, then force a rebind via the helper.
        main.postDelayed(DuoNotifications::ensureBound,8000);

    }
    private boolean home() {
        refreshHomes(false);
        homeUncertain=false;
        if(getSystemService(KeyguardManager.class).isKeyguardLocked() || !getSystemService(PowerManager.class).isInteractive())return false;
        for(AccessibilityWindowInfo w:getWindows()) {
            if(w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive())continue;
            AccessibilityNodeInfo root=w.getRoot();if(root==null){homeUncertain=true;return false;}
            try {return root.getPackageName()!=null && isHomePackage(root.getPackageName().toString());}
            finally {root.recycle();}
        }
        homeUncertain=true;return false;
    }
    private int lockWindowState() {
        // isKeyguardLocked also stays true when a camera/call activity covers it.
        boolean systemUi=false;
        for(AccessibilityWindowInfo w:getWindows()) {
            if(!w.isActive()&&!w.isFocused())continue;
            if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)continue;
            AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
            try {
                String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
                if("com.android.systemui".equals(pkg))systemUi=true;
                else if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&!isHomePackage(pkg))return -1;
            } finally {root.recycle();}
        }
        return systemUi?1:0;
    }
    static void refreshAppScope(){
        ProjectionService s=instance;
        if(s!=null)s.main.post(()->{s.appScopeDirty=true;s.update();});
    }
    static void refreshHomeScope(){
        ProjectionService s=instance;
        if(s!=null)s.main.post(()->{
            // The shade strips are part of OUR desktop experience. When the user
            // defaults to another launcher, get out of the way of the native shade.
            boolean ownHome=s.getSystemService(android.app.role.RoleManager.class)!=null
                &&s.getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
            if(!ownHome&&!shadeHosts.isEmpty())
                for(Integer id:new java.util.ArrayList<>(shadeBars.keySet()))removeShadeBarAt(id);
            s.refreshHomes(true);s.appScopeDirty=true;s.update();
        });
    }
    private void refreshHomes(boolean force){
        long now=SystemClock.uptimeMillis();if(!force&&now-homesResolvedAt<2000)return;homesResolvedAt=now;
        ResolveInfo home=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);
        homes.clear();if(home!=null&&home.activityInfo!=null)homes.add(home.activityInfo.packageName);
    }
    private boolean isHomePackage(String pkg){
        // The settings, updater and HOME share a package; package identity alone is insufficient.
        return getPackageName().equals(pkg)?DuoHomeActivity.foreground:homes.contains(pkg);
    }
    private boolean appBlocked(long now,boolean locked){
        if(AnimationSettings.blacklistedApps.isEmpty())return false;
        if(appScopeDirty||now-appScopeAt>=1000){
            appScopeDirty=false;appScopeAt=now;
            String candidate=null;int layer=Integer.MIN_VALUE;boolean focused=false,unresolvedFocus=false;
            for(AccessibilityWindowInfo w:getWindows()){
                if(w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                AccessibilityNodeInfo root=w.getRoot();if(root==null){if(w.isActive()||w.isFocused())unresolvedFocus=true;continue;}
                try{
                    String pkg=root.getPackageName()==null?null:root.getPackageName().toString();
                    if(pkg==null||"com.android.systemui".equals(pkg))continue;
                    boolean current=w.isActive()||w.isFocused();
                    if(candidate==null||current&&!focused||current==focused&&w.getLayer()>layer){
                        candidate=pkg;layer=w.getLayer();focused=current;
                    }
                }finally{root.recycle();}
            }
            // A real lock screen is its own scene; a foreground camera/call can still be excluded.
            if(unresolvedFocus&&!focused)candidate=null;
            else if(locked&&!focused)candidate="com.android.systemui";
            appBlacklist.observe(candidate);
        }
        return appBlacklist.blocked(AnimationSettings.blacklistedApps);
    }
    private static String key(Display d,Point p) {return d.getMode().getPhysicalWidth()+"x"+d.getMode().getPhysicalHeight()+":"+d.getRotation()+":"+p.x+"x"+p.y;}
    private static boolean inner(Display d) {return Math.min(d.getMode().getPhysicalWidth(),d.getMode().getPhysicalHeight())>=1600;}
    private boolean motion() {return ProjectionMath.endpointOpacity(hinge,primaryInner,AnimationSettings.startAngle,foldPose.blocksProjection())>0;}
    private long[] geometry() {
        long[] value={17,0};AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null)try{if(root.getPackageName()!=null && isHomePackage(root.getPackageName().toString()))collect(root,value,0);}finally{root.recycle();}
        return value;
    }
    private void collect(AccessibilityNodeInfo node,long[] value,int depth) {
        if(depth>12 || value[1]>=160 || !node.isVisibleToUser())return;
        Rect b=new Rect();node.getBoundsInScreen(b);
        value[0]=31*(31*(31*(31*value[0]+b.left)+b.top)+b.right)+b.bottom;value[1]++;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo c=node.getChild(i);if(c!=null)try{collect(c,value,depth+1);}finally{c.recycle();}}
    }
    private boolean updateSuspended;
    private void update() {
        if(updateSuspended)return;
        try{updateState();}finally{
            signalRenderer();main.removeCallbacks(tick);
            long cadence=ProjectionCadence.delay(standby,foldPose.blocksProjection(),screenFadeActive,coverToken,pending,homeUncertain);
            // The fixed-dual branch of updateState() only re-checks power state and lets
            // maintain() keep the session alive; ticking it at 40ms keeps synchronous
            // binder calls landing on the main thread 25x/s and starves every window the
            // process renders (launcher on both virtual displays included).
            if(FixedDualSession.active())cadence=Math.max(cadence,500);
            if(connected)main.postDelayed(tick,cadence);
        }
    }
    private void updateState() {
        if(!connected)return;
        FixedDualSession.maintain(this);
        if(FixedDualSession.active()){if(gestureNavigation!=null)gestureNavigation.close();standby=!getSystemService(PowerManager.class).isInteractive();updatedAt=SystemClock.uptimeMillis();allowed=false;reset();closeWindow();return;}
        if(gestureNavigation!=null)gestureNavigation.update();
        stateUpdates++;
        foldPose=foldPose.expireDirectContact(SystemClock.elapsedRealtimeNanos());
        hinge=foldPose.angle();
        touchObservation.update(AnimationSettings.swipeRestore);
        long now=SystemClock.uptimeMillis();boolean global=AnimationSettings.globalEnabled;
        boolean home=!global&&home();if(home)lastHomeAt=now;
        Display d=displays.getDisplay(0);primaryInner=d!=null && inner(d);
        cacheDisplay(d);
        standby=!getSystemService(PowerManager.class).isInteractive();
        boolean locked=getSystemService(KeyguardManager.class).isKeyguardLocked();
        lockScreen=global?locked&&!standby:livePreferred&&lockGate.visible(locked,!standby,locked&&!standby?lockWindowState():0,now);
        appBlocked=appBlocked(now,locked);
        allowed=d!=null&&!standby&&!appBlocked&&(global || home || lockScreen || (homeUncertain && now-lastHomeAt<1000));updatedAt=now;
        holdGate.configure(AnimationSettings.holdSeconds,AnimationSettings.startAngle);
        boolean held=holdGate.update(now,hinge,allowed);
        if(held!=foldHeld){foldHeld=held;Log.i("ProjectionHold",(held?"RETURN_TO_NORMAL":"FOLLOW_HINGE")+" angle="+hinge);}
        long oldCoverToken=coverToken;boolean wasFading=screenFadeActive;
        screenFade.configure(AnimationSettings.openAngle,AnimationSettings.closeAngle);
        screenFade.update(now,hinge,primaryInner,mirrorFold&&now<mirrorUntil&&allowed&&!standby,held);
        coverToken=screenFade.token();coverStarted=screenFade.startedAt();
        coverBacking=screenFade.backing();screenDarkness=screenFade.darkness();screenFadeActive=screenFade.active();
        screenFadeMode=screenFade.mode();screenFadeAt=screenFade.phaseStartedAt();screenFadeFrom=screenFade.cancelFrom();
        if(oldCoverToken!=coverToken)Log.i("ProjectionContinuity","SCREEN_FADE token="+coverToken+" inner="+primaryInner+" elapsedMs="+(now-coverStarted));
        if(wasFading!=screenFadeActive)Log.i("ProjectionContinuity","SCREEN_FADE_ACTIVE "+screenFadeActive+" angle="+hinge+" inner="+primaryInner);
        if(mirrorPreview!=null)mirrorPreview.setBlackout(coverBacking);
        if(mirrorFold&&now<mirrorUntil&&allowed&&d!=null){
            reset();closeWindow();
            if(mirrorPreview==null&&d.getState()==Display.STATE_ON)mirrorPreview=new MirrorPreview(this,d,true);
            Point p=new Point();d.getRealSize(p);String current=key(d,p)+":"+d.getState();
            if(!current.equals(mirrorScene)){fingerSwipe.reset();mirrorScene=current;Log.i("ProjectionContinuity","DISPLAY "+current+" angle="+hinge);}
            status=foldHeld?"悬停使用中，画面已恢复正常":global?"全局开合投影运行中":lockScreen?"连续锁屏投影运行中":"连续桌面投影运行中";
            return;
        }
        if(!mirrorFold&&now<mirrorUntil && allowed && d!=null && d.getState()==Display.STATE_ON){
            Point p=new Point();d.getRealSize(p);String current=key(d,p);
            if(mirrorPreview!=null&&!current.equals(mirrorScene)){Log.i("ProjectionDesktop","MIRROR_STOP geometry "+mirrorScene+" -> "+current);mirrorUntil=0;}
            else{
                reset();closeWindow();
                if(mirrorPreview==null){mirrorScene=current;mirrorPreview=new MirrorPreview(this,d);Log.i("ProjectionDesktop","MIRROR_START "+current);}
                return;
            }
        }
        if(mirrorPreview!=null){Log.i("ProjectionDesktop","MIRROR_STOP remaining="+(mirrorUntil-now)+" home="+home+" allowed="+allowed+" display="+(d==null?-1:d.getState()));mirrorPreview.close();mirrorPreview=null;mirrorUntil=0;}
        if(now>=mirrorUntil)mirrorFold=false;
        if(appBlocked){reset();closeWindow();scene="";status="当前应用在黑名单中，动画已暂停";return;}
        if(livePreferred){reset();closeWindow();status=now<liveUntil?(global?"连续投影已就绪，等待亮屏":"连续投影已就绪，等待桌面或锁屏"):"连续投影助手未连接";return;}
        if(now<mirrorObserveUntil){reset();closeWindow();return;}
        if(d==null || d.getState()!=Display.STATE_ON || !home) {reset();closeWindow();scene="";return;}
        Point size=new Point();d.getRealSize(size);
        Display.Mode mode=d.getMode();int r=d.getRotation();
        int ew=r%2==0?mode.getPhysicalWidth():mode.getPhysicalHeight(),eh=r%2==0?mode.getPhysicalHeight():mode.getPhysicalWidth();
        if(size.x!=ew || size.y!=eh) {reset();closeWindow();scene="";return;}
        String next=key(d,size);
        if(!next.equals(scene)){reset();closeWindow();scene=next;attempts=0;}
        ensureWindow(d);
        if(window==null)return;
        if(!motion()){
            if(overlay!=null&&primaryInner&&foldPose.fullyOpened()&&overlay.finishFlat(now))return;
            if(overlay!=null || pending)reset();attempts=0;status="桌面投影已就绪";return;
        }
        long[] shape=geometry();
        if(overlay!=null){overlay.follow(hinge);if(shape[1]>=8 && shape[0]!=shownSignature){reset();attempts=0;}else return;}
        if(!gate.observe(scene,shape[0],(int)shape[1],now) || pending || now<retryAt || attempts>=3)return;
        capture(d,size,shape[0]);
    }
    private void capture(Display d,Point size,long signature) {
        pending=true;attempts++;int request=++generation;String captured=scene;int rotation=d.getRotation();boolean inner=primaryInner;
        long started=SystemClock.uptimeMillis();retryAt=started+350;status="正在准备桌面投影";
        takeScreenshot(0,getMainExecutor(),new TakeScreenshotCallback(){
            public void onSuccess(ScreenshotResult result) {
                Bitmap screenshot=null;
                try {
                    if(request!=generation || !connected)return;
                    screenshot=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
                    if(screenshot==null || screenshot.getWidth()!=size.x || screenshot.getHeight()!=size.y || geometry()[0]!=signature){pending=false;return;}
                    Bitmap owned=screenshot;screenshot=null;long captureMs=SystemClock.uptimeMillis()-started;
                    worker.execute(()->{
                        GpuLayers layers=null;
                        try {
                            layers=GpuLayers.bake(owned,inner,rotation);
                            GpuLayers ready=layers;layers=null;
                            main.post(()->finish(request,captured,size,signature,inner,rotation,started,captureMs,ready));
                        } catch(Exception e){Log.e("ProjectionDesktop","GPU bake failed",e);main.post(()->{if(request==generation){pending=false;status="准备失败，本次保留原桌面";}});}
                        finally{owned.recycle();if(layers!=null)layers.close();}
                    });
                } catch(RuntimeException e){pending=false;Log.e("ProjectionDesktop","capture callback",e);}
                finally{result.getHardwareBuffer().close();if(screenshot!=null)screenshot.recycle();}
            }
            public void onFailure(int error){if(request!=generation)return;pending=false;status="截图暂不可用";Log.w("ProjectionDesktop","CAPTURE_ERROR "+error);}
        });
    }
    private void finish(int request,String captured,Point size,long signature,boolean inner,int rotation,long started,long captureMs,GpuLayers layers) {
        if(request==generation)pending=false;
        Display d=displays.getDisplay(0);Point current=new Point();if(d!=null)d.getRealSize(current);
        boolean valid=d!=null && FrameGate.accepts(generation,request,scene,captured,size.x,size.y,current.x,current.y,
            connected && home() && motion(),d.getState()==Display.STATE_ON,SystemClock.uptimeMillis()-started) && key(d,current).equals(captured);
        if(!valid || geometry()[0]!=signature){layers.close();Log.i("ProjectionDesktop","DISCARD stale/geometry ageMs="+(SystemClock.uptimeMillis()-started));return;}
        DesktopProjection view=null;
        try {
            if(window==null){layers.close();return;}
            view=new DesktopProjection(window.getContext(),layers,inner,rotation,hinge);
            window.addView(view,new FrameLayout.LayoutParams(-1,-1));overlay=view;shownSignature=signature;status="桌面投影运行中";
            Log.i("ProjectionDesktop","SHOWN inner="+inner+" angle="+hinge+" captureMs="+captureMs+" totalMs="+(SystemClock.uptimeMillis()-started));
        }catch(Exception e){if(view!=null)view.release();else layers.close();Log.e("ProjectionDesktop","show failed",e);}
    }
    private void reset() {
        if(pending || overlay!=null)generation++;pending=false;
        if(overlay!=null){DesktopProjection old=overlay;overlay=null;if(window!=null)window.removeView(old);old.release();}
    }
    private void ensureWindow(Display display) {
        if(window!=null)return;
        Context context=createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
        manager=context.getSystemService(WindowManager.class);
        FrameLayout candidate=new FrameLayout(context);candidate.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.preferredRefreshRate=120;p.setTitle("ReferencePlane desktop projection");
        try{manager.addView(candidate,p);window=candidate;}catch(RuntimeException e){Log.e("ProjectionDesktop","warm window",e);}
    }
    private void closeWindow() {
        if(window==null)return;FrameLayout old=window;window=null;
        try{manager.removeViewImmediate(old);}catch(RuntimeException e){Log.w("ProjectionDesktop","window remove",e);}
    }
    static void pauseForUpdate(){
        ProjectionService s=instance;
        if(s==null){MobileHelper.stop();return;}
        s.updateSuspended=true;allowed=false;s.main.removeCallbacks(s.tick);
        s.touchObservation.close();
        if(s.mirrorPreview!=null){s.mirrorPreview.close();s.mirrorPreview=null;}
        s.reset();s.closeWindow();MobileHelper.stop();status="更新安装中";
    }
    static void resumeAfterUpdate(){
        ProjectionService s=instance;if(s==null||!s.updateSuspended)return;
        s.updateSuspended=false;s.touchObservation.resume();MobileHelper.start(s);s.update();
    }
    static void stop() {ProjectionService service=instance;if(service!=null)service.main.post(service::disableSelf);}
    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(FixedDualSession.active()){checkDualRecentsEmpty(e);return;}
        if(e!=null&&e.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED){if("com.miui.home".contentEquals(e.getPackageName()==null?"":e.getPackageName()))recentsVisible();return;}
        if(e!=null&&e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){foregroundWindowId=e.getWindowId();foregroundWindowClass=e.getClassName()==null?"":e.getClassName().toString();}
        recentsVisible();
        appScopeDirty=true;update();
    }
    private long lastDualRecentsHome,lastDualRecentsScan;
    /**
     * MIUI recents on a virtual display cannot return home by itself: after clearing all
     * tasks it keeps an empty "no recent items" page instead of dismissing. Watch for that
     * text and send the HOME key to that display, which relaunches the secondary home.
     * Scoped hard: one tree walk max every 3s, only on MIUI-home window-state changes —
     * scanning on every content-changed event stalled the main thread and input-ANR'd us.
     */
    private void checkDualRecentsEmpty(AccessibilityEvent e){
        if(e==null||e.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        if(!"com.miui.home".contentEquals(e.getPackageName()==null?"":e.getPackageName()))return;
        long now=SystemClock.uptimeMillis();
        if(now-lastDualRecentsScan<3000)return;
        lastDualRecentsScan=now;
        for(AccessibilityWindowInfo w:getWindows()){
            if(w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||!w.isActive())continue;
            AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
            try{
                String pkg=String.valueOf(root.getPackageName());
                if(!"com.miui.home".equals(pkg))continue;
                boolean empty=containsRecentsLabel(root,"近期没有任何内容")||containsRecentsLabel(root,"无近期任务");
                // Debug trace for the pending recents fix; drop once the behaviour is confirmed.
                if(e!=null&&e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                    Log.i("DuoFixed","recents watch display="+w.getDisplayId()+" class="+(e.getClassName()==null?"":e.getClassName())+" empty="+empty);
                if(!empty)continue;
                int displayId=w.getDisplayId();
                if(SystemClock.uptimeMillis()-lastDualRecentsHome<4000)continue;
                lastDualRecentsHome=SystemClock.uptimeMillis();
                Log.i("DuoFixed","Recents empty on display "+displayId+" -> HOME");
                MobileHelper.dualKey(displayId,android.view.KeyEvent.KEYCODE_HOME);
            }finally{root.recycle();}
        }
    }
    @Override public void onMotionEvent(MotionEvent e){
        long now=SystemClock.uptimeMillis();
        if(!AnimationSettings.swipeRestore||!touchObservation.ready()||!allowed||!e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                ||e.getPointerCount()!=1||now-e.getEventTime()<0||now-e.getEventTime()>500){fingerSwipe.reset();return;}
        int action=e.getActionMasked(),id=e.getPointerId(0),screen=TouchObservation.displayId(e);
        if(screen!=Display.DEFAULT_DISPLAY){fingerSwipe.reset();return;}
        if(action==MotionEvent.ACTION_DOWN){fingerSwipe.down(id,screen,e.getX(),e.getY(),e.getEventTime());return;}
        if(action!=MotionEvent.ACTION_MOVE){fingerSwipe.reset();return;}
        float threshold=Math.max(ViewConfiguration.get(this).getScaledTouchSlop()*2,20*getResources().getDisplayMetrics().density);
        boolean swiped=false;
        for(int i=0;i<e.getHistorySize()&&!swiped;i++)swiped=fingerSwipe.move(id,screen,e.getHistoricalX(0,i),e.getHistoricalY(0,i),e.getHistoricalEventTime(i),threshold);
        if(!swiped)swiped=fingerSwipe.move(id,screen,e.getX(),e.getY(),e.getEventTime(),threshold);
        if(swiped){
            Log.i("ProjectionTouch","FINGER_SWIPE");
            if(holdGate.restore(now,hinge,connected&&allowed&&mirrorFold&&now<mirrorUntil)){
                Log.i("ProjectionHold","FINGER_RESTORE angle="+hinge);update();
            }
        }
    }
    @Override public void onInterrupt(){reset();}
    public void onSensorChanged(SensorEvent e){
        if(e.values.length==0)return;
        FoldPose before=foldPose,next=before;
        if(e.sensor==physicalFoldSensor)next=before.withFoldEvent(e.values,e.timestamp);
        else if(e.sensor.getType()==Sensor.TYPE_HINGE_ANGLE)next=before.withAngle(e.values[0],e.timestamp);
        if(next==before)return;
        foldPose=next;
        if(next.foldStatus!=before.foldStatus||next.contactStatus!=before.contactStatus||next.postureStatus!=before.postureStatus||next.fullyOpened()!=before.fullyOpened()||next.closedLatched()!=before.closedLatched()||next.directContactStatus!=before.directContactStatus){
            fingerSwipe.reset();
            Log.i("ProjectionFold","PHYSICAL status="+next.foldStatus+" contact="+next.contactStatus+" posture="+next.postureStatus+" rawAngle="+next.rawAngle+" blocked="+next.blocksProjection()+" closedLatched="+next.closedLatched()+" directContact="+next.directContactStatus);
        }
        if(Float.compare(before.angle(),next.angle())!=0||before.blocksProjection()!=next.blocksProjection()||before.fullyOpened()!=next.fullyOpened())update();
    }
    public void onAccuracyChanged(Sensor sensor,int accuracy){}
    static void deliverDirectContact(Bundle sample){
        ProjectionService service=instance;
        if(service==null||!"lhasa".equals(Build.DEVICE))return;
        float[] values=sample.getFloatArray("directContactValues");
        final float[] copy=values==null?null:values.clone();
        long at=sample.getLong("directContactAt");boolean available=sample.getBoolean("directContactAvailable");
        service.main.post(()->{
            if(instance!=service||!service.connected)return;
            FoldPose before=foldPose;
            FoldPose next=before.withDirectContactAvailable(available).withDirectContactEvent(copy,at);
            if(next==before)return;
            foldPose=next;
            if(copy!=null&&copy.length>=9&&next.directContactAgeMs(at)==0){service.directContactField=copy[4];service.directContactBit=(int)copy[0];}
            if(next.directContactStatus!=before.directContactStatus){
                service.fingerSwipe.reset();
                Log.i("ProjectionHall","CONTACT="+next.directContactStatus+" bit="+service.directContactBit+" field="+service.directContactField+" rawAngle="+next.rawAngle+" blocked="+next.blocksProjection());
                service.update();
            }
            // Fresh unchanged Hall samples only refresh age/field. The regular
            // tick handles expiry; repeated readings need no scene/display work.
        });
    }
    @Override public void onDestroy(){FixedDualSession.stop();touchObservation.close();if(gestureNavigation!=null){gestureNavigation.close();gestureNavigation=null;}GestureNavigation.serviceStopping(this);connected=false;instance=null;allowed=false;updatedAt=0;status="已停用";MobileHelper.stop();main.removeCallbacks(tick);if(mirrorPreview!=null){mirrorPreview.close();mirrorPreview=null;}reset();closeWindow();if(sensors!=null)sensors.unregisterListener(this);if(displays!=null)displays.unregisterDisplayListener(displayListener);worker.shutdown();super.onDestroy();}
}
