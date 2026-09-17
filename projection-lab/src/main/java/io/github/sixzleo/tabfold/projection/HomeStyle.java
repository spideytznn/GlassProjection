package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Shared materials for launcher-owned surfaces; provider widgets keep their own design. */
final class HomeStyle {
    static final int TEXT=0xfff4f7fb, MUTED=0xffc3cedb, ACCENT=0xffbcefe3;
    static final int GLASS=0x50343b43, FIELD=0x66343b43, CONTROL_GLASS=0x99343b43;
    static final int PANEL_RADIUS=28, FIELD_RADIUS=18;
    /**
     * DuoGlass role materials measured from MiDuo (参考/MiDuo-实现分析.md §三.3):
     * {blur dp, fill alpha with compositor blur, fallback alpha without}, over dark base 0x121b1f.
     */
    static final int GLASS_BASE=0x121b1f;
    static final float[] ROLE_CONTROL={15,.04f,.30f},ROLE_DOCK={22,.055f,.36f},ROLE_CARD={22,.065f,.40f},
        ROLE_FOLDER={16,.025f,.30f},ROLE_SCREEN={28,.11f,.78f},ROLE_FLOATING={20,.08f,.56f},ROLE_FRAME={18,.035f,.25f};
    static GradientDrawable glassSurface(View view,float[] role,int radiusDp){
        GradientDrawable drawable=surface(view,0xff000000|GLASS_BASE,radiusDp);
        drawable.setAlpha(Math.round(role[2]*255)); // fallback fill for static surfaces; glass() overrides to the blur alpha
        return drawable;
    }
    static void glass(View view,int radiusDp,float[] role){glass(view,radiusDp,role,true);}
    /** border=false draws a full-bleed fill with no stroke, for fullscreen blur surfaces. */
    static void glass(View view,int radiusDp,float[] role,boolean border){
        GradientDrawable fill=border?glassSurface(view,role,radiusDp):flatSurface(role);
        view.setBackground(fill);
        float density=view.getResources().getDisplayMetrics().density;
        // Start at the blur fill, not the fallback: the cross-window blur listener engages
        // a few frames late and swaps the fill instantly, so a fallback start reads as a
        // dark background that abruptly brightens (the HomeControlPanel "shade flash").
        if(HomeGlass.apply(view,Math.round(role[0]*density),Math.round(radiusDp*density),fill,
            Math.round(role[1]*255),Math.round(role[2]*255)))fill.setAlpha(Math.round(role[1]*255));
    }
    /** Rounded card without per-view compositor blur — for windows that blur behind themselves. */
    static void glassStatic(View view,int radiusDp,float[] role,boolean border){
        GradientDrawable fill=border?glassSurface(view,role,radiusDp):flatSurface(role);
        view.setBackground(fill);
        rounded(view,radiusDp);
    }
    private static GradientDrawable flatSurface(float[] role){
        GradientDrawable drawable=new GradientDrawable();
        drawable.setColor(0xff000000|GLASS_BASE);
        drawable.setAlpha(Math.round(role[2]*255));
        return drawable;
    }
    static TextView text(Context context,String value,int size,int color){
        TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);return view;
    }
    static Button button(Context context,String label,Runnable action){
        Button view=new Button(context);view.setText(label);view.setTextSize(14);view.setTextColor(TEXT);view.setAllCaps(false);
        view.setMinWidth(0);view.setMinimumWidth(0);view.setPadding(dp(context,4),0,dp(context,4),0);
        view.setBackground(ripple(view,FIELD_RADIUS));view.setOnClickListener(w->action.run());return view;
    }
    static GradientDrawable glassDrawable(Context context,int color,int radius){
        GradientDrawable drawable=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{color,color&0x00ffffff|Math.max(0,(color>>>24)-20)<<24});
        drawable.setCornerRadius(dp(context,radius));
        drawable.setStroke(Math.max(1,Math.round(context.getResources().getDisplayMetrics().density)),0x32ffffff);
        return drawable;
    }
    static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    static GradientDrawable surface(View view,int color,int radius){
        float density=view.getResources().getDisplayMetrics().density;
        GradientDrawable background=new GradientDrawable();background.setColor(color);
        background.setCornerRadius(radius*density);background.setStroke(Math.max(1,Math.round(density)),0x32ffffff);
        return background;
    }
    static android.graphics.drawable.RippleDrawable ripple(View view,int radius){
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),null,surface(view,0xffffffff,radius));
    }
    /** Editing chrome: transparent fill with a colored border, drawn over children via setForeground. */
    static GradientDrawable outline(View view,int color,int radius){
        float density=view.getResources().getDisplayMetrics().density;
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(0);
        drawable.setCornerRadius(radius*density);
        drawable.setStroke(Math.max(2,Math.round(1.5f*density)),color);
        return drawable;
    }
    static void rounded(View view,int radius){
        view.setOutlineProvider(new android.view.ViewOutlineProvider(){
            @Override public void getOutline(View target,android.graphics.Outline outline){
                float corner=radius*target.getResources().getDisplayMetrics().density;
                outline.setRoundRect(0,0,target.getWidth(),target.getHeight(),Math.min(corner,Math.min(target.getWidth(),target.getHeight())/2f));
            }
        });view.setClipToOutline(true);
    }
}
