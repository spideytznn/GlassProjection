package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.nio.file.*;

/** Shizuku or our paired local ADB starts this shell host, which runs bundled helpers only. */
public final class MobileHelperHost extends IHelperHost.Stub {
    private final int appUid;
    private final Context context;
    private java.lang.Process renderer,controller;
    private boolean running;
    private boolean gestureConfigured;
    private IBinder fixedLifetime;
    private final IBinder.DeathRecipient fixedDeath=()->{synchronized(this){releaseFixedState();}};
    private int fixedState=-1;
    private FixedDualContentHost dualContent;
    private FixedDualContact dualContact;
    private Object fixedStateGlobal;
    private java.lang.reflect.Method fixedCancel;
    private static final String ROOT="/data/local/tmp/";
    public MobileHelperHost(Context context)throws IOException {
        this.context=context;
        appUid=context.getApplicationInfo().uid;
        if(android.os.Process.myUid()!=2000)throw new SecurityException("ADB shell mode required");
        // Take over an earlier ADB-started instance using its existing stop protocol.
        new File(ROOT+"tabfold-live.stop").createNewFile();new File(ROOT+"tabfold-projection-controller.stop").createNewFile();
        SystemClock.sleep(1200);
        install(context,"live.dex","tabfold-continuous-probe.dex");
        install(context,"controller.dex","tabfold-projection-controller.dex");
    }
    private void caller(){int uid=Binder.getCallingUid();if(uid!=appUid&&uid!=2000&&uid!=0)throw new SecurityException("Own app only");}
    private static void install(Context c,String asset,String name)throws IOException {
        File target=new File(ROOT+name),pending=new File(ROOT+name+".mobile-pending");
        Files.deleteIfExists(pending.toPath());
        try(InputStream in=c.getAssets().open("helpers/"+asset);OutputStream out=new FileOutputStream(pending)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        // Android requires dynamically loaded code to be read-only.
        if(!pending.setReadOnly())throw new IOException("Cannot protect bundled helper");
        Files.move(pending.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
    private static java.lang.Process start(String dex,String main,String log,String... args)throws IOException {
        java.util.ArrayList<String> command=new java.util.ArrayList<>();command.add("/system/bin/app_process");command.add("/system/bin");command.add(main);java.util.Collections.addAll(command,args);
        ProcessBuilder p=new ProcessBuilder(command);p.environment().put("CLASSPATH",ROOT+dex);
        p.redirectInput(new File("/dev/null"));p.redirectErrorStream(true);p.redirectOutput(new File(ROOT+log));return p.start();
    }
    @Override public synchronized int ensureRunning(){
        caller();running=true;
        if(fixedState>=0)return 4;
        try {
            new File(ROOT+"tabfold-live.stop").delete();new File(ROOT+"tabfold-projection-controller.stop").delete();
            if(controller==null||!controller.isAlive())controller=start("tabfold-projection-controller.dex","io.github.sixzleo.tabfold.probe.EarlyDisplayHelper","tabfold-projection-controller.log","0","io.github.sixzleo.tabfold.projection.surface");
            if(renderer==null||!renderer.isAlive())renderer=start("tabfold-continuous-probe.dex","io.github.sixzleo.tabfold.probe.LiveMirrorWindowProbe","tabfold-live.log","live");
            return (renderer.isAlive()?1:0)|(controller.isAlive()?2:0);
        }catch(IOException e){android.util.Log.e("MobileHelperHost","start",e);return 0;}
    }
    @Override public synchronized void stopHelpers(){caller();stop();}
    private void stop(){
        releaseFixedState();
        running=false;
        try{new File(ROOT+"tabfold-live.stop").createNewFile();new File(ROOT+"tabfold-projection-controller.stop").createNewFile();}catch(IOException ignored){}
        if(renderer!=null)renderer.destroy();if(controller!=null)controller.destroy();renderer=null;controller=null;
    }
    private void releaseFixedState(){
        if(dualContent!=null){dualContent.close();dualContent=null;}
        if(dualContact!=null){dualContact.close();dualContact=null;}
        if(fixedStateGlobal!=null)try{fixedCancel.invoke(fixedStateGlobal);}catch(Exception e){android.util.Log.w("DuoFixed","Release failed",e);}
        if(fixedLifetime!=null){fixedLifetime.unlinkToDeath(fixedDeath,0);fixedLifetime=null;}
        fixedState=-1;fixedStateGlobal=null;fixedCancel=null;
    }
    @Override public synchronized String fixedDualState(int state,IBinder lifetime){
        caller();long identity=Binder.clearCallingIdentity();
        try{
            if(state==-1){releaseFixedState();return "OK released";}
            if(state!=5&&state!=6)return "ERROR unsupported fixed state";
            if(fixedState>=0)return state==fixedState?"OK already fixed":"ERROR primary mapping cannot change during a session";
            Object dm=Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            Object info=dm.getClass().getMethod("getDisplayInfo",int.class).invoke(dm,0);
            String expected=state==5?"local:4639175402683733248":"local:4639175068132267009";
            if(info==null||!expected.equals(info.getClass().getField("uniqueId").get(info)))return "ERROR requested state would exchange primary";
            if(lifetime==null||!lifetime.isBinderAlive())return "ERROR owner unavailable";
            java.lang.Process oldController=controller;stop();
            fixedLifetime=lifetime;lifetime.linkToDeath(fixedDeath,0);
            if(oldController!=null&&!oldController.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Controller did not stop");
            // stop() destroys the owned processes. Taking its lock proves the old state owner exited.
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(ROOT+"tabfold-projection-controller.lock","rw")){
                java.nio.channels.FileLock lock=file.getChannel().tryLock();
                if(lock==null)throw new IOException("Previous display controller still owns its lock");
                try{
                    info=dm.getClass().getMethod("getDisplayInfo",int.class).invoke(dm,0);
                    if(!expected.equals(info.getClass().getField("uniqueId").get(info)))throw new IOException("Primary changed while releasing old controller");
                    Class<?> type=Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal");
                    fixedStateGlobal=type.getMethod("getInstance").invoke(null);fixedCancel=type.getMethod("cancelStateRequest");
                    Class<?> request=Class.forName("android.hardware.devicestate.DeviceStateRequest");
                    Object builder=request.getMethod("newBuilder",int.class).invoke(null,state);
                    type.getMethod("requestState",request,java.util.concurrent.Executor.class,Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback"))
                        .invoke(fixedStateGlobal,builder.getClass().getMethod("build").invoke(builder),null,null);
                    fixedState=state;dualContact=new FixedDualContact(context);return "OK fixed state="+state+" primary="+expected;
                }finally{lock.release();}
            }
        }catch(Exception e){releaseFixedState();return "ERROR "+e;}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    @Override public synchronized Bundle dualContact(){caller();return dualContact==null?new Bundle():dualContact.sample();}
    @Override public synchronized int createDualContent(android.view.Surface surface,int width,int height,int density,boolean inner){
        caller();long token=Binder.clearCallingIdentity();
        try{if(fixedState<0)throw new IllegalStateException("Fixed topology required");
            if(dualContent==null)dualContent=new FixedDualContentHost(context);
            return dualContent.create(surface,width,height,density,inner);
        }catch(Exception e){android.util.Log.e("DuoFixed","Content creation failed",e);return -1;}
        finally{Binder.restoreCallingIdentity(token);}
    }
    @Override public synchronized void dualTouch(int displayId,android.view.MotionEvent event){
        caller();long token=Binder.clearCallingIdentity();
        try{if(dualContent!=null)dualContent.touch(displayId,event);}catch(Exception e){android.util.Log.e("DuoFixed","Touch failed",e);}
        finally{Binder.restoreCallingIdentity(token);}
    }
    @Override public synchronized void dualKey(int displayId,int keyCode){
        caller();long token=Binder.clearCallingIdentity();
        try{if(dualContent!=null)dualContent.key(displayId,keyCode);}catch(Exception e){android.util.Log.e("DuoFixed","Key failed",e);}
        finally{Binder.restoreCallingIdentity(token);}
    }
    @Override public synchronized String status(){caller();return "uid="+android.os.Process.myUid()+" requested="+running+" renderer="+(renderer!=null&&renderer.isAlive())+" controller="+(controller!=null&&controller.isAlive());}
    @Override public synchronized boolean observeTouch(IBinder binder,boolean enabled){
        caller();if(Build.VERSION.SDK_INT<34)return false;
        long identity=Binder.clearCallingIdentity();
        Object connection=null;java.lang.reflect.Method setter=null;
        android.accessibilityservice.AccessibilityServiceInfo info=null;
        try{
            if(enabled&&context.checkPermission("android.permission.ACCESSIBILITY_MOTION_EVENT_OBSERVING",android.os.Process.myPid(),2000)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Shell motion observing permission missing");
            Class<?> api=Class.forName("android.accessibilityservice.IAccessibilityServiceConnection");
            connection=Class.forName("android.accessibilityservice.IAccessibilityServiceConnection$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            info=(android.accessibilityservice.AccessibilityServiceInfo)api.getMethod("getServiceInfo").invoke(connection);
            if(info==null||info.getResolveInfo()==null||info.getResolveInfo().serviceInfo.applicationInfo.uid!=appUid
                    ||!ProjectionService.class.getName().equals(info.getResolveInfo().serviceInfo.name))
                throw new SecurityException("Own accessibility connection only");
            setter=api.getMethod("setServiceInfo",android.accessibilityservice.AccessibilityServiceInfo.class);
            int source=enabled?android.view.InputDevice.SOURCE_TOUCHSCREEN:0;
            // Submit both masks together under the already authorized shell identity.
            // A consuming-only touchscreen subscription would block the user's gestures.
            info.setMotionEventSources(source);
            java.lang.reflect.Method observed=info.getClass().getMethod("setObservedMotionEventSources",int.class);
            observed.invoke(info,source);setter.invoke(connection,info);
            android.accessibilityservice.AccessibilityServiceInfo actual=(android.accessibilityservice.AccessibilityServiceInfo)api.getMethod("getServiceInfo").invoke(connection);
            int mask=(Integer)info.getClass().getMethod("getObservedMotionEventSources").invoke(actual);
            if(actual.getMotionEventSources()!=source||mask!=source)throw new IllegalStateException("Observing mask rejected");
            android.util.Log.i("ProjectionTouch","OBSERVING="+enabled+" sources="+source+" observed="+mask);
            return true;
        }catch(Exception e){
            if(setter!=null&&info!=null)try{info.setMotionEventSources(0);setter.invoke(connection,info);}catch(Exception ignored){}
            android.util.Log.w("ProjectionTouch","Observer unavailable",e);return false;
        }finally{Binder.restoreCallingIdentity(identity);}
    }
    private static String command(String... values)throws IOException,InterruptedException {
        java.lang.Process process=new ProcessBuilder(values).redirectErrorStream(true).start();
        String output;
        try(InputStream in=process.getInputStream()){output=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();}
        int result=process.waitFor();
        if(result!=0)throw new IOException(java.util.Arrays.toString(values)+" exited "+result+": "+output);
        return output;
    }
    @Override public synchronized String configureGestureNavigation(boolean enabled){
        caller();long identity=Binder.clearCallingIdentity();
        try{
            if(enabled){
                command("/system/bin/settings","put","global","force_fsg_nav_bar","1");
                command("/system/bin/settings","put","secure","navigation_mode","2");
                command("/system/bin/cmd","overlay","enable","--user","0","com.android.internal.systemui.navbar.gestural");
            }else{
                command("/system/bin/settings","put","secure","navigation_mode","0");
                command("/system/bin/settings","put","global","force_fsg_nav_bar","0");
                // Xiaomi may recycle the helper's package context as soon as this
                // overlay changes. Restore the two authoritative values first.
                command("/system/bin/cmd","overlay","disable","--user","0","com.android.internal.systemui.navbar.gestural");
            }
            gestureConfigured=enabled;
            return "OK "+gestureNavigationStatus();
        }catch(Exception e){android.util.Log.e("GlassGestures","navigation mode",e);return "ERROR "+e.getMessage();}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    @Override public synchronized String gestureNavigationStatus(){
        caller();long identity=Binder.clearCallingIdentity();
        try{return "force="+command("/system/bin/settings","get","global","force_fsg_nav_bar")+" mode="+command("/system/bin/settings","get","secure","navigation_mode");}
        catch(Exception e){return "ERROR "+e.getMessage();}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    @Override public synchronized void destroy(){
        caller();
        if(gestureConfigured)try{
            String enabled=command("/system/bin/settings","get","secure","enabled_accessibility_services");
            if(!enabled.contains("io.github.sixzleo.tabfold.projection/io.github.sixzleo.tabfold.projection.ProjectionService"))configureGestureNavigation(false);
        }catch(Exception e){android.util.Log.w("GlassGestures","Unable to verify navigation recovery",e);}
        stop();System.exit(0);
    }
}
