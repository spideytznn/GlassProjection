package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.*;
import java.util.*;

/** Full-screen black input sinks. Physical display identity, not logical id, selects the panel. */
final class FixedDualCurtains implements AutoCloseable {
    private static final String INNER="local:4639175402683733248",COVER="local:4639175068132267009";
    private final Context service;
    private final Map<String,Curtain> windows=new HashMap<>();
    FixedDualCurtains(Context service){this.service=service;}
    private static String identity(Display display){
        try{return (String)org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(Display.class,display,"getUniqueId");}
        catch(Exception e){throw new IllegalStateException("Physical display identity unavailable",e);}
    }
    void prepare(){
        for(Display display:service.getSystemService(DisplayManager.class).getDisplays()){
            String identity=identity(display);
            if(!INNER.equals(identity)&&!COVER.equals(identity))continue;
            Curtain prior=windows.get(identity);
            if(prior!=null&&prior.displayId==display.getDisplayId())continue;
            if(prior!=null)prior.close();
            windows.put(identity,new Curtain(display));
        }
        if(windows.size()!=2)throw new IllegalStateException("Both known physical panels required");
    }
    void showOnly(FixedDualPolicy.Panel active){
        if(active==FixedDualPolicy.Panel.BOTH){
            for(Curtain curtain:windows.values())curtain.setBlocked(false);
            return;
        }
        // Close the inactive input gate before exposing the other panel.
        String visible=active==FixedDualPolicy.Panel.INNER?INNER:active==FixedDualPolicy.Panel.COVER?COVER:"";
        for(Map.Entry<String,Curtain> entry:windows.entrySet())if(!entry.getKey().equals(visible))entry.getValue().setBlocked(true);
        Curtain clear=windows.get(visible);if(clear!=null)clear.setBlocked(false);
    }
    @Override public void close(){for(Curtain curtain:windows.values())curtain.close();windows.clear();}
    private final class Curtain implements AutoCloseable {
        final WindowManager manager;final View view;final int displayId;
        Curtain(Display display){
            displayId=display.getDisplayId();
            Context context=service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
            manager=context.getSystemService(WindowManager.class);
            view=new View(context);view.setBackgroundColor(android.graphics.Color.BLACK);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            view.setOnTouchListener((v,event)->true);
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.OPAQUE);
            p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            p.setFitInsetsTypes(0);p.setTitle("Duo inactive panel "+identity(display));
            manager.addView(view,p);
        }
        void setBlocked(boolean blocked){view.setVisibility(blocked?View.VISIBLE:View.GONE);}
        @Override public void close(){try{manager.removeViewImmediate(view);}catch(IllegalArgumentException ignored){}}
    }
}
