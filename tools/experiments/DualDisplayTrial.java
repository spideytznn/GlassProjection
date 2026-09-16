package io.github.sixzleo.tabfold.probe;

import android.content.AttributionSource;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.opengl.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.FileLock;

/** Bounded physical-display trial. Owns only its surface and a process-bound state request. */
public final class DualDisplayTrial {
  static Object global,dm,provider,am;
  static Method cancel,request,getInfo,providerCall;
  static IBinder providerToken;
  static SurfaceControl layer;
  static Surface surface;
  static RandomAccessFile controllerFile,rendererFile;
  static FileLock controllerLock,rendererLock;
  static final String AUTH="io.github.sixzleo.tabfold.projection.surface";
  static final String ROOT="/data/local/tmp/";
  static volatile long progress;
  static volatile boolean done;
  static boolean requested;
  static int supervisor=-1;
  static java.lang.Process recovery;
  static EGLDisplay egl=EGL14.EGL_NO_DISPLAY;
  static EGLContext gl=EGL14.EGL_NO_CONTEXT;
  static EGLSurface win=EGL14.EGL_NO_SURFACE;
  static DualLiveRenderer live;
  static DualLiveRenderer.Output livePrimary,liveSecondary;
  static DualSnapshotMasks masks;
  static String mode;
  static int exitCode;
  static PhysicalDisplayAccess powerAccess;
  static IBinder keepToken;
  static Thread powerWorker;
  static volatile boolean stopPower;
  static volatile long powerDeadline;
  static volatile int powerCalls;
  static void startPowerTrial(){
    startPowerTrial(1250,15,60);
  }
  static void startPowerTrial(int durationMs,int intervalMs,int maxCalls){
    powerDeadline=SystemClock.uptimeMillis()+durationMs;
    powerWorker=new Thread(()->{
      int count=0;long maxCost=0;
      try{
        while(!stopPower&&SystemClock.uptimeMillis()<powerDeadline&&count<maxCalls){
          long at=SystemClock.uptimeMillis();powerAccess.on(keepToken);powerCalls=++count;
          long cost=SystemClock.uptimeMillis()-at;
          maxCost=Math.max(maxCost,cost);
          if(cost>50)System.out.println("POWER_ON call="+count+" duration="+cost);
          Thread.sleep(intervalMs);
        }
        System.out.println("POWER_ON finished calls="+count+" maxCallMs="+maxCost);
      }catch(Throwable t){t.printStackTrace();}
    },"bounded-inner-on");powerWorker.setDaemon(true);powerWorker.start();
  }
  static void render(Bundle data)throws Exception {
    live.update();
    if(!live.hasFrame)return;
    if(livePrimary!=null)live.draw(livePrimary,data);
    live.draw(liveSecondary,data);
  }
  static boolean sameGeometry(Object info,DualLiveRenderer.Output out)throws Exception {
    return out==null||(field(info,"logicalWidth")==out.width&&field(info,"logicalHeight")==out.height&&field(info,"rotation")==out.rotation&&field(info,"layerStack")==out.stack);
  }
  static int field(Object o,String name)throws Exception{return o.getClass().getField(name).getInt(o);}
  static String unique(Object o)throws Exception{return (String)o.getClass().getField("uniqueId").get(o);}
  static boolean isSupervisor(int pid){
    try(FileInputStream f=new FileInputStream("/proc/"+pid+"/cmdline")){
      byte[] b=new byte[256];int n=f.read(b),end=0;while(end<n&&b[end]!=0)end++;
      return new String(b,0,end,java.nio.charset.StandardCharsets.UTF_8).equals("io.github.sixzleo.tabfold.projection:glass_helpers");
    }catch(Exception e){return false;}
  }
  static void resumeSupervisor(){if(supervisor>0&&isSupervisor(supervisor))android.os.Process.sendSignal(supervisor,18);}
  static Bundle call(String method,String arg)throws Exception {
    return (Bundle)providerCall.invoke(provider,new AttributionSource.Builder(2000).setPackageName("com.android.shell").build(),AUTH,method,arg,null);
  }
  static FileLock acquire(RandomAccessFile file,String stop)throws Exception {
    long end=SystemClock.uptimeMillis()+6000;
    while(SystemClock.uptimeMillis()<end){
      new File(stop).createNewFile();
      FileLock lock=file.getChannel().tryLock();
      if(lock!=null)return lock;
      Thread.sleep(10);
    }
    throw new IOException("Cannot obtain helper lock");
  }
  static void paint(int w,int h,int remaining,Bitmap source)throws Exception {
    if(egl==EGL14.EGL_NO_DISPLAY){
      egl=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] v=new int[2];
      if(!EGL14.eglInitialize(egl,v,0,v,1))throw new IOException("EGL initialize");
      EGLConfig[] cfg=new EGLConfig[1];int[] count=new int[1];
      EGL14.eglChooseConfig(egl,new int[]{EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE},0,cfg,0,1,count,0);
      gl=EGL14.eglCreateContext(egl,cfg[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
      win=EGL14.eglCreateWindowSurface(egl,cfg[0],surface,new int[]{EGL14.EGL_NONE},0);
      if(!EGL14.eglMakeCurrent(egl,win,win,gl))throw new IOException("EGL make current");
    }
    GLES20.glViewport(0,0,w,h);GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
    for(int x=0;x<8;x++)for(int y=0;y<10;y++){
      GLES20.glScissor(x*w/8,y*h/10,w/8+1,h/10+1);
      GLES20.glClearColor(.08f+x*.055f,.12f+y*.06f,.6f-((x+y)%2)*.2f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }
    GLES20.glScissor(0,h-28,w,28);GLES20.glClearColor(.04f,.04f,.04f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    GLES20.glScissor(0,h-28,w*Math.min(remaining,35)/35,28);GLES20.glClearColor(.1f,1f,.75f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    if(!EGL14.eglSwapBuffers(egl,win))throw new IOException("EGL swap");GLES20.glFinish();
  }
  public static void main(String[] args)throws Exception {
    if(args.length>0&&args[0].equals("recover")){
      supervisor=Integer.parseInt(args[1]);SystemClock.sleep(55000);resumeSupervisor();return;
    }
    Looper.prepareMainLooper();
    Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
    int seconds=Math.max(5,Math.min(35,args.length>0?Integer.parseInt(args[0]):20));
    mode=args.length>1?args[1]:"pattern";
    final boolean snapshotMode=mode.equals("angle-snapshot");
    final boolean angleLive=mode.equals("angle-live")||snapshotMode;
    final boolean angleMode=mode.equals("angle-on")||angleLive;
    if(!mode.equals("pattern")&&!mode.equals("cover")&&!mode.equals("live")&&!mode.equals("fold")&&!mode.equals("handoff")&&!mode.equals("handoff-on")&&!angleMode)throw new IllegalArgumentException("Unknown trial mode");
    Bitmap source=null;
    progress=SystemClock.uptimeMillis();
    Thread watchdog=new Thread(()->{while(!done){SystemClock.sleep(500);if(SystemClock.uptimeMillis()-progress>6000)Runtime.getRuntime().halt(3);}},"trial-watchdog");
    watchdog.setDaemon(true);watchdog.start();
    try{
      Class<?> api=Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal");
      global=api.getMethod("getInstance").invoke(null);cancel=api.getMethod("cancelStateRequest");
      request=api.getMethod("requestState",Class.forName("android.hardware.devicestate.DeviceStateRequest"),java.util.concurrent.Executor.class,Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback"));
      dm=Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
      getInfo=dm.getClass().getMethod("getDisplayInfo",int.class);
      Object primary=getInfo.invoke(dm,0),secondary=getInfo.invoke(dm,1);
      if(primary==null||secondary==null||field(primary,"state")!=2)throw new IllegalStateException("Both display records and an ON primary required");
      String primaryId=unique(primary);
      boolean inner=primaryId.equals("local:4639175402683733248");
      if(!inner&&!primaryId.equals("local:4639175068132267009"))throw new IllegalStateException("Unrecognized physical device");
      if(args.length>1&&"cover".equals(args[1])&&inner)throw new IllegalStateException("This trial requires the cover to be the ON primary");
      if(angleMode&&inner)throw new IllegalStateException("angle-on requires the cover primary; prepare a small opening first");
      int state=inner?5:6;
      am=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);providerToken=new Binder();
      Object holder=Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class).invoke(am,AUTH,0,providerToken,"GlassDualTrial");
      if(holder==null)throw new IllegalStateException("App telemetry unavailable");
      provider=holder.getClass().getField("provider").get(holder);
      providerCall=Class.forName("android.content.IContentProvider").getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class);
      Bundle before=call("desktop-telemetry",null);
      if(before.getBoolean("standby")||before.getBoolean("lockScreen")||!before.getBoolean("allowed"))throw new IllegalStateException("Unlocked allowed scene required");
      if(before.getInt("contactStatus")!=1)throw new IllegalStateException("Open a small gap before the trial");
      int opening=before.getInt("openAngle",60),preAngle=Math.max(4,opening-12);
      if(angleMode&&(!Float.isFinite(before.getFloat("angle"))||before.getFloat("angle")>=preAngle))throw new IllegalStateException("Start below "+preAngle+" degrees; configured handoff="+opening);
      if(mode.equals("handoff-on")||angleMode){powerAccess=new PhysicalDisplayAccess();keepToken=powerAccess.token(4639175402683733248L);if(keepToken==null)throw new IllegalStateException("No inner physical token");}
      for(String name:new File("/proc").list())try{int pid=Integer.parseInt(name);if(isSupervisor(pid)){supervisor=pid;break;}}catch(NumberFormatException ignored){}
      if(supervisor<0)throw new IllegalStateException("Helper supervisor not found");
      ProcessBuilder recoveryBuilder=new ProcessBuilder("/system/bin/app_process","/system/bin","io.github.sixzleo.tabfold.probe.DualDisplayTrial","recover",Integer.toString(supervisor));
      recoveryBuilder.environment().put("CLASSPATH",ROOT+"glass-dual-trial.dex");
      recoveryBuilder.redirectInput(new File("/dev/null")).redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null"));recovery=recoveryBuilder.start();
      android.os.Process.sendSignal(supervisor,19);
      System.out.println("SUPERVISOR paused; independent recovery armed");
      controllerFile=new RandomAccessFile(ROOT+"tabfold-projection-controller.lock","rw");
      controllerLock=acquire(controllerFile,ROOT+"tabfold-projection-controller.stop");progress=SystemClock.uptimeMillis();
      rendererFile=new RandomAccessFile(ROOT+"tabfold-live.lock","rw");
      rendererLock=acquire(rendererFile,ROOT+"tabfold-live.stop");progress=SystemClock.uptimeMillis();
      call("mirror-live","0");Thread.sleep(200);
      primary=getInfo.invoke(dm,0);secondary=getInfo.invoke(dm,1);
      if(angleMode&&!primaryId.equals(unique(primary))){
        // Releasing the installed controller may expose a different base pose.
        // Restore the starting cover mapping before any angle-driven power burst.
        Object anchor=Class.forName("android.hardware.devicestate.DeviceStateRequest").getMethod("newBuilder",int.class).invoke(null,state);
        requested=true;request.invoke(global,anchor.getClass().getMethod("build").invoke(anchor),null,null);
        long anchorEnd=SystemClock.uptimeMillis()+2500;
        do{
          progress=SystemClock.uptimeMillis();primary=getInfo.invoke(dm,0);secondary=getInfo.invoke(dm,1);
          if(primaryId.equals(unique(primary))&&field(primary,"state")==2&&field(primary,"committedState")==2)break;
          Thread.sleep(20);
        }while(SystemClock.uptimeMillis()<anchorEnd);
        if(!primaryId.equals(unique(primary))||field(primary,"state")!=2||field(primary,"committedState")!=2)throw new IllegalStateException("Starting cover mapping did not stabilize");
        Bundle prepared=call("desktop-telemetry",null);
        if(prepared.getBoolean("lockScreen")||prepared.getBoolean("standby")||!prepared.getBoolean("allowed")||prepared.getInt("contactStatus")!=1||!Float.isFinite(prepared.getFloat("angle"))||prepared.getFloat("angle")>=preAngle)throw new IllegalStateException("Keep the small opening until the trial reports ready");
        System.out.println("ANCHOR starting cover restored before angle test");
      }
      if(!primaryId.equals(unique(primary)))throw new IllegalStateException("Primary changed during preparation");
      int sw=field(secondary,"logicalWidth"),sh=field(secondary,"logicalHeight");
      int w=sw/2,h=sh/2,stack=field(secondary,"layerStack");
      if(mode.equals("live")||mode.equals("fold")||angleLive){
        live=new DualLiveRenderer(field(primary,"logicalWidth"),field(primary,"logicalHeight"),io.github.sixzleo.tabfold.projection.ProjectionMath.turn(inner,field(primary,"rotation")),mode.equals("fold"));
        live.rightHalfMirror=angleLive;
        liveSecondary=new DualLiveRenderer.Output(live,sw,sh,field(secondary,"rotation"),stack,!inner);
        if(mode.equals("fold"))livePrimary=new DualLiveRenderer.Output(live,field(primary,"logicalWidth"),field(primary,"logicalHeight"),field(primary,"rotation"),field(primary,"layerStack"),inner);
        long frameDeadline=SystemClock.uptimeMillis()+3000;
        while(!live.hasFrame&&SystemClock.uptimeMillis()<frameDeadline){live.update();Thread.sleep(5);progress=SystemClock.uptimeMillis();}
        if(!live.hasFrame)throw new IllegalStateException("No source frame before lighting secondary");
        render(call("desktop-telemetry",null));
        liveSecondary.show();if(livePrimary!=null)livePrimary.show();
        if(snapshotMode)masks=new DualSnapshotMasks(live,liveSecondary,field(primary,"logicalWidth"),field(primary,"logicalHeight"),field(primary,"rotation"));
      }else if(!angleMode){
      layer=new SurfaceControl.Builder().setName("Glass bounded dual display trial").setBufferSize(w,h).setOpaque(true).setHidden(true).build();
      surface=Surface.class.getConstructor(SurfaceControl.class).newInstance(layer);
      paint(w,h,seconds,source);
      try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
        SurfaceControl.Transaction.class.getMethod("setLayerStack",SurfaceControl.class,int.class).invoke(t,layer,stack);
        SurfaceControl.Transaction.class.getMethod("setSkipScreenshot",SurfaceControl.class,boolean.class).invoke(t,layer,true);
        t.setLayer(layer,2000000).setScale(layer,2f,2f).setPosition(layer,0,0).setVisibility(layer,true).apply();
      }
      }
      Thread.sleep(200);progress=SystemClock.uptimeMillis();
      System.out.println("PREPARED mode="+mode+" primaryInner="+inner+" target="+sw+"x"+sh+" stack="+stack+" state="+state);
      if(angleMode)System.out.println("ANGLE_PLAN burstAt="+preAngle+" switchAt="+opening+" intervalMs=1 maxBurstMs=6000 postSwitchMs=1500 outputLayers="+(angleLive?1:0));
      Object builder=Class.forName("android.hardware.devicestate.DeviceStateRequest").getMethod("newBuilder",int.class).invoke(null,state);
      requested=true;request.invoke(global,builder.getClass().getMethod("build").invoke(builder),null,null);
      if(angleMode){
        long readyEnd=SystemClock.uptimeMillis()+2500;
        do{
          progress=SystemClock.uptimeMillis();primary=getInfo.invoke(dm,0);secondary=getInfo.invoke(dm,1);
          if(primaryId.equals(unique(primary))&&field(primary,"state")==2&&field(primary,"committedState")==2&&field(secondary,"state")==2&&field(secondary,"committedState")==2)break;
          Thread.sleep(20);
        }while(SystemClock.uptimeMillis()<readyEnd);
        if(!primaryId.equals(unique(primary))||field(primary,"state")!=2||field(primary,"committedState")!=2||field(secondary,"state")!=2||field(secondary,"committedState")!=2)throw new IllegalStateException("Both panels must be committed ON before angle testing");
        System.out.println("DUAL_READY state=6 primary=cover secondary=inner; both committed ON");
      }
      long start=SystemClock.uptimeMillis(),end=start+seconds*1000,frameWaitEnd=0,maskDeadline=0;int lastSecond=-1,draws=0;String last="",capturePrimaryId=primaryId;boolean exchanged=false;
      while(SystemClock.uptimeMillis()<end){
        progress=SystemClock.uptimeMillis();primary=getInfo.invoke(dm,0);secondary=getInfo.invoke(dm,1);
        if(!mode.startsWith("handoff")&&!angleMode&&!primaryId.equals(unique(primary))){System.out.println("EXIT primary mapping changed");break;}
        Bundle data=call("desktop-telemetry",null);
        if(data.getBoolean("standby")||data.getBoolean("lockScreen")||!data.getBoolean("allowed")||SystemClock.uptimeMillis()-data.getLong("updatedAt")>1500){System.out.println("EXIT scene unavailable");break;}
        float angle=data.getFloat("angle");
        if(data.getInt("contactStatus")==0||!Float.isFinite(angle)){System.out.println("EXIT invalid/opening ended");break;}
        if(angleMode&&!exchanged){
          if(powerWorker==null&&angle>=preAngle){
            System.out.println("PRE_ON t="+(progress-start)+" angle="+angle+" switchAt="+opening);
            startPowerTrial(6000,1,5000);
            if(masks!=null){live.update();masks.capture();System.out.println("SNAPSHOT_READY t="+(SystemClock.uptimeMillis()-start)+" sourceTimestamp="+live.sourceTimestamp);}
          }
          if(powerWorker!=null&&(angle<preAngle-4||SystemClock.uptimeMillis()>=powerDeadline||!powerWorker.isAlive())){
            System.out.println("EXIT burst reversed or expired before handoff");break;
          }
        }
        if(live!=null){
          boolean changed=!capturePrimaryId.equals(unique(primary))||!sameGeometry(primary,livePrimary)||!sameGeometry(secondary,liveSecondary)||field(primary,"logicalWidth")!=live.sourceWidth||field(primary,"logicalHeight")!=live.sourceHeight;
          if(changed){
            if(!angleLive){System.out.println("EXIT geometry changed");break;}
            boolean sourceInner=unique(primary).equals("local:4639175402683733248");
            if(masks!=null&&sourceInner&&!masks.remapped){masks.remap();System.out.println("SNAPSHOT_REMAP t="+(SystemClock.uptimeMillis()-start));}
            live.update();liveSecondary.close();liveSecondary=null;
            live.rebindSource(field(primary,"logicalWidth"),field(primary,"logicalHeight"),io.github.sixzleo.tabfold.projection.ProjectionMath.turn(sourceInner,field(primary,"rotation")));
            liveSecondary=new DualLiveRenderer.Output(live,field(secondary,"logicalWidth"),field(secondary,"logicalHeight"),field(secondary,"rotation"),field(secondary,"layerStack"),!sourceInner);
            capturePrimaryId=unique(primary);frameWaitEnd=SystemClock.uptimeMillis()+3000;
            System.out.println("MIRROR_REBIND t="+(SystemClock.uptimeMillis()-start)+" primaryInner="+sourceInner+" source="+live.sourceWidth+"x"+live.sourceHeight+" destination="+liveSecondary.viewWidth+"x"+liveSecondary.viewHeight+" rightHalf="+(sourceInner?"source":"destination"));
          }
          render(data);draws++;
          if(angleLive&&frameWaitEnd!=0){
            if(live.hasFrame){liveSecondary.show();frameWaitEnd=0;System.out.println("MIRROR_READY t="+(SystemClock.uptimeMillis()-start));}
            else if(SystemClock.uptimeMillis()>frameWaitEnd)throw new IllegalStateException("No new primary frame after role swap");
          }
          if(masks!=null&&exchanged&&!masks.finished){
            boolean ready=unique(primary).equals("local:4639175402683733248")&&field(primary,"state")==2&&field(primary,"committedState")==2&&field(secondary,"state")==2&&field(secondary,"committedState")==2&&live.hasFrame&&frameWaitEnd==0;
            if(masks.settle(ready,SystemClock.uptimeMillis(),live.frames))System.out.println("SNAPSHOT_RELEASED t="+(SystemClock.uptimeMillis()-start));
            if(!masks.finished&&SystemClock.uptimeMillis()>maskDeadline)throw new IllegalStateException("Snapshot readiness timed out");
          }
        }
        if(!exchanged&&((mode.startsWith("handoff")&&progress-start>=6000)||(angleMode&&powerWorker!=null&&angle>=opening))){
          int opposite=inner?6:5;
          Object next=Class.forName("android.hardware.devicestate.DeviceStateRequest").getMethod("newBuilder",int.class).invoke(null,opposite);
          System.out.println("HANDOFF request t="+(progress-start)+" target="+opposite+" angle="+angle+" powerCallsBefore="+powerCalls);
          if(mode.equals("handoff-on"))startPowerTrial();
          if(angleMode){powerDeadline=Math.min(powerDeadline,SystemClock.uptimeMillis()+1500);end=Math.min(end,SystemClock.uptimeMillis()+12000);}
          if(masks!=null)maskDeadline=SystemClock.uptimeMillis()+3500;
          exchanged=true;request.invoke(global,next.getClass().getMethod("build").invoke(next),null,null);
        }
        String status="primary="+field(primary,"state")+" secondary="+field(secondary,"state")+" primaryInner="+unique(primary).equals("local:4639175402683733248")+" primaryCommitted="+field(primary,"committedState")+" secondaryCommitted="+field(secondary,"committedState");
        if(!status.equals(last)){System.out.println("DISPLAY t="+(progress-start)+" "+status);last=status;}
        int remaining=(int)((end-progress+999)/1000);
        if(remaining!=lastSecond){if(layer!=null)paint(w,h,remaining,source);System.out.println("TICK remaining="+remaining+" angle="+angle+" contact="+data.getInt("contactStatus")+" draws="+draws+" sourceFrames="+(live==null?0:live.frames)+" powerCalls="+powerCalls);lastSecond=remaining;}
        if(data.getInt("contactStatus")==0){System.out.println("EXIT physically closed");break;}
        Thread.sleep(Math.max(1,16-(SystemClock.uptimeMillis()-progress)));
      }
    }catch(Throwable e){exitCode=1;e.printStackTrace();}
    finally{
      try{
      progress=SystemClock.uptimeMillis();
      stopPower=true;if(powerWorker!=null)powerWorker.join(2000);
      try{if(requested)cancel.invoke(global);}catch(Exception e){e.printStackTrace();}
      try{if(masks!=null)masks.close();}catch(Exception e){e.printStackTrace();}
      try{if(livePrimary!=null)livePrimary.close();}catch(Exception e){e.printStackTrace();}
      try{if(liveSecondary!=null)liveSecondary.close();}catch(Exception e){e.printStackTrace();}
      try{if(live!=null)live.close();}catch(Exception e){e.printStackTrace();}
      try{if(layer!=null)try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){t.setVisibility(layer,false).reparent(layer,null).apply();}}catch(Exception e){e.printStackTrace();}
      if(egl!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(egl,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(win!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(egl,win);if(gl!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(egl,gl);EGL14.eglTerminate(egl);}
      if(surface!=null)surface.release();if(layer!=null)layer.release();if(source!=null)source.recycle();
      try{if(providerToken!=null)Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class).invoke(am,AUTH,providerToken,0);}catch(Exception e){e.printStackTrace();}
      if(rendererLock!=null)rendererLock.release();if(rendererFile!=null)rendererFile.close();
      if(controllerLock!=null)controllerLock.release();if(controllerFile!=null)controllerFile.close();
      if(rendererFile!=null)new File(ROOT+"tabfold-live.stop").delete();if(controllerFile!=null)new File(ROOT+"tabfold-projection-controller.stop").delete();
      }finally{
      resumeSupervisor();if(recovery!=null)recovery.destroy();
      done=true;System.out.println("CLEANED state released; original helper supervisor resumes");
      }
    }
    System.exit(exitCode);
  }
}
