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
    private long readyUntil,lastFrame;
    private float angle=Float.NaN;
    private static long nextStart;
    static boolean active(){return current!=null;}
    static boolean enabled(android.content.Context c){return c.getSharedPreferences("duo_dual",0).getBoolean("enabled",false);}
    static void setEnabled(android.content.Context c,boolean enabled){c.getSharedPreferences("duo_dual",0).edit().putBoolean("enabled",enabled).apply();if(enabled)start(ProjectionService.instance);else stop();}
    static void maintain(ProjectionService service){
        boolean ownHome=service.getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
        if(!ownHome){stop();return;}
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
    private FixedDualSession(ProjectionService service){this.service=service;primary=identity(service.getSystemService(DisplayManager.class).getDisplay(0));policy=new FixedDualPolicy(primary.equals("local:4639175402683733248"));}
    private static String identity(Display display){try{return (String)org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(Display.class,display,"getUniqueId");}catch(Exception e){return "";}}
    private void begin(){
        status="preparing";main.postDelayed(()->{if(outputs.size()!=2||outputs.stream().anyMatch(o->o.contentId<0))fail("双屏内容准备超时");},15000);
        MobileHelper.fixedDualState(policy.requestedState(),result->{if(closed)return;if(!result.startsWith("OK")){fail(result);return;}readyUntil=SystemClock.uptimeMillis()+3000;pollContact();prepare();});
    }
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
            for(Display display:dm.getDisplays()){
                String id=identity(display);boolean inner=id.equals("local:4639175402683733248");
                if(!inner&&!id.equals("local:4639175068132267009"))continue;
                if(outputs.stream().noneMatch(o->o.inner==inner))outputs.add(new FixedDualOutput(service,display,inner,this));
            }
            if(outputs.size()<2){if(SystemClock.uptimeMillis()>readyUntil)throw new IllegalStateException("Missing physical panel");main.postDelayed(this::prepare,50);}
        }catch(Exception e){fail(e.toString());}
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
            status="running primary="+primary+" visible="+visible+" angle="+raw+" content="+outputs.get(0).contentId+","+outputs.get(1).contentId;
            Choreographer.getInstance().postFrameCallback(this);
        }catch(Exception e){fail(e.toString());}
    }};
    void fail(String reason){status="ERROR "+reason;android.util.Log.e("DuoFixed",status);close();}
    private void close(){
        if(closed)return;closed=true;main.removeCallbacksAndMessages(null);Choreographer.getInstance().removeFrameCallback(frame);
        for(FixedDualOutput output:outputs)output.close();outputs.clear();
        MobileHelper.fixedDualState(-1,result->{if(current==this)current=null;if(!status.startsWith("ERROR"))status="stopped; "+result;});
    }
}
