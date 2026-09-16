package io.github.sixzleo.tabfold.probe;

import android.content.Context;
import android.hardware.display.*;
import android.hardware.input.InputManager;
import android.graphics.SurfaceTexture;
import android.view.*;
import android.os.*;

/** Capability check: creates and releases one offscreen display; never changes physical state or input. */
public final class FixedDualCapabilityProbe {
    public static void main(String[] args)throws Exception{
        Looper.prepareMainLooper();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context system=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        Context context=system.createPackageContext("com.android.shell",0);
        for(String permission:new String[]{"CONTROL_DEVICE_STATE","INJECT_EVENTS","DISABLE_INPUT_DEVICE","MANAGE_INPUT_DEVICES","ADD_TRUSTED_DISPLAY","INTERNAL_SYSTEM_WINDOW"})
            System.out.println("PERMISSION "+permission+"="+context.checkPermission("android.permission."+permission,android.os.Process.myPid(),2000));
        for(int id:InputDevice.getDeviceIds()){
            InputDevice device=InputDevice.getDevice(id);if(device==null||!device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN))continue;
            System.out.println("TOUCH id="+id+" name="+device.getName()+" descriptor="+device.getDescriptor());
            try{System.out.println("ASSOCIATED_DISPLAY="+InputDevice.class.getMethod("getAssociatedDisplayId").invoke(device));}catch(Exception e){System.out.println(e);}
        }
        for(java.lang.reflect.Method method:InputManager.class.getDeclaredMethods())
            if(method.getName().matches(".*(nableInputDevice|isplayId|injectInputEvent).*"))System.out.println("INPUT_API "+method);
        int flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        for(String field:new String[]{"VIRTUAL_DISPLAY_FLAG_TRUSTED","VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH","VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP","VIRTUAL_DISPLAY_FLAG_OWN_FOCUS","VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL"}){
            try{int value=DisplayManager.class.getField(field).getInt(null);flags|=value;System.out.println("FLAG "+field+"="+value);}catch(Exception e){System.out.println("MISSING "+field);}
        }
        SurfaceTexture texture=new SurfaceTexture(false);texture.setDefaultBufferSize(640,480);Surface surface=new Surface(texture);
        VirtualDisplay display=null;
        try{
            display=context.getSystemService(DisplayManager.class).createVirtualDisplay("Duo-capability-check",640,480,160,surface,flags);
            if(display==null)throw new IllegalStateException("virtual display rejected");
            int id=display.getDisplay().getDisplayId();
            Object global=Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            Object info=global.getClass().getMethod("getDisplayInfo",int.class).invoke(global,id);
            System.out.println("VIRTUAL id="+id+" "+info);
            if(args.length>0&&args[0].equals("task")){
                java.lang.Process process=new ProcessBuilder("am","start","--display",String.valueOf(id),"-a","android.settings.SETTINGS","-f","0x18000000").redirectErrorStream(true).start();
                try(java.io.InputStream in=process.getInputStream()){System.out.println(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}process.waitFor();
                Thread.sleep(1500);
                java.lang.Process dump=new ProcessBuilder("dumpsys","activity","activities").redirectErrorStream(true).start();
                try(java.io.InputStream in=dump.getInputStream()){String data=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);for(String line:data.split("\\n"))if(line.contains("displayId="+id)||line.contains("Display #"+id)||line.contains("topResumedActivity"))System.out.println(line);}
                dump.waitFor();
            }
        }finally{if(display!=null)display.release();surface.release();texture.release();}
        System.out.println("RELEASED; physical displays and input untouched");System.exit(0);
    }
}
