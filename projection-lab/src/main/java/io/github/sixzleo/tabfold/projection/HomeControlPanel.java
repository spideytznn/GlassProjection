package io.github.sixzleo.tabfold.projection;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.NotificationManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * iOS-style shade in an accessibility overlay window. Notifications and control
 * are two real side-by-side pages inside a scrolled track, so pane swipes track
 * the finger 1:1 and settle in one continuous motion.
 */
final class HomeControlPanel {
    // Glass materials: dock-tinted translucency with a bottom fade instead of a solid edge.
    private static final int TEXT=0xfff2f2f7, MUTED=0x99ebebf5, BLUE=0xff0a84ff;
    static final int PANEL_TINT=0xff121b1f, MODULE=0x42787880, CARD=0x463a3a44;
    private static final int TORCH_ON=0xfff2f2f7, TORCH_GLYPH=0xff16161c, INDIGO=0xff5e5ce6;
    private static final Typeface MEDIUM=Typeface.create("sans-serif-medium",Typeface.NORMAL);

    private static final Map<Integer,HomeControlPanel> currentByDisplay=new HashMap<>();

    private final ProjectionService service;
    private final android.view.Display display;
    private final WindowManager manager;
    private final FrameLayout root;
    private final LinearLayout panel;
    private final PaneHost paneHost;
    private final LinearLayout controlPane,notifPane;
    private final View scrim;
    private boolean control;
    private float fade; // 0 = notification page, 1 = control page
    private boolean animating;
    private LinearLayout torchTile,rotateTile,dndTile,darkTile,wifiTile,btTile,dataTile,airTile;
    private boolean wifiOn,btOn,dataOn=true,airOn;
    private PillSlider brightnessSlider,volumeSlider;
    private ScrollView notifList;
    private LinearLayout notifBox;
    private TextView notifHead,batteryLabel;
    private final SimpleDateFormat timeFormat=new SimpleDateFormat("HH:mm",Locale.CHINA);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable change=this::refreshNotifications;
    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){
        public void onReceive(Context context,Intent intent){
            int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,100);
            int status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
            batteryCharging=status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL;
            batteryPercent=level>=0&&scale>0?Math.round(level*100f/scale):0;
            paintBattery();
        }
    };
    private int batteryPercent=-1;
    private boolean batteryCharging;
    private CameraManager torchManager;
    private String torchId;
    private boolean torchOn,closed,attached,fingerDriven;
    private float dropDistance;
    private float handleTouchX,handleTouchY;
    private android.animation.ValueAnimator dragAnim;

    private final CameraManager.TorchCallback torchCallback=new CameraManager.TorchCallback(){
        @Override public void onTorchModeChanged(String id,boolean enabled){torchOn=enabled;main.post(()->{if(!closed&&control)paintTiles();});}
    };

    /** Opens (or switches) the shade on a display; one shade per display at a time. */
    static void open(int displayId,boolean control){
        ProjectionService s=ProjectionService.instance;if(s==null)return;
        HomeControlPanel showing=currentByDisplay.get(displayId);
        if(showing!=null&&showing.attached){showing.switchTo(control);return;}
        android.view.Display target=s.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(displayId);
        if(target==null)return;
        HomeControlPanel created=new HomeControlPanel(s,target,control,false,0f);
        if(created.attached)currentByDisplay.put(displayId,created);
    }
    /** Shade pinned to the finger: built at the touch position, then driven by dragOn/releaseOn. */
    static void beginDrag(int displayId,boolean control,float fingerY){
        ProjectionService s=ProjectionService.instance;if(s==null)return;
        HomeControlPanel showing=currentByDisplay.get(displayId);
        if(showing!=null&&showing.attached){showing.switchTo(control);return;}
        android.view.Display target=s.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(displayId);
        if(target==null)return;
        HomeControlPanel created=new HomeControlPanel(s,target,control,true,fingerY);
        if(created.attached)currentByDisplay.put(displayId,created);
    }
    /** Finger tracking while a pull gesture owns the shade; fingerY is the touch's screen y. */
    static void dragOn(int displayId,float fingerY){
        HomeControlPanel panel=currentByDisplay.get(displayId);
        if(panel!=null)panel.dragTo(fingerY);
    }
    /** Release the finger tracking; velocity in px/ms (down positive) decides settle vs spring shut. */
    static void releaseOn(int displayId,float velocityPxMs){
        HomeControlPanel panel=currentByDisplay.get(displayId);
        if(panel!=null)panel.releaseDrag(velocityPxMs);
    }

    private HomeControlPanel(ProjectionService service,android.view.Display display,boolean control,boolean interactive,float fingerY){
        this.service=service;this.display=display;this.control=control;
        Context wc=service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
        manager=wc.getSystemService(WindowManager.class);
        torchManager=(CameraManager)service.getSystemService(Context.CAMERA_SERVICE);
        try{
            for(String id:torchManager.getCameraIdList()){
                Boolean flash=torchManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if(flash!=null&&flash){torchId=id;break;}
            }
        }catch(CameraAccessException ignored){}

        scrim=new View(wc);scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v->close());
        panel=new LinearLayout(wc);panel.setOrientation(LinearLayout.VERTICAL);
        // Full-bleed glass like the dock, melting into the wallpaper at the bottom edge.
        // MiDuo Screen role: heavy blur with a light fill, solid dark fallback when blur is off.
        // Start at the blur fill, not the fallback: the async blur listener would otherwise
        // show a near-black panel for its first few frames of the drop-in (the "shade flash").
        // Blur engages here a few frames later as a soft sharpen-to-frost; if this ROM ever
        // reports blur off, the listener deepens the fill to the fallback.
        GlassFade fadeDrawable=new GlassFade(PANEL_TINT,dp(110));
        fadeDrawable.setAlpha(Math.round(HomeStyle.ROLE_SCREEN[1]*255));
        panel.setBackground(fadeDrawable);
        panel.setPadding(dp(18),dp(8),dp(18),dp(14));
        panel.setClickable(true);
        HomeGlass.apply(panel,dp(28),0,fadeDrawable,Math.round(HomeStyle.ROLE_SCREEN[1]*255),Math.round(HomeStyle.ROLE_SCREEN[2]*255));
        controlPane=new LinearLayout(wc);controlPane.setOrientation(LinearLayout.VERTICAL);
        controlPane.setOnClickListener(v->close());
        notifPane=new LinearLayout(wc);notifPane.setOrientation(LinearLayout.VERTICAL);
        notifPane.setOnClickListener(v->close());
        paneHost=new PaneHost(wc);
        paneHost.addView(notifPane,new FrameLayout.LayoutParams(-1,-1));
        paneHost.addView(controlPane,new FrameLayout.LayoutParams(-1,-1));
        panel.addView(paneHost,new LinearLayout.LayoutParams(-1,0,1f));
        // Close handle over the bottom gradient whitespace. On the inner screen the
        // content is rotated 90°, so a physical upward swipe arrives as a sideways
        // drag that PaneHost would read as page switching; this handle closes on any
        // direction so the natural "flick the sheet away" gesture always wins.
        View handle=new View(wc);
        handle.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:handleTouchX=event.getX();handleTouchY=event.getY();return true;
                case MotionEvent.ACTION_MOVE:
                    if(Math.abs(event.getX()-handleTouchX)>dp(10)||Math.abs(event.getY()-handleTouchY)>dp(10)){close();return true;}
                    handleTouchX=event.getX();handleTouchY=event.getY();return true;
                case MotionEvent.ACTION_UP:close();return true;
                default:return true;
            }
        });
        panel.addView(handle,new LinearLayout.LayoutParams(-1,dp(40)));
        root=new FrameLayout(wc);
        root.setOnClickListener(v->close());
        root.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        root.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            panel.setPadding(dp(18),safe.top+dp(10),dp(18),Math.max(safe.bottom,dp(14)));
            return insets;
        });

        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("Duo home shade");
        try{manager.addView(root,p);attached=true;}
        catch(RuntimeException failed){Log.w("GlassHome","Shade overlay unavailable",failed);destroy();return;}
        buildControlPane(controlPane);
        buildNotifPane(notifPane);
        DuoNotifications.observe(change);
        settle(control);
        try{service.registerReceiver(batteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));}catch(RuntimeException ignored){}

        dropDistance=service.getResources().getDisplayMetrics().heightPixels*0.6f;
        panel.setTranslationY(-dropDistance);scrim.setAlpha(0f);
        if(interactive){
            // Bottom-edge tracking, like the native shade: the sheet stays glued to the
            // screen top and its height rides the finger 1:1. Resizing (not clipping —
            // the compositor blur is a window-level drawable that ignores clipBounds)
            // keeps the frost exactly above the fingertip, and the GlassFade gradient
            // lands right on the reveal edge.
            fingerDriven=true;
            panel.setTranslationY(0f);
            dragTo(fingerY);
            return;
        }
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){
            panel.setTranslationY(0f);scrim.setAlpha(1f);return;
        }
        scrim.animate().alpha(1f).setDuration(240).start();
        panel.animate().translationY(0f).setDuration(340)
            .setInterpolator(new PathInterpolator(.32f,.72f,0f,1f)).start();
    }
    void dragTo(float fingerY){
        if(closed||!attached||!fingerDriven)return;
        if(dragAnim!=null)dragAnim.cancel();
        panel.animate().cancel();scrim.animate().cancel();
        int full=root.getHeight()>0?root.getHeight():service.getResources().getDisplayMetrics().heightPixels;
        int height=Math.round(Math.max(0,Math.min(fingerY,full)));
        panel.setLayoutParams(new FrameLayout.LayoutParams(-1,height));
        scrim.setAlpha(full>0?height/(float)full:0f);
    }
    private void setGestureExclusion(boolean on){
        // Fully open: claim the bottom gesture zone so upward swipes close the shade
        // instead of being swallowed by the system pill.
        panel.setSystemGestureExclusionRects(on
            ?java.util.Collections.singletonList(new android.graphics.Rect(0,0,Math.max(1,panel.getWidth()),Math.max(1,panel.getHeight())))
            :java.util.Collections.<android.graphics.Rect>emptyList());
    }
    void releaseDrag(float velocityPxMs){
        if(closed||!attached||!fingerDriven)return;
        fingerDriven=false;
        int full=root.getHeight()>0?root.getHeight():service.getResources().getDisplayMetrics().heightPixels;
        int height=Math.max(0,panel.getHeight());
        boolean open=height>dropDistance*0.5f||velocityPxMs>0.35f;
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){
            if(open){panel.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));scrim.setAlpha(1f);setGestureExclusion(true);}
            else destroy();
            return;
        }
        dragAnim=android.animation.ValueAnimator.ofInt(height,open?full:0);
        dragAnim.setDuration(open?200l:180l);
        dragAnim.setInterpolator(open?new PathInterpolator(.32f,.72f,0f,1f):new PathInterpolator(.55f,.05f,.8f,.6f));
        dragAnim.addUpdateListener(a->{
            if(closed||!attached)return;
            int value=(int)a.getAnimatedValue();
            panel.setLayoutParams(new FrameLayout.LayoutParams(-1,value));
            scrim.setAlpha(full>0?value/(float)full:0f);
        });
        if(open){
            dragAnim.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation){if(!closed&&attached)setGestureExclusion(true);}
            });
            dragAnim.start();
        }else{
            dragAnim.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation){destroy();}
            });
            dragAnim.start();
        }
    }
    void close(){
        if(closed||animating||!attached)return;
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){destroy();return;}
        animating=true;
        if(dragAnim!=null)dragAnim.cancel();
        setGestureExclusion(false);
        panel.getLayoutParams().height=-1; // a mid-drag close slides the full sheet
        // Mirror of the drop-in: slide the whole panel back up the same distance,
        // fading the scrim in parallel — no alpha tricks on the panel itself.
        scrim.animate().alpha(0f).setDuration(240).start();
        panel.animate()
            .translationY(-dropDistance)
            .setDuration(320)
            .setInterpolator(new PathInterpolator(.55f,.05f,.8f,.6f))
            .withEndAction(this::destroy).start();
    }
    /** Close a shade left open on a display, e.g. when the user returns home. */
    static void closeIfOpen(int displayId){
        HomeControlPanel panel=currentByDisplay.get(displayId);
        if(panel!=null&&panel.attached)panel.close();
    }
    /** True when the shade on that display is sheet-sized to the screen bottom; the dual
     *  output then extends the sheet over the reserved gesture band (the pill stands down). */
    static boolean bottomCovered(int displayId){
        HomeControlPanel panel=currentByDisplay.get(displayId);
        return panel!=null&&panel.bottomCovered();
    }
    private boolean bottomCovered(){
        int full=root.getHeight();
        return !closed&&attached&&full>0&&panel.getHeight()>=full-dp(28);
    }
    private void destroy(){
        if(closed)return;closed=true;attached=false;
        setTorchListening(false);DuoNotifications.forget(change);
        paneHost.recycle();
        try{service.unregisterReceiver(batteryReceiver);}catch(IllegalArgumentException ignored){}
        try{manager.removeViewImmediate(root);}catch(IllegalArgumentException ignored){}
        if(currentByDisplay.get(display.getDisplayId())==this)currentByDisplay.remove(display.getDisplayId());
    }
    private void applyFade(){
        notifPane.setAlpha(1f-fade);
        controlPane.setAlpha(fade);
    }
    private void settle(boolean wantControl){
        fade=wantControl?1f:0f;applyFade();
        notifPane.setVisibility(wantControl?View.INVISIBLE:View.VISIBLE);
        controlPane.setVisibility(wantControl?View.VISIBLE:View.INVISIBLE);
        if(control!=wantControl){control=wantControl;setTorchListening(control);}
        if(control)paintTiles();
    }
    private void switchTo(boolean wantControl){
        if(paneHost.dragging())return;
        animateFadeTo(wantControl);
    }
    private void animateFadeTo(boolean wantControl){
        if(animating)return;
        if(control==wantControl&&fade==(wantControl?1f:0f))return;
        animating=true;
        notifPane.setVisibility(View.VISIBLE);controlPane.setVisibility(View.VISIBLE);
        ValueAnimator run=ValueAnimator.ofFloat(fade,wantControl?1f:0f);
        run.setDuration(230);
        run.setInterpolator(new PathInterpolator(.3f,.6f,.1f,1f));
        run.addUpdateListener(a->{fade=(float)a.getAnimatedValue();applyFade();});
        run.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator a){animating=false;settle(wantControl);}
        });
        run.start();
    }
    private void buildControlPane(LinearLayout target){
        readConnectivityStates();
        target.addView(compactHeader(),new LinearLayout.LayoutParams(-1,dp(42)));
        target.addView(brightnessRow(),rowMargins(dp(52)));
        target.addView(volumeRow(),rowMargins(dp(52)));
        GridLayout grid=new GridLayout(service);grid.setColumnCount(4);
        wifiTile=circle("Wi-Fi",TileIcon.WIFI);
        btTile=circle("蓝牙",TileIcon.BLUETOOTH);
        dataTile=circle("数据",TileIcon.CELL);
        airTile=circle("飞行模式",TileIcon.AIRPLANE);
        torchTile=circle("手电筒",TileIcon.TORCH);
        rotateTile=circle("自动旋转",TileIcon.ROTATE);
        dndTile=circle("勿扰",TileIcon.DND);
        darkTile=circle("深色模式",TileIcon.DARK);
        for(View tile:new View[]{wifiTile,btTile,dataTile,airTile,torchTile,rotateTile,dndTile,darkTile})
            grid.addView(tile,circleSpec());
        target.addView(grid,rowMargins(dp(168)));
        paintTiles();
    }
    private void buildNotifPane(LinearLayout target){
        // One compact top bar: small clock top-left, date beside it, clear-all right —
        // the old hero clock squeezed the notification list.
        LinearLayout head=new LinearLayout(service);head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2),dp(4),dp(2),dp(2));
        TextClock clock=new TextClock(service);
        clock.setFormat12Hour(DateFormat.is24HourFormat(service)?"HH:mm":"h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(22);clock.setTextColor(TEXT);clock.setTypeface(MEDIUM);
        head.addView(clock,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams dateGap=new LinearLayout.LayoutParams(dp(10),dp(36));
        head.addView(new View(service),dateGap);
        String date=new SimpleDateFormat("M月d日 EEEE",Locale.CHINA).format(new Date());
        TextView dateLabel=HomeStyle.text(service,date,12,MUTED);
        head.addView(dateLabel,new LinearLayout.LayoutParams(-2,-2));
        head.addView(new View(service),new LinearLayout.LayoutParams(0,-2,1));
        TextView clear=HomeStyle.text(service,"全部清除",13,BLUE);
        clear.setGravity(Gravity.CENTER);
        clear.setBackground(HomeStyle.ripple(clear,16));
        clear.setPadding(dp(14),0,dp(14),0);
        clear.setOnClickListener(v->DuoNotifications.cancelAll());
        head.addView(clear,new LinearLayout.LayoutParams(-2,dp(32)));
        target.addView(head,new LinearLayout.LayoutParams(-1,dp(40)));

        notifBox=new LinearLayout(service);notifBox.setOrientation(LinearLayout.VERTICAL);
        notifList=new ScrollView(service){
            // The stock ScrollView silences parent interception once it claims a drag,
            // which eats horizontal flicks and the up-dismiss; keep the host in charge.
            @Override public void requestDisallowInterceptTouchEvent(boolean disallow){}
        };
        notifList.setVerticalScrollBarEnabled(false);
        notifList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Cards melt into the panel's fading bottom edge.
        notifList.setVerticalFadingEdgeEnabled(true);
        notifList.setFadingEdgeLength(dp(88));
        notifList.addView(notifBox,new ViewGroup.LayoutParams(-1,-2));
        LinearLayout.LayoutParams listSize=new LinearLayout.LayoutParams(-1,0,1f);
        // Keep a modest glass grab-zone below the list (~4.5 cards visible).
        listSize.bottomMargin=dp(36);
        target.addView(notifList,listSize);
        refreshNotifications();
    }
    private View compactHeader(){
        LinearLayout row=new LinearLayout(service);row.setGravity(Gravity.CENTER_VERTICAL);
        TextClock clock=new TextClock(service);
        clock.setFormat12Hour(DateFormat.is24HourFormat(service)?"HH:mm":"h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(17);clock.setTextColor(TEXT);clock.setTypeface(MEDIUM);
        row.addView(clock,new LinearLayout.LayoutParams(-2,-2));
        row.addView(new View(service),new LinearLayout.LayoutParams(0,-2,1));
        batteryLabel=HomeStyle.text(service,"",13,TEXT);batteryLabel.setTypeface(MEDIUM);
        row.addView(batteryLabel,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams gearGap=new LinearLayout.LayoutParams(dp(10),dp(38));
        row.addView(new View(service),gearGap);
        FrameLayout gear=new FrameLayout(service);
        TileIcon glyph=new TileIcon(service,TileIcon.GEAR);
        glyph.setColor(0xddf2f2f7);
        gear.addView(glyph,new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));
        gear.setBackground(HomeStyle.ripple(gear,19));
        gear.setContentDescription("设置");
        gear.setOnClickListener(v->start(new Intent(Settings.ACTION_SETTINGS)));
        row.addView(gear,new LinearLayout.LayoutParams(dp(38),dp(38)));
        paintBattery();
        return row;
    }
    private LinearLayout.LayoutParams rowMargins(int height){
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,height);
        size.topMargin=dp(14);return size;
    }
    private TextView pill(String label,View.OnClickListener action){
        TextView pill=pillText(label);pill.setOnClickListener(action);return pill;
    }
    private TextView pillText(String label){
        TextView pill=HomeStyle.text(service,label,15,TEXT);
        pill.setGravity(Gravity.CENTER);pill.setTypeface(MEDIUM);
        pill.setBackground(HomeStyle.ripple(pill,23));
        return pill;
    }
    private void start(Intent intent){
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{service.startActivity(intent);}catch(RuntimeException e){toast("无法打开");}
    }
    private void toast(String value){Toast.makeText(service,value,Toast.LENGTH_SHORT).show();}

    // ---- tiles ----
    private LinearLayout circle(String label,int glyphType){
        LinearLayout box=new LinearLayout(service);box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout circle=new FrameLayout(service);
        TileIcon glyph=new TileIcon(service,glyphType);
        circle.addView(glyph,new FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER));
        box.addView(circle,new LinearLayout.LayoutParams(dp(52),dp(52)));
        TextView text=HomeStyle.text(service,label,11,MUTED);
        text.setGravity(Gravity.CENTER);text.setSingleLine(true);
        box.addView(text,new LinearLayout.LayoutParams(-2,dp(15)));
        box.setTag(new Object[]{circle,glyph,text});
        box.setOnClickListener(v->onTile(label));
        return box;
    }
    private GridLayout.LayoutParams circleSpec(){
        GridLayout.LayoutParams spec=new GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED,1,1f),GridLayout.spec(GridLayout.UNDEFINED,1,1f));
        spec.width=0;spec.height=dp(80);
        spec.setMargins(dp(3),dp(3),dp(3),dp(3));
        return spec;
    }
    private void readConnectivityStates(){
        try{wifiOn=((android.net.wifi.WifiManager)service.getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();}catch(Exception ignored){}
        try{
            android.bluetooth.BluetoothAdapter adapter=((android.bluetooth.BluetoothManager)
                service.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
            btOn=adapter!=null&&adapter.isEnabled();
        }catch(SecurityException ignored){}
        try{airOn=Settings.Global.getInt(service.getContentResolver(),Settings.Global.AIRPLANE_MODE_ON,0)==1;}catch(Exception ignored){}
        try{dataOn=Settings.Global.getInt(service.getContentResolver(),"mobile_data",1)==1;}catch(Exception ignored){}
    }
    /** Shell toggle through the Shizuku helper; falls back to the matching system panel. */
    private void svcToggle(String enable,String disable,boolean current,Runnable afterRead,String fallbackAction){
        if(!MobileHelper.ready()){
            if(fallbackAction!=null)start(new Intent(fallbackAction));
            toast("需要 Shizuku，已打开系统面板");
            return;
        }
        MobileHelper.svc(current?disable:enable,out->{
            if(out.startsWith("ERROR"))toast("切换失败："+out);
            afterRead.run();
            paintTiles();
        });
    }
    private void onTile(String label){
        switch(label){
            case "Wi-Fi":{
                svcToggle("svc wifi enable","svc wifi disable",wifiOn,()->{
                    try{wifiOn=((android.net.wifi.WifiManager)service.getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();}
                    catch(Exception ignored){wifiOn=!wifiOn;}
                },Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                return;
            }
            case "蓝牙":{
                svcToggle("svc bluetooth enable","svc bluetooth disable",btOn,()->btOn=!btOn,
                    Settings.ACTION_BLUETOOTH_SETTINGS);
                return;
            }
            case "数据":{
                svcToggle("svc data enable","svc data disable",dataOn,()->dataOn=!dataOn,
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                return;
            }
            case "飞行模式":{
                svcToggle("cmd connectivity airplane-mode enable","cmd connectivity airplane-mode disable",airOn,()->{
                    try{airOn=Settings.Global.getInt(service.getContentResolver(),Settings.Global.AIRPLANE_MODE_ON,0)==1;}
                    catch(Exception ignored){airOn=!airOn;}
                },Settings.ACTION_AIRPLANE_MODE_SETTINGS);
                return;
            }
            case "手电筒":{
                if(torchId==null){toast("没有可用的闪光灯");return;}
                try{torchManager.setTorchMode(torchId,!torchOn);}catch(CameraAccessException ignored){}
                return;
            }
            case "自动旋转":{
                if(!Settings.System.canWrite(service)){openWriteSettings();return;}
                int current=Settings.System.getInt(service.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,0);
                Settings.System.putInt(service.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,current==1?0:1);
                paintTiles();return;
            }
            case "勿扰":{
                NotificationManager notifications=(NotificationManager)service.getSystemService(Context.NOTIFICATION_SERVICE);
                if(notifications==null)return;
                if(!notifications.isNotificationPolicyAccessGranted()){
                    start(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    toast("请允许勿扰权限后返回");return;
                }
                boolean active=notifications.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL;
                notifications.setInterruptionFilter(active?NotificationManager.INTERRUPTION_FILTER_ALL:NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                paintTiles();return;
            }
            case "深色模式":{
                UiModeManager uiMode=(UiModeManager)service.getSystemService(Context.UI_MODE_SERVICE);
                if(uiMode==null)return;
                boolean dark=(service.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    ==android.content.res.Configuration.UI_MODE_NIGHT_YES;
                uiMode.setNightMode(dark?UiModeManager.MODE_NIGHT_NO:UiModeManager.MODE_NIGHT_YES);
                return;
            }
        }
    }
    private void paintTiles(){
        if(wifiTile==null||torchTile==null)return;
        boolean rotate=Settings.System.getInt(service.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,0)==1;
        NotificationManager notifications=(NotificationManager)service.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean dnd=notifications!=null&&notifications.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL;
        boolean dark=(service.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            ==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        paintCircle(wifiTile,wifiOn,BLUE,0xffffffff);
        paintCircle(btTile,btOn,BLUE,0xffffffff);
        paintCircle(dataTile,dataOn,BLUE,0xffffffff);
        paintCircle(airTile,airOn,BLUE,0xffffffff);
        paintCircle(torchTile,torchOn,TORCH_ON,TORCH_GLYPH);
        paintCircle(rotateTile,rotate,BLUE,0xffffffff);
        paintCircle(dndTile,dnd,INDIGO,0xffffffff);
        paintCircle(darkTile,dark,BLUE,0xffffffff);
    }
    private void paintCircle(LinearLayout box,boolean active,int activeBg,int activeGlyph){
        Object[] parts=(Object[])box.getTag();
        FrameLayout circle=(FrameLayout)parts[0];
        TileIcon glyph=(TileIcon)parts[1];TextView label=(TextView)parts[2];
        glyph.setColor(active?activeGlyph:0xfff2f2f7);
        label.setTextColor(active?0xffffffff:MUTED);
        circle.setBackground(HomeStyle.surface(circle,active?activeBg:0x33787880,999));
    }
    private void paintBattery(){
        if(batteryLabel==null||batteryPercent<0)return;
        batteryLabel.setText(batteryCharging?"⚡ "+batteryPercent+"%":batteryPercent+"%");
    }
    private void setTorchListening(boolean on){
        try{
            if(on)torchManager.registerTorchCallback(torchCallback,main);
            else torchManager.unregisterTorchCallback(torchCallback);
        }catch(RuntimeException ignored){}
    }
    private void openWriteSettings(){
        start(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:"+service.getPackageName())));
        toast("请允许修改系统设置后返回");
    }

    // ---- sliders ----
    private View brightnessRow(){
        brightnessSlider=new PillSlider(service,true);
        PillSlider slider=brightnessSlider;
        boolean granted=Settings.System.canWrite(service);
        int value;
        try{value=Settings.System.getInt(service.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,120);}
        catch(RuntimeException e){value=120;}
        slider.setValue(value/255f);
        slider.setEnabled(granted);
        slider.setChange(t->{
            try{Settings.System.putInt(service.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,Math.max(2,Math.round(t*255f)));}
            catch(RuntimeException ignored){}
        });
        if(!granted)slider.setOnClickListener(v->openWriteSettings());
        return slider;
    }
    private View volumeRow(){
        AudioManager audio=(AudioManager)service.getSystemService(Context.AUDIO_SERVICE);
        volumeSlider=new PillSlider(service,false);
        PillSlider slider=volumeSlider;
        int max=audio!=null?audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC):15;
        int now=audio!=null?audio.getStreamVolume(AudioManager.STREAM_MUSIC):8;
        slider.setValue(max>0?Math.min(1f,now/(float)max):0f);
        slider.setChange(t->{
            try{audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.round(t*max),0);}catch(RuntimeException ignored){}
        });
        return slider;
    }

    // ---- notifications ----
    private void refreshNotifications(){
        if(closed||notifBox==null)return;
        notifBox.removeAllViews();
        if(!DuoNotifications.available()){
            boolean helper=MobileHelper.ready();
            notifBox.addView(hintRow(helper?"通知服务未连接，点此重试":"通知读取未授权，点击前往授权",
                helper?v->{DuoNotifications.ensureBound();toast("正在重连通知服务…");}
                    :v->start(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
            return;
        }
        List<DuoNotifications.Item> items=DuoNotifications.snapshot();
        if(notifHead!=null)notifHead.setText(items.isEmpty()?"通知":"通知 · "+items.size());
        if(items.isEmpty()){
            TextView empty=HomeStyle.text(service,"没有新通知",14,MUTED);
            empty.setGravity(Gravity.CENTER);
            notifBox.addView(empty,new LinearLayout.LayoutParams(-1,dp(56)));
        }
        for(DuoNotifications.Item item:items)notifBox.addView(notifCard(item));
    }
    private View hintRow(String text,View.OnClickListener action){
        LinearLayout row=new LinearLayout(service);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14),dp(12),dp(14),dp(12));
        row.setBackground(HomeStyle.surface(row,CARD,20));
        TextView label=HomeStyle.text(service,text,13,action==null?MUTED:BLUE);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null)row.setOnClickListener(action);
        return row;
    }
    private View notifCard(DuoNotifications.Item item){
        LinearLayout card=new LinearLayout(service);card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(12),dp(11),dp(12),dp(11));
        card.setBackground(HomeStyle.surface(card,CARD,20));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);
        size.bottomMargin=dp(8);card.setLayoutParams(size);
        ImageView icon=new ImageView(service);
        icon.setImageBitmap(appIcon(item.packageName));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(icon,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout labels=new LinearLayout(service);labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12),0,dp(6),0);
        LinearLayout meta=new LinearLayout(service);
        TextView app=HomeStyle.text(service,item.label,11,MUTED);
        app.setSingleLine(true);app.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(app,new LinearLayout.LayoutParams(0,-2,1));
        meta.addView(HomeStyle.text(service,timeFormat.format(new Date(item.when)),11,MUTED),new LinearLayout.LayoutParams(-2,-2));
        labels.addView(meta,new LinearLayout.LayoutParams(-1,dp(16)));
        TextView title=HomeStyle.text(service,item.title.isEmpty()?item.label:item.title,15,TEXT);
        title.setTypeface(MEDIUM);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title,new LinearLayout.LayoutParams(-1,dp(22)));
        TextView text=HomeStyle.text(service,item.text,13,MUTED);
        text.setMaxLines(2);text.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(text,new LinearLayout.LayoutParams(-1,-2));
        card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        card.setOnClickListener(v->{
            // Launch onto the VIRTUAL content display the desktop lives on; the physical
            // panel this shade covers would leave the app hidden behind our own overlay.
            int physical=display==null?-1:display.getDisplayId();
            int content=FixedDualSession.active()?FixedDualSession.contentIdForPhysical(physical):physical;
            if(item.open(service,content))close();
        });
        card.setOnLongClickListener(v->{DuoNotifications.cancel(item.key);return true;});
        return card;
    }
    private Bitmap appIcon(String packageName){
        PackageManager packages=service.getPackageManager();
        int pixels=dp(38);
        Bitmap bitmap=Bitmap.createBitmap(pixels,pixels,Bitmap.Config.ARGB_8888);
        try{Drawable drawable=packages.getApplicationIcon(packageName);drawable.setBounds(0,0,pixels,pixels);drawable.draw(new Canvas(bitmap));}
        catch(Exception unavailable){Drawable fallback=packages.getDefaultActivityIcon();fallback.setBounds(0,0,pixels,pixels);fallback.draw(new Canvas(bitmap));}
        return bitmap;
    }
    private int dp(float value){return Math.round(value*service.getResources().getDisplayMetrics().density);}

    /**
     * Shade gesture host following the SystemUI recipe:
     * {@link android.view.VelocityTracker} with the platform minimum fling velocity
     * decides flicks (real shades use ~50dp/s, not position math); the neutralized
     * ScrollView lets the host grab at-top upward drags to carry the shade away.
     */
    private final class PaneHost extends FrameLayout {
        private static final byte UNDECIDED=0,FADE=1,DISMISS=2,VERTICAL=3;
        private byte mode=UNDECIDED;
        private float downX,downY,startFade,lastTouchY;
        private boolean onList;
        private android.view.VelocityTracker tracker;
        private final int minFling=android.view.ViewConfiguration.get(service).getScaledMinimumFlingVelocity();
        PaneHost(Context context){super(context);}
        boolean dragging(){return mode==FADE||mode==DISMISS;}
        void recycle(){if(tracker!=null){tracker.recycle();tracker=null;}}
        private boolean listAtTop(){return notifList==null||notifList.getScrollY()<=2;}
        private float velX(){if(tracker==null)return 0;tracker.computeCurrentVelocity(1000);return tracker.getXVelocity();}
        private float velY(){if(tracker==null)return 0;tracker.computeCurrentVelocity(1000);return tracker.getYVelocity();}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                if(tracker==null)tracker=android.view.VelocityTracker.obtain();
                tracker.clear();
            }
            if(tracker!=null)tracker.addMovement(event);
            return super.dispatchTouchEvent(event);
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=event.getX();downY=event.getY();lastTouchY=downY;startFade=fade;
                    onList=overView(event,notifList);
                    mode=overSlider(event)?VERTICAL:UNDECIDED;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if(mode==UNDECIDED){
                        float dx=event.getX()-downX,dy=event.getY()-downY;
                        if(Math.abs(dx)>dp(16)&&Math.abs(dx)>1.4f*Math.abs(dy)){
                            mode=FADE;
                            getParent().requestDisallowInterceptTouchEvent(true);
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            notifPane.setVisibility(View.VISIBLE);controlPane.setVisibility(View.VISIBLE);
                            return true;
                        }
                        // Verticals: the control page and the empty grab-zone below the
                        // list dismiss from anywhere; on the list itself only at its top.
                        if(dy<-dp(14)&&-dy>1.4f*Math.abs(dx)&&(control||!onList||listAtTop())){
                            mode=DISMISS;
                            getParent().requestDisallowInterceptTouchEvent(true);
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            return true;
                        }
                        if(Math.abs(dy)>dp(22))mode=VERTICAL;
                    }
                    return false;
                default:return false;
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=event.getX();downY=event.getY();lastTouchY=downY;startFade=fade;
                    onList=overView(event,notifList);
                    mode=overSlider(event)?VERTICAL:UNDECIDED;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(mode==UNDECIDED){
                        float dx=event.getX()-downX,dy=event.getY()-downY;
                        if(Math.abs(dx)>dp(16)&&Math.abs(dx)>1.4f*Math.abs(dy)){
                            mode=FADE;performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            notifPane.setVisibility(View.VISIBLE);controlPane.setVisibility(View.VISIBLE);
                        }else if(dy<-dp(14)&&-dy>1.4f*Math.abs(dx)&&(control||!onList||listAtTop())){
                            mode=DISMISS;performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        }
                    }
                    if(mode==FADE){
                        float width=getWidth();
                        fade=Math.max(0f,Math.min(1f,startFade-(event.getX()-downX)/(width*0.5f)));
                        applyFade();
                        return true;
                    }
                    if(mode==DISMISS){
                        panel.setTranslationY(Math.max(-panel.getHeight()*0.6f,
                            Math.min(0f,panel.getTranslationY()+(event.getY()-lastTouchY)*0.55f)));
                        lastTouchY=event.getY();
                        return true;
                    }
                    lastTouchY=event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if(mode==UNDECIDED){
                        // Ultra-fast flicks may deliver no intermediate MOVE at all.
                        float dx=event.getX()-downX,dy=event.getY()-downY;
                        float width=getWidth();
                        if(Math.abs(dx)>dp(40)&&Math.abs(dx)>1.4f*Math.abs(dy)&&width>0){
                            mode=FADE;
                            fade=Math.max(0f,Math.min(1f,startFade-dx/(width*0.5f)));
                        }else if(dy<-dp(40)&&-dy>1.4f*Math.abs(dx)&&(control||!onList||listAtTop())){
                            mode=DISMISS;
                            panel.setTranslationY(dy*0.55f);
                        }
                    }
                    if(mode==FADE){
                        float width=getWidth();
                        float vx=velX();
                        boolean flick=Math.abs(vx)>=minFling&&Math.abs(event.getX()-downX)>dp(4);
                        float projected=Math.max(0f,Math.min(1f,fade-vx*220f/(width*1000f)));
                        boolean want=flick?vx<0:projected>=0.25f;
                        animateFadeTo(want);
                    }else if(mode==DISMISS){
                        finishDismiss();
                    }
                    mode=UNDECIDED;
                    return true;
                default:return true;
            }
        }
        private void finishDismiss(){
            float raised=-panel.getTranslationY();
            if(raised>panel.getHeight()*0.06f||velY()<=-minFling)close();
            else panel.animate().translationY(0f).setDuration(200).start();
        }
        /** Sliders own their horizontal drags; never steal gestures starting on them. */
        private boolean overSlider(MotionEvent event){
            for(PillSlider slider:new PillSlider[]{brightnessSlider,volumeSlider})
                if(overView(event,slider))return true;
            return false;
        }
        private boolean overView(MotionEvent event,View view){
            if(view==null||!view.isShown())return false;
            int[] viewAt=new int[2],mine=new int[2];
            view.getLocationOnScreen(viewAt);getLocationOnScreen(mine);
            float x=event.getX()-(viewAt[0]-mine[0]),y=event.getY()-(viewAt[1]-mine[1]);
            return x>=0&&y>=0&&x<=view.getWidth()&&y<=view.getHeight();
        }
    }

    /** Dock-tinted glass that stays solid and melts to transparent over the last stretch. */
    private static final class GlassFade extends android.graphics.drawable.Drawable {
        private final int color;
        private final int fadePixels;
        private final Paint paint=new Paint();
        GlassFade(int color,int fadePixels){this.color=color;this.fadePixels=fadePixels;}
        @Override public void draw(Canvas canvas){
            android.graphics.Rect bounds=getBounds();
            if(bounds.height()<=0)return;
            float stop=Math.max(0f,1f-fadePixels/(float)bounds.height());
            paint.setShader(new android.graphics.LinearGradient(0,0,0,bounds.height(),
                new int[]{color,color,color&0x00ffffff},new float[]{0f,stop,1f},android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,bounds.width(),bounds.height(),paint);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    /** Thick iOS-style pill slider: white fill with the glyph punched out (DST_OUT). */
    private static final class PillSlider extends View {
        private final boolean sun;
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cutFill=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cutStroke=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float t;
        private Consumer<Float> change;
        PillSlider(Context context,boolean sun){
            super(context);this.sun=sun;
            cutStroke.setStrokeWidth(1.8f);cutStroke.setStyle(Paint.Style.STROKE);
            cutStroke.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
        }
        void setValue(float value){t=Math.max(0f,Math.min(1f,value));invalidate();}
        void setChange(Consumer<Float> action){change=action;}
        private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!isEnabled())return false;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                case MotionEvent.ACTION_MOVE:{
                    float start=dp(48),usable=getWidth()-start-dp(16);
                    float value=Math.max(0f,Math.min(1f,(event.getX()-start)/usable));
                    if(value!=t){t=value;invalidate();if(change!=null)change.accept(t);}
                    return true;
                }
                default:return true;
            }
        }
        @Override protected void onDraw(Canvas canvas){
            float width=getWidth(),height=getHeight(),radius=height/2f;
            fill.setColor(isEnabled()?0x2ef2f2f7:0x1af2f2f7);
            canvas.drawRoundRect(0,0,width,height,radius,radius,fill);
            float start=dp(48);
            float end=start+t*(width-start-dp(16));
            if(end>start){
                int layer=canvas.saveLayer(0,0,width,height,null);
                fill.setColor(0xe6f2f2f7);
                canvas.drawRoundRect(0,0,end,height,radius,radius,fill);
                cutFill.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                cutStroke.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                float scale=dp(20)/24f;
                canvas.save();
                canvas.translate(dp(14),(height-dp(20))/2f);
                canvas.scale(scale,scale);
                if(sun)drawSun(canvas);else drawSpeaker(canvas);
                canvas.restore();
                cutFill.setXfermode(null);cutStroke.setXfermode(null);
                canvas.restoreToCount(layer);
            }
        }
        private void drawSun(Canvas canvas){
            cutFill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(12,12,3.7f,cutFill);
            for(int ray=0;ray<8;ray++){
                double angle=Math.toRadians(ray*45);
                canvas.drawLine(12+(float)Math.cos(angle)*5.6f,12+(float)Math.sin(angle)*5.6f,
                    12+(float)Math.cos(angle)*8.1f,12+(float)Math.sin(angle)*8.1f,cutStroke);
            }
        }
        private void drawSpeaker(Canvas canvas){
            Path body=new Path();
            body.moveTo(4,9.6f);body.lineTo(7.6f,9.6f);body.lineTo(12,5.4f);body.lineTo(12,18.6f);
            body.lineTo(7.6f,14.4f);body.lineTo(4,14.4f);body.close();
            cutFill.setStyle(Paint.Style.FILL);
            canvas.drawPath(body,cutFill);
            canvas.drawArc(13.4f,7f,19.4f,17f,-46f,92f,false,cutStroke);
            canvas.drawArc(14.6f,3.6f,22.4f,20.4f,-42f,84f,false,cutStroke);
        }
    }

    /** Linear 24-grid glyphs so toggles read like iOS symbols without image assets. */
    private static final class TileIcon extends View {
        static final int TORCH=0,ROTATE=1,DND=2,DARK=3,WIFI=4,BLUETOOTH=5,CELL=6,AIRPLANE=7,GEAR=8;
        private final int type;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color=0xfff2f2f7;
        TileIcon(Context context,int type){super(context);this.type=type;
            paint.setStrokeWidth(1.7f);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        void setColor(int value){color=value;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float scale=Math.min(getWidth(),getHeight())/24f;
            canvas.save();canvas.scale(scale,scale);
            paint.setColor(color);paint.setStyle(Paint.Style.STROKE);
            switch(type){
                case TORCH:{
                    Path head=new Path();head.moveTo(8.5f,3.5f);head.lineTo(15.5f,3.5f);head.lineTo(14f,9f);head.lineTo(10f,9f);head.close();
                    canvas.drawPath(head,paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(10f,10.5f,14f,19.5f,1.5f,1.5f,paint);
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(9f,21f,15f,21f,paint);
                    break;
                }
                case ROTATE:{
                    canvas.drawArc(4f,4f,20f,20f,-50f,260f,false,paint);
                    paint.setStyle(Paint.Style.FILL);
                    Path arrow=new Path();arrow.moveTo(17.5f,2.5f);arrow.lineTo(21f,6.5f);arrow.lineTo(15.8f,7.2f);arrow.close();
                    canvas.drawPath(arrow,paint);
                    paint.setStyle(Paint.Style.STROKE);
                    break;
                }
                case DND:{
                    // iOS crescent moon: filled circle with an offset circle punched out.
                    int layer=canvas.saveLayer(0,0,24,24,null);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(12,12,8.4f,paint);
                    paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                    canvas.drawCircle(15.8f,9.4f,7.6f,paint);
                    paint.setXfermode(null);
                    canvas.restoreToCount(layer);
                    paint.setStyle(Paint.Style.STROKE);
                    break;
                }
                case DARK:{
                    canvas.drawCircle(12,12,8.2f,paint);
                    paint.setStyle(Paint.Style.FILL);
                    Path half=new Path();half.addArc(3.8f,3.8f,20.2f,20.2f,-90f,180f);half.close();
                    canvas.drawPath(half,paint);
                    break;
                }
                case WIFI:{
                    paint.setStrokeWidth(1.9f);
                    canvas.drawArc(4.5f,9f,19.5f,24f,-135f,90f,false,paint);
                    canvas.drawArc(7f,11.5f,17f,21.5f,-135f,90f,false,paint);
                    canvas.drawArc(9.5f,14f,14.5f,19f,-135f,90f,false,paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(12,16.5f,1.3f,paint);
                    break;
                }
                case BLUETOOTH:{
                    Path rune=new Path();
                    rune.moveTo(12,2.6f);rune.lineTo(12,21.4f);
                    rune.moveTo(12,2.6f);rune.lineTo(16.6f,7.2f);rune.lineTo(12,11.8f);
                    rune.lineTo(16.6f,16.6f);rune.lineTo(12,21.4f);
                    rune.moveTo(12,11.8f);rune.lineTo(7.4f,16.4f);
                    canvas.drawPath(rune,paint);
                    break;
                }
                case CELL:{
                    paint.setStrokeWidth(2.6f);
                    canvas.drawLine(5.2f,19f,5.2f,14.5f,paint);
                    canvas.drawLine(9.8f,19f,9.8f,10.5f,paint);
                    canvas.drawLine(14.2f,19f,14.2f,6.5f,paint);
                    canvas.drawLine(18.8f,19f,18.8f,2.5f,paint);
                    break;
                }
                case AIRPLANE:{
                    paint.setStyle(Paint.Style.FILL);
                    Path plane=new Path();
                    plane.moveTo(12,2.4f);
                    plane.lineTo(13.4f,8.2f);plane.lineTo(21,12.6f);plane.lineTo(21,14.6f);
                    plane.lineTo(13.4f,12.4f);plane.lineTo(13.2f,17.8f);plane.lineTo(15.8f,20.2f);
                    plane.lineTo(15.8f,21.6f);plane.lineTo(12,20.4f);plane.lineTo(8.2f,21.6f);
                    plane.lineTo(8.2f,20.2f);plane.lineTo(10.8f,17.8f);plane.lineTo(10.6f,12.4f);
                    plane.lineTo(3,14.6f);plane.lineTo(3,12.6f);plane.lineTo(10.6f,8.2f);
                    plane.close();
                    canvas.drawPath(plane,paint);
                    break;
                }
                case GEAR:{
                    canvas.drawCircle(12,12,6.6f,paint);
                    canvas.drawCircle(12,12,2.9f,paint);
                    for(int tooth=0;tooth<8;tooth++){
                        double angle=Math.toRadians(tooth*45+22.5);
                        canvas.drawLine(12+(float)Math.cos(angle)*7.6f,12+(float)Math.sin(angle)*7.6f,
                            12+(float)Math.cos(angle)*10.6f,12+(float)Math.sin(angle)*10.6f,paint);
                    }
                    break;
                }
            }
            canvas.restore();
        }
    }
}
