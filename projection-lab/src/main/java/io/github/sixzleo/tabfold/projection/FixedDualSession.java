package io.github.sixzleo.tabfold.projection;

import android.app.KeyguardManager;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import java.util.*;

/** One owner for the fixed physical topology and both persistent content pipelines. */
final class FixedDualSession {
    private static FixedDualSession current;
    static volatile String status="idle";
    private final ProjectionService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<FixedDualOutput> outputs=new ArrayList<>();
    private final FixedDualPolicy policy;
    private final String primary;
    private boolean closed;
    private int touchedDisplay=-1;
    void touched(int id){touchedDisplay=id;}
    static void nativeHomeRequested(){if(current!=null&&current.touchedDisplay>=0)MobileHelper.dualKey(current.touchedDisplay,KeyEvent.KEYCODE_HOME);}
    private long readyUntil,lastFrame,statusAt;
    private float angle=Float.NaN;
    private static long nextStart;
    static boolean active(){return current!=null;}
    /** True while this session draws an independent output over that physical display. */
    static boolean outputOn(int displayId){
        FixedDualSession s=current;
        if(s==null)return false;
        for(FixedDualOutput o:s.outputs)if(o.physicalId==displayId)return true;
        return false;
    }
    /** Display ids of this session's live content surfaces; empty while starting or after close. */
    static Set<Integer> activeContentIds(){
        FixedDualSession s=current;
        if(s==null)return Collections.emptySet();
        Set<Integer> ids=new HashSet<>();
        for(FixedDualOutput o:s.outputs)if(o.contentId>=0)ids.add(o.contentId);
        return ids;
    }
    /** Virtual content display mirroring a physical panel; -1 when unknown. */
    static int contentIdForPhysical(int physicalId){
        FixedDualSession s=current;
        if(s==null)return -1;
        for(FixedDualOutput o:s.outputs)if(o.physicalId==physicalId&&o.contentId>=0)return o.contentId;
        return -1;
    }
    /** Fixed dual is the one advertised desktop mode; the legacy single-screen pipeline stays dormant unless disabled via adb. */
    static boolean enabled(android.content.Context c){return c.getSharedPreferences("duo_dual",0).getBoolean("enabled",true);}
    static void setEnabled(android.content.Context c,boolean enabled){c.getSharedPreferences("duo_dual",0).edit().putBoolean("enabled",enabled).apply();if(enabled)start(ProjectionService.instance);else stop();}
    private static long ownHomeSince;
    static void maintain(ProjectionService service){
        DuoHomeActivity.validateSecondaryHomes();
        if(nextStart==0)nextStart=SystemClock.uptimeMillis()+8000; // let a predecessor's death-release of the display topology settle before the first flip
        boolean ownHome=service.getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
        if(!ownHome){ownHomeSince=0;stop();return;}
        if(ownHomeSince==0)ownHomeSince=SystemClock.uptimeMillis();
        // A role grant lands mid home transition; requesting the fixed display topology at
        // that exact moment blacked out the whole device. Let the transition settle first.
        if(SystemClock.uptimeMillis()-ownHomeSince<2500)return;
        if(active()||!enabled(service)||!MobileHelper.ready()||SystemClock.uptimeMillis()<nextStart)return;
        if(!service.getSystemService(PowerManager.class).isInteractive()||service.getSystemService(KeyguardManager.class).isKeyguardLocked())return;
        nextStart=SystemClock.uptimeMillis()+10000;start(service);
    }
    static void start(ProjectionService service){
        if(current!=null)return;
        nextStart=SystemClock.uptimeMillis()+10000;
        if(service==null){status="ERROR service unavailable";return;}
        current=new FixedDualSession(service);current.begin();
    }
    static void stop(){if(current!=null)current.close();}
    /** ADB experiment hook: collapse or restore one output's gesture strip in place. */
    static String setBand(int displayId,boolean reserve){
        FixedDualSession s=current;if(s==null)return "ERROR no session";
        final Object[] result={"ERROR display not found"};
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        s.main.post(()->{try{for(FixedDualOutput o:s.outputs)if(o.contentId==displayId){o.setBandReserved(reserve);result[0]="OK band "+(reserve?"reserved":"collapsed");break;}}finally{done.countDown();}});
        try{done.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}
        return (String)result[0];
    }
    private FixedDualSession(ProjectionService service){this.service=service;primary=identity(service.getSystemService(DisplayManager.class).getDisplay(0));policy=new FixedDualPolicy(primary.equals("local:4639175402683733248"));}
    private static String identity(Display display){try{return (String)org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(Display.class,display,"getUniqueId");}catch(Exception e){return "";}}
    private void begin(){
        status="preparing";main.postDelayed(()->{if(outputs.size()!=2||outputs.stream().anyMatch(o->o.contentId<0))fail("双屏内容准备超时");},15000);
        // The Choreographer frame loop dies with vsync when the panel sleeps, so its
        // keyguard check never runs and the opaque overlays plus fixed topology would trap
        // the lock screen's input. Close on the broadcast instead; maintain() re-arms later.
        try{
            android.content.IntentFilter screen=new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF);
            service.registerReceiver(screenEvents,screen);
        }catch(RuntimeException ignored){}
        MobileHelper.fixedDualState(policy.requestedState(),result->{if(closed)return;if(!result.startsWith("OK")){fail(result);return;}readyUntil=SystemClock.uptimeMillis()+3000;pollContact();prepare();});
    }
    private final android.content.BroadcastReceiver screenEvents=new android.content.BroadcastReceiver(){
        @Override public void onReceive(android.content.Context context,android.content.Intent intent){
            if(android.content.Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))stop();
        }
    };
    private void pollContact(){
        if(closed)return;
        MobileHelper.dualContact(sample->{if(closed)return;
            if(sample!=null)ProjectionService.deliverDirectContact(sample);
            main.postDelayed(this::pollContact,40);
        });
    }
    private void prepare(){
        if(closed)return;
        try{
            DisplayManager dm=service.getSystemService(DisplayManager.class);
            Display innerDisplay=null,coverDisplay=null;
            for(Display display:dm.getDisplays()){
                String id=identity(display);
                if(id.equals("local:4639175402683733248"))innerDisplay=display;
                else if(id.equals("local:4639175068132267009"))coverDisplay=display;
            }
            if(innerDisplay!=null&&outputs.stream().noneMatch(o->o.inner))
                outputs.add(new FixedDualOutput(service,innerDisplay,true,0,this));
            if(coverDisplay!=null&&outputs.stream().noneMatch(o->!o.inner))
                outputs.add(new FixedDualOutput(service,coverDisplay,false,compensatedDensity(innerDisplay,coverDisplay),this));
            if(outputs.size()<2){if(SystemClock.uptimeMillis()>readyUntil)throw new IllegalStateException("Missing physical panel");main.postDelayed(this::prepare,50);}
        }catch(Exception e){fail(e.toString());}
    }
    /**
     * Both panels report the same nominal densityDpi while their real PPI differs, so one dp
     * renders at a different physical size per screen. Anchor to the inner panel and scale
     * the cover density by the PPI ratio for exact physical parity. This ROM normalizes the
     * app-visible xdpi to densityDpi, so the lhasa panel PPIs (from the display viewports)
     * are used when the metrics cannot tell the panels apart.
     */
    static volatile String densityInfo="";
    private static int compensatedDensity(Display innerDisplay,Display coverDisplay){
        try{
            if(innerDisplay==null||coverDisplay==null)return 0;
            android.util.DisplayMetrics innerMetrics=new android.util.DisplayMetrics();innerDisplay.getRealMetrics(innerMetrics);
            android.util.DisplayMetrics coverMetrics=new android.util.DisplayMetrics();coverDisplay.getRealMetrics(coverMetrics);
            if(innerMetrics.xdpi<=0||coverMetrics.xdpi<=0)return 0;
            float ratio=coverMetrics.xdpi/innerMetrics.xdpi;
            if(Math.abs(ratio-1f)<0.002f&&"lhasa".equals(android.os.Build.DEVICE))
                ratio=385.288f/381.913f; // cover PPI / inner PPI, measured from the viewports
            int base=coverMetrics.densityDpi,compensated=Math.round(base*ratio);
            densityInfo="metrics xdpi="+(int)innerMetrics.xdpi+"/"+(int)coverMetrics.xdpi+" ratio="+String.format(java.util.Locale.ROOT,"%.4f",ratio)+" -> "+compensated;
            return compensated>=100&&compensated<=800&&compensated!=base?compensated:0;
        }catch(Exception e){return 0;}
    }
    void contentReady(){if(closed)return;if(outputs.size()==2&&outputs.stream().allMatch(o->o.contentId>=0)){status="running";Choreographer.getInstance().postFrameCallback(frame);}}
    private final Choreographer.FrameCallback frame=new Choreographer.FrameCallback(){public void doFrame(long nanos){
        if(closed)return;
        try{
            DisplayManager dm=service.getSystemService(DisplayManager.class);
            if(!primary.equals(identity(dm.getDisplay(0)))){fail("Physical primary unexpectedly changed");return;}
            FoldPose pose=ProjectionService.foldPose.expireDirectContact(SystemClock.elapsedRealtimeNanos());float raw=pose.angle();
            boolean asleep=!service.getSystemService(PowerManager.class).isInteractive();
            boolean locked=service.getSystemService(KeyguardManager.class).isKeyguardLocked();
            if(locked){close();return;}
            boolean contact=pose.directContactStatus==0;
            boolean flat=pose.fullyOpened()&&pose.directContactStatus==1;
            long now=SystemClock.uptimeMillis();
            float target=contact?0:flat?180:raw;
            angle=contact?0:flat?180:ProjectionMath.followAngle(angle,target,lastFrame==0?16:now-lastFrame);lastFrame=now;
            FixedDualPolicy.Panel visible=policy.update(flat,contact,Float.isFinite(raw)&&pose.directContactStatus>=0,!asleep);
            for(FixedDualOutput output:outputs)output.frame(angle,visible==FixedDualPolicy.Panel.NONE||visible==FixedDualPolicy.Panel.INNER&&!output.inner||visible==FixedDualPolicy.Panel.COVER&&output.inner);
            if(now-statusAt>=250){statusAt=now;status="running primary="+primary+" visible="+visible+" angle="+raw+" content="+outputs.get(0).contentId+","+outputs.get(1).contentId
                +" dpi="+outputs.get(0).density+"/"+outputs.get(1).density+" "+densityInfo;}
            Choreographer.getInstance().postFrameCallback(this);
        }catch(Exception e){fail(e.toString());}
    }};
    void fail(String reason){status="ERROR "+reason;android.util.Log.e("DuoFixed",status);close();}
    private void close(){
        if(closed)return;closed=true;main.removeCallbacksAndMessages(null);Choreographer.getInstance().removeFrameCallback(frame);
        try{service.unregisterReceiver(screenEvents);}catch(RuntimeException ignored){}
        for(FixedDualOutput output:outputs)output.close();outputs.clear();
        MobileHelper.fixedDualState(-1,result->{
            if(current==this){current=null;DuoHomeActivity.validateSecondaryHomes();}
            if(!status.startsWith("ERROR"))status="stopped; "+result;});
    }
}
