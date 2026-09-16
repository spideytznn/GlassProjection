package io.github.sixzleo.tabfold.projection;

import android.content.*;
import android.hardware.display.*;
import android.os.*;
import android.view.*;
import java.util.*;

/** Shell-owned task displays. Physical display topology is owned separately for the whole session. */
final class FixedDualContentHost implements AutoCloseable {
    private final Map<Integer,VirtualDisplay> displays=new java.util.concurrent.ConcurrentHashMap<>();
    private final Context shell;
    FixedDualContentHost(Context context)throws Exception{shell=context.createPackageContext("com.android.shell",0);}
    int create(Surface surface,int width,int height,int density,boolean inner)throws Exception{
        if(surface==null||!surface.isValid()||width<100||height<100||width>4096||height>4096||density<100||density>800)
            throw new IllegalArgumentException("Invalid output");
        if(displays.size()>=2)throw new IllegalStateException("Both content displays already exist");
        int flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        for(String field:new String[]{"VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS","VIRTUAL_DISPLAY_FLAG_TRUSTED","VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH","VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP","VIRTUAL_DISPLAY_FLAG_OWN_FOCUS","VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL"})
            flags|=DisplayManager.class.getField(field).getInt(null);
        VirtualDisplay display=shell.getSystemService(DisplayManager.class).createVirtualDisplay("Duo "+(inner?"inner":"cover")+" content",width,height,density,surface,flags);
        if(display==null)throw new IllegalStateException("Content display rejected");
        int id=display.getDisplay().getDisplayId();displays.put(id,display);
        try{
            IBinder windowBinder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
            Object wm=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,windowBinder);
            Class<?> api=Class.forName("android.view.IWindowManager");
            api.getMethod("setDisplayImePolicy",int.class,int.class).invoke(wm,id,0);
            int ime=(Integer)api.getMethod("getDisplayImePolicy",int.class).invoke(wm,id);
            if(ime!=0)throw new IllegalStateException("Local keyboard policy rejected");
            launchHome(id);return id;
        }catch(Exception e){displays.remove(id);display.release();throw e;}
    }
    private synchronized void launchHome(int id)throws Exception{
        if(!displays.containsKey(id))return;
        ProcessBuilder builder=new ProcessBuilder("am","start","--display",String.valueOf(id),"--activityType","2",
            "-a","android.intent.action.MAIN","-c","android.intent.category.SECONDARY_HOME","-n",
            "io.github.sixzleo.tabfold.projection/.DuoSecondaryActivity","-f","0x10000000").redirectErrorStream(true);
        java.lang.Process process=builder.start();String result;
        try(java.io.InputStream in=process.getInputStream()){result=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        if(process.waitFor()!=0||result.contains("Error:"))throw new IllegalStateException(result);
    }
    void touch(int displayId,MotionEvent event)throws Exception{
        if(!displays.containsKey(displayId)||event==null)return;
        MotionEvent copy=MotionEvent.obtain(event);
        try{MotionEvent.class.getMethod("setDisplayId",int.class).invoke(copy,displayId);inject(copy);}finally{copy.recycle();}
    }
    void key(int displayId,int keyCode)throws Exception{
        if(!displays.containsKey(displayId)||(keyCode!=KeyEvent.KEYCODE_BACK&&keyCode!=KeyEvent.KEYCODE_HOME&&keyCode!=KeyEvent.KEYCODE_APP_SWITCH))return;
        if(keyCode==KeyEvent.KEYCODE_APP_SWITCH){
            java.lang.Process process=new ProcessBuilder("am","start","--display",String.valueOf(displayId),"--activityType","3","-n","com.miui.home/.recents.RecentsActivity").redirectErrorStream(true).start();
            try(java.io.InputStream in=process.getInputStream()){String result=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(result.contains("Error:"))throw new IllegalStateException(result);}if(process.waitFor()!=0)throw new IllegalStateException("Recent tasks launch failed");return;
        }
        if(keyCode==KeyEvent.KEYCODE_HOME){launchHome(displayId);return;}
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP}){
            KeyEvent event=new KeyEvent(now,now,action,keyCode,0);KeyEvent.class.getMethod("setDisplayId",int.class).invoke(event,displayId);inject(event);
        }
    }
    private void inject(InputEvent event)throws Exception{
        Object manager=Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null);
        manager.getClass().getMethod("injectInputEvent",InputEvent.class,int.class).invoke(manager,event,0);
    }
    @Override public synchronized void close(){for(VirtualDisplay display:displays.values())display.release();displays.clear();}
}
