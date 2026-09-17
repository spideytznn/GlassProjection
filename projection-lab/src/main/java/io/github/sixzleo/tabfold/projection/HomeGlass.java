package io.github.sixzleo.tabfold.projection;

import android.graphics.drawable.Drawable;
import android.graphics.Outline;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
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
    /** Optional MiDuo-style dual alpha: light fill while blur is active, solid fallback otherwise. */
    private final Drawable fill;
    private final int blurAlpha,fallbackAlpha;
    private boolean listening,created,failed,blurEnabled;
    private float appliedAlpha=Float.NaN;
    private ViewTreeObserver observer;

    static boolean apply(View view,int radius,int cornerRadius){
        return apply(view,radius,cornerRadius,null,255,255);
    }
    /** @return true when this material will drive the fill alpha; false when the blur API
     *  is missing, so the caller's fallback fill must stay as the permanent background. */
    static boolean apply(View view,int radius,int cornerRadius,Drawable fill,int blurAlpha,int fallbackAlpha){
        if(BLUR==null||ALPHA==null)return false;
        HomeGlass material=new HomeGlass(view,radius,cornerRadius,fill,blurAlpha,fallbackAlpha);
        view.addOnAttachStateChangeListener(material);
        if(view.isAttachedToWindow())material.onViewAttachedToWindow(view);
        return true;
    }
    private HomeGlass(View view,int radius,int corner,Drawable fill,int blurAlpha,int fallbackAlpha){
        this.view=view;this.tint=view.getBackground();this.radius=radius;
        // Some HyperOS builds read an 8-value per-corner radii array; 4 identical values
        // cover ROMs that expect the short form.
        // Framework fact (decompiled HyperOS View.updateBackgroundBlur): a 4-value radii
        // array takes the standard setCornerRadius path whose values land in the published
        // BlurRegion; an 8-value array switches to the MI-only radii mode whose fields
        // SurfaceFlinger ignores for third-party windows, leaving square blur regions.
        corners=new float[]{corner,corner,corner,corner};
        windows=view.getContext().getSystemService(WindowManager.class);
        this.fill=fill;this.blurAlpha=blurAlpha;this.fallbackAlpha=fallbackAlpha;
        // The OEM blur layer ignores our corner radii on some builds and blurs a square:
        // clip the whole render node (fill, stroke and blur) to the rounded outline.
        view.setOutlineProvider(new ViewOutlineProvider(){
            @Override public void getOutline(View target,Outline outline){
                float r=Math.min(corner,Math.min(target.getWidth(),target.getHeight())/2f);
                outline.setRoundRect(0,0,target.getWidth(),target.getHeight(),r);
            }
        });
        view.setClipToOutline(true);
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
            if(created){syncAlpha();syncFill();}
        }catch(ReflectiveOperationException|RuntimeException unavailable){
            failed=true;view.setBackground(tint);
            if(fill!=null)fill.setAlpha(fallbackAlpha);
            Log.w("GlassHomeMaterial","Local blur unavailable; using translucent background",unavailable);
        }
    }
    private void syncFill(){
        if(fill==null)return;
        int target=created&&blurEnabled?blurAlpha:fallbackAlpha;
        if(fill.getAlpha()!=target)fill.setAlpha(target);
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
            failed=true;view.setBackground(tint);
            if(fill!=null)fill.setAlpha(fallbackAlpha);
            Log.w("GlassHomeMaterial","Blur opacity unavailable; using translucent background",unavailable);
        }
        return true;
    }
    @Override public void onViewDetachedFromWindow(View v){
        if(observer!=null&&observer.isAlive())observer.removeOnPreDrawListener(this);observer=null;
        if(listening){windows.removeCrossWindowBlurEnabledListener(availability);listening=false;}
        if(created){
            try{ALPHA.invoke(view,0f);}catch(ReflectiveOperationException|RuntimeException ignored){}
            view.setBackground(tint);created=false;appliedAlpha=Float.NaN;
            if(fill!=null)fill.setAlpha(fallbackAlpha);
        }
    }
}
