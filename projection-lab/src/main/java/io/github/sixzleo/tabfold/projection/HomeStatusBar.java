package io.github.sixzleo.tabfold.projection;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.function.Consumer;

/** Home-drawn status bar in an overlay window that covers the dormant MIUI bar. */
final class HomeStatusBar extends LinearLayout {
    static final int SIDE_NOTIFICATIONS=0,SIDE_CONTROL=1;
    private final Consumer<Integer> openPanel;
    private final TextView notifChip;
    private final WifiView wifi;
    private final BatteryView batteryIcon;
    private float pullStart,pullSide;
    private boolean pullFired;
    HomeStatusBar(Context context,Consumer<Integer> openPanel){
        super(context);
        this.openPanel=openPanel;
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(14),0,dp(10),0);
        // Frosted strip (澎湃OS 柔光玻璃): blurs out the dormant MIUI bar underneath.
        setBackgroundColor(0x59000000);
        HomeGlass.apply(this,Math.round(24*getResources().getDisplayMetrics().density),0);
        TextClock clock=new TextClock(context);
        clock.setFormat12Hour(DateFormat.is24HourFormat(context)?"HH:mm":"h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(13f);clock.setTextColor(HomeStyle.TEXT);
        clock.setSingleLine(true);
        addView(clock,new LinearLayout.LayoutParams(-2,-1));
        addView(notifChip=makeChip(context),new LinearLayout.LayoutParams(-2,-1));
        TextView spring=new TextView(context);spring.setClickable(false);
        addView(spring,new LinearLayout.LayoutParams(0,-1,1));
        addView(wifi=new WifiView(context),new LinearLayout.LayoutParams(dp(18),-1));
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(dp(7),-1);addView(new View(context),gap);
        addView(batteryIcon=new BatteryView(context),new LinearLayout.LayoutParams(dp(44),-1));
        setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case android.view.MotionEvent.ACTION_DOWN:
                    pullStart=event.getY();pullSide=event.getX();pullFired=false;return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if(!pullFired&&event.getY()-pullStart>dp(16))fire();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if(!pullFired)fire();
                    return true;
                default:return true;
            }
        });
        setClickable(true);setFocusable(true);
    }
    private void fire(){
        pullFired=true;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        openPanel.accept(pullSide<getWidth()/2f?SIDE_NOTIFICATIONS:SIDE_CONTROL);
    }
    private TextView makeChip(Context context){
        TextView chip=new TextView(context);chip.setTextSize(10.5f);chip.setTextColor(HomeStyle.TEXT);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(7),0,dp(7),0);
        chip.setBackground(HomeStyle.surface(chip,0x33ffffff,11));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,dp(17));size.leftMargin=dp(9);
        chip.setLayoutParams(size);
        chip.setVisibility(View.GONE);return chip;
    }
    void setNotificationCount(int count){
        if(count<=0){notifChip.setVisibility(View.GONE);return;}
        notifChip.setVisibility(View.VISIBLE);
        notifChip.setText(String.valueOf(Math.min(count,99)));
        notifChip.setContentDescription(count+" 条通知");
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();wifi.attach();batteryIcon.attach();}
    @Override protected void onDetachedFromWindow(){wifi.detach();batteryIcon.detach();super.onDetachedFromWindow();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private final class WifiView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){post(()->{wifiOn=true;invalidate();});}
            @Override public void onLost(Network network){post(()->{wifiOn=false;invalidate();});}
        };
        private boolean wifiOn,registered;
        WifiView(Context context){super(context);
            setContentDescription("Wi-Fi");paint.setStrokeWidth(dp(1.7f));paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);}
        void attach(){
            if(registered)return;registered=true;
            ConnectivityManager connectivity=(ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if(connectivity!=null)try{connectivity.registerDefaultNetworkCallback(callback);}catch(RuntimeException ignored){registered=false;}
        }
        void detach(){
            if(!registered)return;registered=false;
            ConnectivityManager connectivity=(ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if(connectivity!=null)connectivity.unregisterNetworkCallback(callback);
        }
        @Override protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,bottom=getHeight()*0.72f;
            paint.setColor(HomeStyle.TEXT);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx,bottom,dp(1.1f),paint);
            paint.setStyle(Paint.Style.STROKE);
            for(int ring=1;ring<=3;ring++){
                float radius=dp(1.8f)*ring+dp(1.4f);
                canvas.drawArc(cx-radius,bottom-radius,cx+radius,bottom+radius,-133,76,false,paint);
            }
        }
    }

    private final class BatteryView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private int percent=0;
        private boolean charging;
        private final BroadcastReceiver receiver=new BroadcastReceiver(){
            public void onReceive(Context context,Intent intent){
                int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                int status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                charging=status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL;
                percent=level>=0&&scale>0?Math.round(level*100f/scale):0;
                setContentDescription("电池 "+percent+"%"+(charging?"，充电中":""));
                invalidate();
            }
        };
        BatteryView(Context context){super(context);paint.setTextAlign(Paint.Align.CENTER);}
        void attach(){getContext().registerReceiver(receiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));}
        void detach(){try{getContext().unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}}
        @Override protected void onDraw(Canvas canvas){
            float iconW=dp(11),h=getHeight()*0.46f,top=(getHeight()-h)/2f,r=dp(2f);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.2f));paint.setColor(HomeStyle.TEXT);
            canvas.drawRoundRect(0,top,iconW,top+h,r,r,paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(iconW+dp(1),top+h*0.32f,iconW+dp(2.6f),top+h*0.68f,paint);
            if(percent>0){
                float inner=iconW-dp(3),fill=Math.max(dp(1.2f),inner*percent/100f);
                paint.setColor(charging?0xff5ad19b:percent<=20?0xffe57373:HomeStyle.TEXT);
                canvas.drawRoundRect(dp(1.5f),top+dp(1.4f),dp(1.5f)+fill,top+h-dp(1.4f),dp(1.2f),dp(1.2f),paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(HomeStyle.TEXT);paint.setTextSize(dp(11));
            paint.setTextAlign(Paint.Align.LEFT);paint.setStyle(Paint.Style.FILL);
            canvas.drawText(String.valueOf(percent),iconW+dp(5),getHeight()/2f+dp(4),paint);
        }
    }
}
