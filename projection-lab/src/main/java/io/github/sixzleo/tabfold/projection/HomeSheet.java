package io.github.sixzleo.tabfold.projection;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.TextUtils;

/** A bounded panel in a normal dialog window, with keyboard and display-cutout insets. */
final class HomeSheet extends Dialog {
    final LinearLayout content;
    private final FrameLayout outside;
    private final View scrim;
    private final int preferredWidth,preferredHeight;
    private LinearLayout heading;
    private boolean keyboardVisible;
    HomeSheet(Activity activity,int widthDp,int heightDp){
        super(activity,R.style.HomeSheetTheme);
        preferredWidth=dp(widthDp);preferredHeight=dp(heightDp);
        outside=new FrameLayout(activity);outside.setTag("home-sheet-outside");outside.setOnClickListener(v->dismiss());
        outside.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content=new LinearLayout(activity);content.setTag("home-sheet-content");content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(12),dp(20),dp(16));content.setClickable(true);
        content.setBackground(HomeStyle.surface(content,0xee252b32,HomeStyle.PANEL_RADIUS));
        HomeGlass.apply(content,dp(32),dp(28));
        outside.addView(content,new FrameLayout.LayoutParams(preferredWidth,preferredHeight,Gravity.CENTER));
        scrim=new View(activity);scrim.setBackgroundColor(0x52000000);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        outside.addView(scrim,0,new FrameLayout.LayoutParams(-1,-1));
        setContentView(outside);
        Window window=getWindow();window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);window.setNavigationBarColor(Color.TRANSPARENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        outside.setOnApplyWindowInsetsListener((v,insets)->{
            keyboardVisible=insets.isVisible(WindowInsets.Type.ime());
            Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            outside.setPadding(safe.left+dp(16),safe.top+dp(12),safe.right+dp(16),safe.bottom+dp(12));
            resize();return insets;
        });
        outside.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->resize());
    }
    @Override public void show(){
        super.show();getWindow().setLayout(-1,-1);outside.requestApplyInsets();
        scrim.setAlpha(0f);content.setAlpha(0f);content.setScaleX(.94f);content.setScaleY(.94f);content.setTranslationY(dp(14));
        if(!android.animation.ValueAnimator.areAnimatorsEnabled())return;
        scrim.animate().alpha(1f).setDuration(240).start();
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(240)
            .setInterpolator(new android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start();
    }
    LinearLayout header(String title){
        LinearLayout header=new LinearLayout(getContext());header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=new TextView(getContext());name.setText(title);name.setTextSize(21);name.setTextColor(0xfff4f7fb);
        name.setGravity(Gravity.CENTER_VERTICAL);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(name,new LinearLayout.LayoutParams(0,dp(56),1));
        Button close=new Button(getContext());close.setText("×");close.setTextSize(20);close.setTextColor(0xfff4f7fb);
        close.setMinWidth(0);close.setMinimumWidth(0);close.setPadding(0,0,0,0);close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription("关闭面板");close.setOnClickListener(v->dismiss());header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
        heading=header;content.setAccessibilityPaneTitle(title);content.addView(header);return header;
    }
    private int dp(int value){return Math.round(value*getContext().getResources().getDisplayMetrics().density);}
    private void resize(){
        int availableWidth=outside.getWidth()-outside.getPaddingLeft()-outside.getPaddingRight();
        int availableHeight=outside.getHeight()-outside.getPaddingTop()-outside.getPaddingBottom();
        if(availableWidth<=0||availableHeight<=0)return;
        if(heading!=null){int visibility=keyboardVisible&&availableHeight<dp(320)?View.GONE:View.VISIBLE;
            if(heading.getVisibility()!=visibility)heading.setVisibility(visibility);}
        FrameLayout.LayoutParams size=(FrameLayout.LayoutParams)content.getLayoutParams();
        int width=Math.min(preferredWidth,availableWidth),height=Math.min(preferredHeight,availableHeight);
        if(size.width!=width||size.height!=height){size.width=width;size.height=height;content.setLayoutParams(size);}
    }
}
