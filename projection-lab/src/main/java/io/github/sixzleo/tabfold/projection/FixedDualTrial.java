package io.github.sixzleo.tabfold.projection;

import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;

/** Bounded commissioning of topology and physical input curtains, before attaching virtual content. */
final class FixedDualTrial {
    static String status="idle";
    private static FixedDualTrial current;
    private final ProjectionService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final FixedDualPolicy policy;
    private final FixedDualCurtains curtains;
    private final int seconds;
    private boolean closed;
    private long deadline;
    private FixedDualTrial(ProjectionService service,int seconds){this.service=service;this.seconds=seconds;policy=new FixedDualPolicy(ProjectionService.primaryInner);curtains=new FixedDualCurtains(service);}
    static void start(ProjectionService service,int seconds){
        if(current!=null||service==null){status="ERROR trial already active or service unavailable";return;}
        current=new FixedDualTrial(service,Math.max(5,Math.min(30,seconds)));current.begin();
    }
    private void begin(){
        status="preparing fixed state "+policy.requestedState();
        main.postDelayed(this::finish,(seconds+5)*1000L);
        MobileHelper.fixedDualState(policy.requestedState(),result->{
            if(closed){MobileHelper.fixedDualState(-1,ignored->{});return;}
            if(!result.startsWith("OK")){status=result;finish();return;}
            deadline=SystemClock.uptimeMillis()+seconds*1000L;awaitPanels(SystemClock.uptimeMillis()+2000);
        });
    }
    private void awaitPanels(long limit){
        if(closed)return;
        try{curtains.prepare();tick.run();}
        catch(Exception e){
            if(SystemClock.uptimeMillis()<limit)main.postDelayed(()->awaitPanels(limit),50);
            else{status="ERROR curtains "+e;finish();}
        }
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;if(SystemClock.uptimeMillis()>=deadline){status="completed";finish();return;}
        try{
            Display primary=service.getSystemService(DisplayManager.class).getDisplay(0);
            String expected=policy.requestedState()==5?"local:4639175402683733248":"local:4639175068132267009";
            String actual=(String)org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(Display.class,primary,"getUniqueId");
            if(!expected.equals(actual))throw new IllegalStateException("Physical primary mapping changed");
            // The legacy projection angle intentionally collapses to zero when its Hall helper stops.
            // A fixed session stops that helper, so consume the independent hinge stream.
            float angle=ProjectionService.foldPose.rawAngle;
            FoldPose pose=ProjectionService.foldPose;
            boolean closed=pose.contactStatus==0||(pose.foldStatus==1&&angle<=1);
            boolean opened=pose.postureStatus==3&&angle>=175;
            FixedDualPolicy.Panel active=policy.update(opened,closed,
                Float.isFinite(angle),!ProjectionService.standby);
            curtains.showOnly(active);status="running fixed="+policy.requestedState()+" active="+active+" angle="+angle+" primary="+actual;
            main.postDelayed(this,40);
        }catch(Exception e){status="ERROR "+e;finish();}
    }};
    private void finish(){if(closed)return;closed=true;main.removeCallbacksAndMessages(null);
        MobileHelper.fixedDualState(-1,result->{curtains.close();current=null;status+="; "+result;});}
}
