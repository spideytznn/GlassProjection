package io.github.sixzleo.tabfold.probe;

import android.content.Context;
import android.os.*;
import android.view.SurfaceControl;
import java.lang.reflect.*;

/** Read-only capability inventory; same-on optionally repeats ON on an already committed ON main panel. */
public final class NoRootDisplayProbe {
    public static void main(String[] args)throws Exception {
        try{run(args);System.exit(0);}catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        Looper.prepareMainLooper();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        System.out.println("UID "+android.os.Process.myUid());
        for(String permission:new String[]{"CONTROL_DEVICE_STATE","MANAGE_DISPLAYS","ACCESS_SURFACE_FLINGER","INTERNAL_SYSTEM_WINDOW","CAPTURE_VIDEO_OUTPUT","INJECT_EVENTS","MONITOR_INPUT","ASSOCIATE_INPUT_DEVICE_TO_DISPLAY","MANAGE_VIRTUAL_DEVICE","DEVICE_POWER"})
            System.out.println("PERMISSION "+permission+" "+context.checkPermission("android.permission."+permission,android.os.Process.myPid(),android.os.Process.myUid()));
        Method check=Class.forName("android.os.ServiceManager").getMethod("checkService",String.class);
        for(String name:new String[]{"display","displayfeature","SurfaceFlingerAIDL","vendor.xiaomi.hardware.display.mihwcextension.IMiHwcExtension/default","vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature/default","vendor.xring.hardware.display.composerext.IComposerExt/default","android.hardware.graphics.composer3.IComposer/default"}){
            try{
                IBinder binder=(IBinder)check.invoke(null,name);System.out.println("SERVICE "+name+" "+(binder==null?"not accessible":binder.getInterfaceDescriptor()));
                if(binder!=null&&name.startsWith("vendor.")){
                    Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                    try{boolean ok=binder.transact(IBinder.INTERFACE_TRANSACTION,data,reply,0);System.out.println("DESCRIPTOR_QUERY "+name+" handled="+ok+" replyBytes="+reply.dataSize());}
                    finally{data.recycle();reply.recycle();}
                }
            }
            catch(Throwable t){System.out.println("SERVICE "+name+" "+t);}
        }
        for(String cls:new String[]{"android.view.SurfaceControl","android.view.SurfaceControl$Transaction","android.hardware.display.IDisplayManager","android.hardware.input.IInputManager","android.view.IWindowManager"}){
            try{for(Method m:Class.forName(cls).getDeclaredMethods())if(m.getName().matches("(?i).*(power|physicaldisplay|displayprojection|displaylayerstack|displaystate|uniqueidassociation|monitorGesture|fold|displaycontentmode).*"))System.out.println("API "+m.toString());}
            catch(Throwable t){System.out.println("CLASS "+cls+" "+t);}
        }
        Object dm=Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Method info=dm.getClass().getMethod("getDisplayInfo",int.class);
        for(int id=0;id<2;id++){
            Object d=info.invoke(dm,id);
            if(d==null)continue;
            System.out.println("DISPLAY "+id+" unique="+d.getClass().getField("uniqueId").get(d)+" state="+d.getClass().getField("state").getInt(d)+" committed="+d.getClass().getField("committedState").getInt(d));
        }
        if(args.length>0&&args[0].equals("same-on")){
            Object d=info.invoke(dm,0);
            if(d.getClass().getField("state").getInt(d)!=2||d.getClass().getField("committedState").getInt(d)!=2)throw new IllegalStateException("Committed ON primary required");
            String unique=(String)d.getClass().getField("uniqueId").get(d);
            if(!unique.equals("local:4639175402683733248")&&!unique.equals("local:4639175068132267009"))throw new IllegalStateException("Known physical panel required");
            PhysicalDisplayAccess access=new PhysicalDisplayAccess();
            IBinder token=access.token(Long.parseLong(unique.substring(6)));
            if(token==null)throw new IllegalStateException("No physical token");
            long start=SystemClock.uptimeMillis();
            access.on(token);
            System.out.println("SAME_ON returned in "+(SystemClock.uptimeMillis()-start)+"ms; no assertion of a persistent power hold");
        }
    }
}
