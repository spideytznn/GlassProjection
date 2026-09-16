package io.github.sixzleo.tabfold.projection;

import android.content.Context;
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
    static void glass(View view,int radius){
        glass(view,radius,GLASS);
    }
    static void glass(View view,int radius,int color){
        view.setBackground(surface(view,color,radius));
        float density=view.getResources().getDisplayMetrics().density;
        HomeGlass.apply(view,Math.round(32*density),Math.round(radius*density));
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
