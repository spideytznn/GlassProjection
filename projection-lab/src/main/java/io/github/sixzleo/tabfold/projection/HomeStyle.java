package io.github.sixzleo.tabfold.projection;

import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Shared materials for launcher-owned surfaces; provider widgets keep their own design. */
final class HomeStyle {
    static final int TEXT=0xfff4f7fb, MUTED=0xffc3cedb, ACCENT=0xffbcefe3;
    static final int GLASS=0x50343b43, FIELD=0x66343b43, CONTROL_GLASS=0x99343b43;
    static final int PANEL_RADIUS=28, FIELD_RADIUS=18;
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
    static void rounded(View view,int radius){
        view.setOutlineProvider(new android.view.ViewOutlineProvider(){
            @Override public void getOutline(View target,android.graphics.Outline outline){
                float corner=radius*target.getResources().getDisplayMetrics().density;
                outline.setRoundRect(0,0,target.getWidth(),target.getHeight(),Math.min(corner,Math.min(target.getWidth(),target.getHeight())/2f));
            }
        });view.setClipToOutline(true);
    }
}
