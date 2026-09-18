package io.github.sixzleo.tabfold.projection;

import android.content.*;
import android.hardware.display.*;
import android.os.*;
import android.view.*;
import java.util.*;

/** Shell-owned task displays. Physical display topology is owned separately for the whole session. */
final class FixedDualContentHost implements AutoCloseable {
    private final Map<Integer,VirtualDisplay> displays=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer,Integer> densities=new java.util.concurrent.ConcurrentHashMap<>();
    private final Context shell;
    FixedDualContentHost(Context context)throws Exception{shell=context.createPackageContext("com.android.shell",0);}
    int create(Surface surface,int width,int height,int density,boolean inner)throws Exception{
        if(surface==null||!surface.isValid()||width<100||height<100||width>4096||height>4096||density<100||density>800)
            throw new IllegalArgumentException("Invalid output");
        if(displays.size()>=2)throw new IllegalStateException("Both content displays already exist");
        int flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        for(String field:new String[]{"VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS","VIRTUAL_DISPLAY_FLAG_TRUSTED","VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH","VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP","VIRTUAL_DISPLAY_FLAG_OWN_FOCUS","VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL"})
            flags|=DisplayManager.class.getField(field).getInt(null);
        // Virtual displays get one generated mode and HyperOS defaults it to 60Hz; the rate
        // must be requested at creation (API 35 config), with a surface vote as pre-35 fallback.
        String name="Duo "+(inner?"inner":"cover")+" content";
        VirtualDisplay display;
        if(android.os.Build.VERSION.SDK_INT>=35)
            display=shell.getSystemService(DisplayManager.class).createVirtualDisplay(new VirtualDisplayConfig.Builder(name,width,height,density).setSurface(surface).setFlags(flags).setRequestedRefreshRate(120f).build());
        else{display=shell.getSystemService(DisplayManager.class).createVirtualDisplay(name,width,height,density,surface,flags);
            surface.setFrameRate(120,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);}
        if(display==null)throw new IllegalStateException("Content display rejected");
        int id=display.getDisplay().getDisplayId();displays.put(id,display);densities.put(id,density);
        if(displays.size()==1)applyPrimaryRate(true);
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
    // Per-event reflection on the touch path shows up as jitter at 120Hz input; resolve once.
    private static final java.lang.reflect.Method SET_DISPLAY_ID;
    private static final Object INPUT_MANAGER;
    private static final java.lang.reflect.Method INJECT;
    static{
        java.lang.reflect.Method id=null,inject=null;Object manager=null;
        try{id=MotionEvent.class.getMethod("setDisplayId",int.class);}catch(Exception ignored){}
        try{Class<?> global=Class.forName("android.hardware.input.InputManagerGlobal");
            manager=global.getMethod("getInstance").invoke(null);
            inject=global.getMethod("injectInputEvent",InputEvent.class,int.class);}catch(Exception ignored){}
        SET_DISPLAY_ID=id;INPUT_MANAGER=manager;INJECT=inject;
    }
    void touch(int displayId,MotionEvent event)throws Exception{
        if(!displays.containsKey(displayId)||event==null||SET_DISPLAY_ID==null)return;
        MotionEvent copy=MotionEvent.obtain(event);
        try{SET_DISPLAY_ID.invoke(copy,displayId);inject(copy);}finally{copy.recycle();}
    }
    /** Hot-swaps the virtual display's output surface: the fold shader's input or, when the
     *  panel is settled flat, the TextureView itself so frames skip the GL round trip. */
    void surface(int displayId,Surface surface){
        VirtualDisplay display=displays.get(displayId);
        if(display!=null&&surface!=null)display.setSurface(surface);
    }
    /** Resizes a content display so immersive apps can fill the panel when the strip collapses. */
    void resize(int displayId,int width,int height){
        VirtualDisplay display=displays.get(displayId);Integer density=densities.get(displayId);
        if(display!=null&&density!=null)display.resize(width,height,density);
    }
    void key(int displayId,int keyCode)throws Exception{
        if(!displays.containsKey(displayId)||(keyCode!=KeyEvent.KEYCODE_BACK&&keyCode!=KeyEvent.KEYCODE_HOME&&keyCode!=KeyEvent.KEYCODE_APP_SWITCH))return;
        if(keyCode==KeyEvent.KEYCODE_APP_SWITCH){
            // MIUI recents on a virtual display routes restored tasks to invisible displays
            // and starves input focus until the launcher ANRs. Our own switcher sheet is
            // driven instead; it launches through the display-scoped LauncherApps path.
            shell.sendBroadcast(new android.content.Intent("io.github.sixzleo.tabfold.projection.OPEN_RECENTS")
                .setPackage("io.github.sixzleo.tabfold.projection").putExtra("display",displayId));
            return;
        }
        if(keyCode==KeyEvent.KEYCODE_HOME){launchHome(displayId);return;}
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP}){
            KeyEvent event=new KeyEvent(now,now,action,keyCode,0);KeyEvent.class.getMethod("setDisplayId",int.class).invoke(event,displayId);inject(event);
        }
    }
    private void inject(InputEvent event)throws Exception{
        if(INPUT_MANAGER==null||INJECT==null)throw new IllegalStateException("Input injection unavailable");
        INJECT.invoke(INPUT_MANAGER,event,0);
    }
    private boolean primaryRateHeld;
    // HyperOS idles the primary panel to 60Hz on static content, which also caps app vsync;
    // hold a non-persistent 120Hz preferred mode while the session runs and restore unset on
    // close. An existing user preference is never touched; a crash can leave ours until reboot.
    private void applyPrimaryRate(boolean hold){
        try{
            String preferred=sh("cmd","display","get-user-preferred-display-mode","0");
            if(hold){
                if(!preferred.contains("-1")&&!preferred.contains("null")){android.util.Log.w("DuoFixed","User display preference kept: "+preferred.trim());return;}
                String size=sh("wm","size","-d","0");
                java.util.regex.Matcher over=java.util.regex.Pattern.compile("Override size: (\\d+)x(\\d+)").matcher(size);
                java.util.regex.Matcher m=over.find()?over:java.util.regex.Pattern.compile("Physical size: (\\d+)x(\\d+)").matcher(size);
                if(!m.find())throw new IllegalStateException("Primary size missing: "+size.trim());
                sh("cmd","display","set-user-preferred-display-mode",m.group(1),m.group(2),"120","0","false");
                primaryRateHeld=true;
            }else{
                if(primaryRateHeld){sh("cmd","display","clear-user-preferred-display-mode","0");primaryRateHeld=false;}
            }
        }catch(Exception e){android.util.Log.w("DuoFixed","Primary refresh rate",e);}
    }
    private static String sh(String... command)throws Exception{
        java.lang.Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        try(java.io.InputStream in=process.getInputStream()){String result=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if(process.waitFor()!=0||result.contains("Error"))throw new IllegalStateException(result.trim());return result;}
    }
    @Override public synchronized void close(){for(VirtualDisplay display:displays.values())display.release();displays.clear();densities.clear();applyPrimaryRate(false);}
}
