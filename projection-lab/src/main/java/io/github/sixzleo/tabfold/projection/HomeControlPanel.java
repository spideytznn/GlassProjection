package io.github.sixzleo.tabfold.projection;

import android.app.NotificationManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Two HyperOS-style panes: top-left pull = notification center, top-right = control center. */
final class HomeControlPanel {
    private static final int MIUI_BLUE=0xff3c7bfa,TILE_IDLE=0x26ffffff;
    static final int MODE_NOTIFICATIONS=0,MODE_CONTROL=1;
    final HomeSheet sheet;
    private final DuoHomeActivity activity;
    private final boolean control;
    private final LinearLayout notifBox;
    private TextView notifHead;
    private LinearLayout torchTile,rotateTile,dndTile,darkTile;
    private final SimpleDateFormat timeFormat=new SimpleDateFormat("HH:mm",Locale.CHINA);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable change=this::refreshNotifications;
    private CameraManager torchManager;
    private String torchId;
    private boolean torchOn,closed;
    private final CameraManager.TorchCallback torchCallback=new CameraManager.TorchCallback(){
        @Override public void onTorchModeChanged(String id,boolean enabled){torchOn=enabled;main.post(()->{if(!closed)paintTiles();});}
    };

    HomeControlPanel(DuoHomeActivity activity,boolean control){
        this.activity=activity;this.control=control;
        sheet=new HomeSheet(activity,440,control?460:560);sheet.header(control?"控制中心":"通知中心");
        if(control){
            sheet.content.addView(dateBatteryRow(),new LinearLayout.LayoutParams(-1,dp(30)));
            sheet.content.addView(brightnessBar(),new LinearLayout.LayoutParams(-1,dp(48)));
            torchTile=tileLabel("手电筒",TileIcon.TORCH);rotateTile=tileLabel("自动旋转",TileIcon.ROTATE);
            dndTile=tileLabel("勿扰",TileIcon.DND);darkTile=tileLabel("深色模式",TileIcon.DARK);
            android.widget.GridLayout grid=new android.widget.GridLayout(activity);grid.setColumnCount(4);
            grid.addView(torchTile,tileSpec());grid.addView(rotateTile,tileSpec());
            grid.addView(dndTile,tileSpec());grid.addView(darkTile,tileSpec());
            sheet.content.addView(grid,new LinearLayout.LayoutParams(-1,dp(84)));
        }else notifHead=null;
        notifHead=HomeStyle.text(activity,"通知",15,HomeStyle.TEXT);
        notifHead.setPadding(dp(4),dp(12),dp(4),dp(6));
        LinearLayout headRow=new LinearLayout(activity);headRow.setGravity(Gravity.CENTER_VERTICAL);
        notifHead.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        headRow.addView(notifHead);
        headRow.addView(HomeStyle.button(activity,"全部清除",DuoNotifications::cancelAll),new LinearLayout.LayoutParams(-2,dp(34)));
        sheet.content.addView(headRow,new LinearLayout.LayoutParams(-1,dp(36)));
        notifBox=new LinearLayout(activity);notifBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(notifBox,new ViewGroup.LayoutParams(-1,-2));
        sheet.content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        torchManager=(CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);
        try{
            for(String id:torchManager.getCameraIdList()){
                Boolean flash=torchManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if(flash!=null&&flash){torchId=id;break;}
            }
        }catch(CameraAccessException ignored){}
        sheet.setOnDismissListener(dialog->{closed=true;torchManager.unregisterTorchCallback(torchCallback);DuoNotifications.forget(change);});
    }
    void show(){
        torchManager.registerTorchCallback(torchCallback,null);
        DuoNotifications.observe(change);
        activity.show(sheet);
        if(control)paintTiles();
        refreshNotifications();
    }
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}

    private View dateBatteryRow(){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(4),0,dp(4),0);
        String date=new SimpleDateFormat("M月d日 EEEE",Locale.CHINA).format(new Date());
        row.addView(HomeStyle.text(activity,date,13,HomeStyle.MUTED),new LinearLayout.LayoutParams(0,-1,1));
        row.addView(HomeStyle.text(activity,batteryPercent()+"%",12,HomeStyle.MUTED),new LinearLayout.LayoutParams(-2,-1));
        return row;
    }
    private int batteryPercent(){
        Intent sticky=activity.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(sticky==null)return 0;
        int level=sticky.getIntExtra("level",-1),scale=sticky.getIntExtra("scale",100);
        return level>=0&&scale>0?Math.round(level*100f/scale):0;
    }
    private LinearLayout tileLabel(String label,int icon){
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);box.setBackground(HomeStyle.surface(box,TILE_IDLE,22));
        TileIcon glyph=new TileIcon(activity,icon);box.addView(glyph,new LinearLayout.LayoutParams(dp(25),dp(25)));
        TextView text=HomeStyle.text(activity,label,11,HomeStyle.TEXT);text.setGravity(Gravity.CENTER);
        box.addView(text,new LinearLayout.LayoutParams(-2,-2));
        box.setOnClickListener(v->onTile(label));
        box.setTag(new Object[]{glyph,text});
        return box;
    }
    private android.widget.GridLayout.LayoutParams tileSpec(){
        android.widget.GridLayout.LayoutParams spec=new android.widget.GridLayout.LayoutParams(
            android.widget.GridLayout.spec(0,1,1f),android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1,1f));
        spec.width=0;spec.height=dp(78);
        spec.setMargins(dp(3),0,dp(3),0);
        return spec;
    }
    private void onTile(String label){
        switch(label){
            case "手电筒":{
                if(torchId==null){activity.message("没有可用的闪光灯");return;}
                try{torchManager.setTorchMode(torchId,!torchOn);}catch(CameraAccessException ignored){}
                return;
            }
            case "自动旋转":{
                if(!Settings.System.canWrite(activity)){openWriteSettings();return;}
                int current=Settings.System.getInt(activity.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,0);
                Settings.System.putInt(activity.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,current==1?0:1);
                paintTiles();return;
            }
            case "勿扰":{
                NotificationManager notifications=(NotificationManager)activity.getSystemService(Context.NOTIFICATION_SERVICE);
                if(notifications==null)return;
                if(!notifications.isNotificationPolicyAccessGranted()){
                    activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    activity.message("请允许勿扰权限后返回");return;
                }
                boolean active=notifications.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL;
                notifications.setInterruptionFilter(active?NotificationManager.INTERRUPTION_FILTER_ALL:NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                paintTiles();return;
            }
            case "深色模式":{
                UiModeManager uiMode=(UiModeManager)activity.getSystemService(Context.UI_MODE_SERVICE);
                if(uiMode==null)return;
                boolean dark=(activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    ==android.content.res.Configuration.UI_MODE_NIGHT_YES;
                uiMode.setNightMode(dark?UiModeManager.MODE_NIGHT_NO:UiModeManager.MODE_NIGHT_YES);
                return;
            }
        }
    }
    private void paintTiles(){
        if(closed)return;
        boolean rotate=Settings.System.getInt(activity.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,0)==1;
        NotificationManager notifications=(NotificationManager)activity.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean dnd=notifications!=null&&notifications.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL;
        boolean dark=(activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            ==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        paintTile(torchTile,torchOn);paintTile(rotateTile,rotate);paintTile(dndTile,dnd);paintTile(darkTile,dark);
    }
    private void paintTile(LinearLayout tile,boolean active){
        Object[] parts=(Object[])tile.getTag();
        TileIcon glyph=(TileIcon)parts[0];TextView label=(TextView)parts[1];
        int color=active?MIUI_BLUE:HomeStyle.TEXT;
        glyph.setColor(color);
        label.setTextColor(active?MIUI_BLUE:HomeStyle.TEXT);
        tile.setBackground(HomeStyle.surface(tile,TILE_IDLE,22));
    }
    private void openWriteSettings(){
        activity.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:"+activity.getPackageName())));
        activity.message("请允许修改系统设置后返回");
    }
    private View brightnessBar(){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),0,dp(12),0);
        row.setBackground(HomeStyle.surface(row,HomeStyle.FIELD,HomeStyle.FIELD_RADIUS));
        LinearLayout.LayoutParams rowSize=new LinearLayout.LayoutParams(-1,dp(46));rowSize.topMargin=dp(6);
        row.setLayoutParams(rowSize);
        TextView sun=HomeStyle.text(activity,"☀",15,HomeStyle.TEXT);sun.setGravity(Gravity.CENTER);
        row.addView(sun,new LinearLayout.LayoutParams(dp(26),-1));
        SeekBar seek=new SeekBar(activity);
        boolean granted=Settings.System.canWrite(activity);
        seek.setMax(255);
        try{seek.setProgress(Settings.System.getInt(activity.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,120));}catch(RuntimeException ignored){}
        seek.setEnabled(granted);
        seek.setProgressTintList(ColorStateList.valueOf(0xffffffff));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x33ffffff));
        seek.setThumbTintList(ColorStateList.valueOf(0xffffffff));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
                if(!fromUser)return;
                try{Settings.System.putInt(activity.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,value);}catch(RuntimeException ignored){}
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        row.addView(seek,new LinearLayout.LayoutParams(0,dp(42),1));
        if(!granted)row.setOnClickListener(v->openWriteSettings());
        return row;
    }
    private void refreshNotifications(){
        if(closed)return;
        notifBox.removeAllViews();
        if(!DuoNotifications.available()){
            notifBox.addView(hintRow("通知读取未授权，点击前往授权",v->activity.startActivity(
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
            return;
        }
        List<DuoNotifications.Item> items=DuoNotifications.snapshot();
        notifHead.setText(items.isEmpty()?"通知":(control?"通知 · ":"")+"通知 · "+items.size());
        if(items.isEmpty()){notifBox.addView(hintRow("没有新通知",null));return;}
        for(DuoNotifications.Item item:items)notifBox.addView(notifCard(item));
    }
    private View hintRow(String text,View.OnClickListener action){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));
        row.setBackground(HomeStyle.surface(row,HomeStyle.FIELD,HomeStyle.FIELD_RADIUS));
        TextView label=HomeStyle.text(activity,text,13,action==null?HomeStyle.MUTED:HomeStyle.ACCENT);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null)row.setOnClickListener(action);
        return row;
    }
    private View notifCard(DuoNotifications.Item item){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(11),dp(10),dp(11),dp(10));
        card.setBackground(HomeStyle.surface(card,0x24ffffff,22));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.topMargin=dp(7);
        card.setLayoutParams(size);
        ImageView icon=new ImageView(activity);
        icon.setImageBitmap(appIcon(item.packageName));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(icon,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(12),0,dp(8),0);
        LinearLayout meta=new LinearLayout(activity);
        TextView app=HomeStyle.text(activity,item.label,10,HomeStyle.MUTED);
        app.setSingleLine(true);app.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(app,new LinearLayout.LayoutParams(0,-2,1));
        meta.addView(HomeStyle.text(activity,timeFormat.format(new Date(item.when)),10,HomeStyle.MUTED),new LinearLayout.LayoutParams(-2,-2));
        labels.addView(meta,new LinearLayout.LayoutParams(-1,-2));
        TextView title=HomeStyle.text(activity,item.title.isEmpty()?item.label:item.title,14,HomeStyle.TEXT);
        title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);
        TextView text=HomeStyle.text(activity,item.text,12,HomeStyle.MUTED);
        text.setMaxLines(2);text.setEllipsize(TextUtils.TruncateAt.END);labels.addView(text);
        card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        card.setOnClickListener(v->{if(item.open(activity))sheet.dismiss();});
        card.setOnLongClickListener(v->{DuoNotifications.cancel(item.key);return true;});
        return card;
    }
    private Bitmap appIcon(String packageName){
        PackageManager packages=activity.getPackageManager();
        int pixels=dp(38);
        Bitmap bitmap=Bitmap.createBitmap(pixels,pixels,Bitmap.Config.ARGB_8888);
        try{Drawable drawable=packages.getApplicationIcon(packageName);drawable.setBounds(0,0,pixels,pixels);drawable.draw(new Canvas(bitmap));}
        catch(Exception unavailable){Drawable fallback=packages.getDefaultActivityIcon();fallback.setBounds(0,0,pixels,pixels);fallback.draw(new Canvas(bitmap));}
        return bitmap;
    }

    /** Hand-drawn linear glyphs so tiles read like HyperOS without image assets. */
    private static final class TileIcon extends View {
        static final int TORCH=0,ROTATE=1,DND=2,DARK=3;
        private final int type;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color=HomeStyle.TEXT;
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
                    canvas.drawCircle(12f,12f,8.2f,paint);
                    canvas.drawLine(6.8f,12f,17.2f,12f,paint);
                    break;
                }
                case DARK:{
                    canvas.drawCircle(12f,12f,8.2f,paint);
                    paint.setStyle(Paint.Style.FILL);
                    Path half=new Path();half.addArc(3.8f,3.8f,20.2f,20.2f,-90f,180f);half.close();
                    canvas.drawPath(half,paint);
                    break;
                }
            }
            canvas.restore();
        }
    }
}
