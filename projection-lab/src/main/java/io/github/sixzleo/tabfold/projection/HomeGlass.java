package io.github.sixzleo.tabfold.projection;

import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewTreeObserver;
import android.view.ViewParent;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Optional HyperOS per-view compositor blur. The supplied background remains the fallback. */
final class HomeGlass implements View.OnAttachStateChangeListener,ViewTreeObserver.OnPreDrawListener {
    private static final Method BLUR=method("setBackgroundBlur",int.class,float[].class);
    private static final Method ALPHA=method("setBackgroundBlurAlpha",float.class);
    private final View view;
    private final Drawable tint;
    private final int radius;
    private final float[] corners;
    private final WindowManager windows;
    private final Consumer<Boolean> availability=this::update;
    private boolean listening,created,failed,blurEnabled;
    private float appliedAlpha=Float.NaN;
    private ViewTreeObserver observer;

    static void apply(View view,int radius,int cornerRadius){
        if(BLUR==null||ALPHA==null)return;
        HomeGlass material=new HomeGlass(view,radius,cornerRadius);
        view.addOnAttachStateChangeListener(material);
        if(view.isAttachedToWindow())material.onViewAttachedToWindow(view);
    }
    private HomeGlass(View view,int radius,int corner){
        this.view=view;this.tint=view.getBackground();this.radius=radius;
        corners=new float[]{corner,corner,corner,corner};
        windows=view.getContext().getSystemService(WindowManager.class);
    }
    private static Method method(String name,Class<?>... parameters){
        try{return View.class.getMethod(name,parameters);}
        catch(ReflectiveOperationException|RuntimeException unavailable){return null;}
    }
    @Override public void onViewAttachedToWindow(View v){
        if(windows==null||failed||listening||!v.isHardwareAccelerated())return;
        try{
            listening=true;
            observer=v.getViewTreeObserver();observer.addOnPreDrawListener(this);
            windows.addCrossWindowBlurEnabledListener(v.getContext().getMainExecutor(),availability);
        }catch(RuntimeException unavailable){listening=false;failed=true;}
    }
    private void update(boolean enabled){
        if(!view.isAttachedToWindow()||failed)return;
        blurEnabled=enabled;
        try{
            if(enabled&&!created){
                // This OEM API adds a rounded BackgroundBlurDrawable beneath the existing
                // tint. Call once: repeated calls on some builds discard that tint layer.
                int left=view.getPaddingLeft(),top=view.getPaddingTop(),right=view.getPaddingRight(),bottom=view.getPaddingBottom();
                try{BLUR.invoke(view,radius,corners);}
                finally{view.setPadding(left,top,right,bottom);}
                created=view.getBackground()!=tint;
            }
            if(created)syncAlpha();
        }catch(ReflectiveOperationException|RuntimeException unavailable){
            failed=true;view.setBackground(tint);
            Log.w("GlassHomeMaterial","Local blur unavailable; using translucent background",unavailable);
        }
    }
    private void syncAlpha()throws ReflectiveOperationException{
        float alpha=blurEnabled&&view.isShown()&&view.getWindowVisibility()==View.VISIBLE?1f:0f;
        for(View current=view;alpha>0;){
            alpha*=Math.max(0,Math.min(1,current.getAlpha()));
            ViewParent parent=current.getParent();if(!(parent instanceof View))break;current=(View)parent;
        }
        if(!Float.isFinite(appliedAlpha)||Math.abs(alpha-appliedAlpha)>.0001f){ALPHA.invoke(view,alpha);appliedAlpha=alpha;}
    }
    @Override public boolean onPreDraw(){
        if(created&&!failed)try{syncAlpha();}catch(ReflectiveOperationException|RuntimeException unavailable){
            failed=true;view.setBackground(tint);Log.w("GlassHomeMaterial","Blur opacity unavailable; using translucent background",unavailable);
        }
        return true;
    }
    @Override public void onViewDetachedFromWindow(View v){
        if(observer!=null&&observer.isAlive())observer.removeOnPreDrawListener(this);observer=null;
        if(listening){windows.removeCrossWindowBlurEnabledListener(availability);listening=false;}
        if(created){
            try{ALPHA.invoke(view,0f);}catch(ReflectiveOperationException|RuntimeException ignored){}
            view.setBackground(tint);created=false;appliedAlpha=Float.NaN;
        }
    }
}
