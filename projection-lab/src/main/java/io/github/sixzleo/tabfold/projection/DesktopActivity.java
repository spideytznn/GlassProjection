package io.github.sixzleo.tabfold.projection;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.function.IntConsumer;

public final class DesktopActivity extends Activity {
    private static final int BG=0xff10191c,CARD=0xff1b272b,TEXT=0xffedf4f3,MUTED=0xffa6b9bb,ACCENT=0xffa4e6d6;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView state,hint;
    private TextView mobileStatus;
    private View updateDot;
    private Button updateButton;
    private UpdateCoordinator updates;
    private final Runnable updateBadge=()->{
        if(updateDot!=null){boolean available=updates.hasUpdate();updateDot.setVisibility(available?View.VISIBLE:View.GONE);
            updateButton.setContentDescription(available?"检查更新，有新版本":"检查更新");}
    };
    private Button service;
    private Button gestures;
    private boolean switchingGestures;
    private Button blacklist;
    private SeekBar blur,stretch,open,close,holdTime,startAngle;
    private Switch swipeRestore;
    private boolean waitingNotifications;
    private final Runnable tick=new Runnable(){public void run(){refreshStatus();handler.postDelayed(this,700);}};
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);waitingNotifications=saved!=null&&saved.getBoolean("waitingNotifications");AnimationSettings.init(this);MobileHelper.init(this);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bar=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bar.left,bar.top,bar.right,bar.bottom);return insets;
        });
        FrameLayout frame=new FrameLayout(this);scroll.addView(frame,new ScrollView.LayoutParams(-1,-2));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(24),dp(22),dp(24),dp(24));
        int width=Math.min(getResources().getDisplayMetrics().widthPixels,dp(680));
        frame.addView(page,new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        updates=UpdateCoordinator.get(this);
        // Fixed dual is the only user-facing mode; legacy projection sections stay
        // reachable only when dual was explicitly disabled through adb.
        final boolean legacy=!FixedDualSession.enabled(this);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);page.addView(top);
        TextView mark=text("GLASS / FOLD",11,ACCENT);mark.setLetterSpacing(.18f);top.addView(mark,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout updateEntry=new FrameLayout(this);top.addView(updateEntry,new LinearLayout.LayoutParams(dp(112),dp(48)));
        LinearLayout buttonHolder=new LinearLayout(this);updateEntry.addView(buttonHolder,new FrameLayout.LayoutParams(-1,-1));
        updateButton=button(buttonHolder,"检查更新",()->startActivity(new Intent(this,UpdateActivity.class)),false);
        updateButton.setLayoutParams(new LinearLayout.LayoutParams(-1,-1));
        updateDot=new View(this);updateDot.setBackground(background(0xffff5b61,4));updateDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams dotLayout=new FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP|Gravity.RIGHT);dotLayout.topMargin=dp(7);dotLayout.rightMargin=dp(5);updateEntry.addView(updateDot,dotLayout);updateDot.setVisibility(View.GONE);
        TextView title=text("玻璃投影",32,TEXT);title.setTypeface(null,Typeface.BOLD);title.setPadding(0,dp(9),0,dp(6));page.addView(title);
        page.addView(text("让每一次开合，柔和衔接。",14,MUTED));space(page,24);
        LinearLayout status=card(page);state=text("正在连接",17,ACCENT);state.setTypeface(null,Typeface.BOLD);status.addView(state);
        hint=text("助手连接并开启无障碍后，固定双屏桌面自动运行。",13,MUTED);hint.setPadding(0,dp(7),0,dp(14));status.addView(hint);
        section(page,"玻璃桌面 · 双屏版","侧边 Dock、应用分页和手机现有小组件。使用桌面需设为默认桌面并开启无障碍服务。");
        button(card(page),"打开桌面",this::openDesktop,true);
        section(page,"① 连接手机端助手","首次配对一次，之后自动寻找本机并连接。不用填写 IP 和端口。");
        LinearLayout mobile=card(page);mobileStatus=text("",13,MUTED);mobile.addView(mobileStatus);
        button(mobile,"配对",this::pairWireless,true);
        mobile.addView(text("找不到开发者选项？"+DeveloperOptionsGuide.XIAOMI_PATH,12,MUTED));
        button(mobile,"如何开启开发者选项",()->DeveloperOptionsGuide.show(this),false);
        mobile.addView(text("默认打开配对小窗应用，在系统无线调试中打开配对码窗口，再回到小窗输入 6 位码。重启后若连接不上，请重新开启无线调试；一般无需再次配对。小米还需开启「USB 调试（安全设置）」。",12,MUTED));
        section(page,"② 开启无障碍","助手连接成功后，再开启「玻璃投影」无障碍服务，双屏桌面、状态栏和手势才能运行。");
        LinearLayout accessibility=card(page);
        service=button(accessibility,"开启无障碍",()->{
            if(!MobileHelper.ready()&&ProjectionService.instance==null){Toast.makeText(this,"请先完成第 1 步：连接手机端助手",Toast.LENGTH_SHORT).show();return;}
            AccessibilitySettings.open(this);
        },true);
        section(page,"全面屏手势 · 兼容模式","从左右边缘内滑返回；从底部上滑回桌面，上滑并停留打开小米最近任务。启用时隐藏底部三键。");
        LinearLayout navigation=card(page);
        gestures=button(navigation,"启用全面屏手势",this::toggleGestures,true);
        navigation.addView(text("手势由玻璃投影无障碍服务识别，主页、返回和最近任务仍交给系统执行。停用无障碍服务时会自动恢复三键导航。",12,MUTED));
        section(page,"后台运行","允许后台运行，退出设置页后也能继续使用。");
        LinearLayout background=card(page);
        button(background,"后台运行设置",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))),false);
        background.addView(text("退出设置页不会暂停动画，也不在最近任务中保留卡片。请允许后台自启动，并在小米后台设置中取消省电限制。",12,MUTED));
        if(legacy){
        section(page,"作用范围","选择动画出现的位置，半折悬停时自动恢复正常画面。");
        LinearLayout scope=card(page);
        Switch global=new Switch(this);global.setText("全局启用");global.setTextColor(TEXT);global.setTextSize(16);
        global.setChecked(AnimationSettings.globalEnabled);global.setPadding(0,dp(8),0,dp(8));
        global.setOnCheckedChangeListener((b,checked)->{AnimationSettings.global(checked);refreshStatus();});
        scope.addView(global,new LinearLayout.LayoutParams(-1,dp(56)));
        scope.addView(text("关闭：仅桌面和锁屏。开启：扩展到其他应用的可捕获画面。",13,MUTED));
        blacklist=button(scope,"应用黑名单",()->startActivity(new Intent(this,AppBlacklistActivity.class)),false);
        scope.addView(text("选中的应用不显示动画，离开后自动恢复。",12,MUTED));
        section(page,"恢复正常画面","悬停或滑动时，约 220 毫秒平滑回放；继续开合超过 10° 后重新跟随。");
        LinearLayout restore=card(page);
        holdTime=slider(restore,"悬停等待时间","默认 3 秒 · 在此时间内角度摆幅不超过 10° 时恢复。",1,10,1,AnimationSettings.holdSeconds," 秒",v->{AnimationSettings.hold(v);refreshStatus();});
        swipeRestore=new Switch(this);swipeRestore.setText("滑动恢复正常画面");swipeRestore.setTextColor(TEXT);swipeRestore.setTextSize(16);
        swipeRestore.setChecked(AnimationSettings.swipeRestore);swipeRestore.setPadding(0,dp(10),0,dp(8));
        swipeRestore.setOnCheckedChangeListener((b,checked)->AnimationSettings.swipe(checked));
        restore.addView(swipeRestore,new LinearLayout.LayoutParams(-1,dp(60)));
        restore.addView(text("检测到手指滑动就立即回放，静态页面上也有效。轻点不触发，应用仍正常响应手势。",12,MUTED));
        }
        section(page,"玻璃质感","调节雾化程度，保留原有的投影形状。");
        blur=slider(card(page),"模糊强度","100% 为当前默认效果；0% 关闭模糊。",0,200,5,AnimationSettings.blurPercent,"%",AnimationSettings::blur);
        section(page,"视差形变","拉伸与裁切同步调节，内外屏共用。");
        stretch=slider(card(page),"拉伸与裁切强度","默认 100%：偏转 30° 时裁切 12%。可调 0–125%；0% 关闭拉伸裁切。自动保存，调整立即生效。",0,ProjectionMath.MAX_STRETCH_PERCENT,5,AnimationSettings.stretchPercent,"%",AnimationSettings::stretch);
        section(page,"动画起点","调节接近合拢时的渐入角度，不改变切屏时机。");
        startAngle=slider(card(page),"动画起始角度","默认 1° · 可调 1–30°。超过设定角度后逐渐显现；合拢时反向淡出。完全合拢保护优先，实际起点受手机开合检测影响。",1,30,1,AnimationSettings.startAngle,"°",AnimationSettings::start);
        if(legacy){
        section(page,"切屏时机","展开与合拢分别设置，角度越小越接近合上。");
        LinearLayout angles=card(page);
        open=slider(angles,"展开时切到内屏","默认 60° · 可调 10–170°",10,170,1,AnimationSettings.openAngle,"°",AnimationSettings::open);
        View line=new View(this);line.setBackgroundColor(0xff304044);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.setMargins(0,dp(17),0,dp(20));angles.addView(line,lp);
        close=slider(angles,"合拢时切到外屏","默认 120° · 可调 10–170°",10,170,1,AnimationSettings.closeAngle,"°",AnimationSettings::close);
        }
        space(page,12);button(page,"回到桌面体验",()->startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)),true);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);page.addView(actions);
        Button reset=button(actions,"恢复默认",()->{AnimationSettings.reset();blur.setProgress(20);stretch.setProgress(ProjectionMath.DEFAULT_STRETCH_PERCENT/5);startAngle.setProgress(0);
            if(open!=null)open.setProgress(50);if(close!=null)close.setProgress(110);if(holdTime!=null)holdTime.setProgress(2);if(swipeRestore!=null)swipeRestore.setChecked(false);
            refreshStatus();Toast.makeText(this,"已恢复默认玻璃质感参数",Toast.LENGTH_SHORT).show();},false);
        reset.setLayoutParams(new LinearLayout.LayoutParams(0,dp(52),1));
        Button pause=button(actions,"停用服务",()->{ProjectionService.stop();refreshStatus();},false);pause.setLayoutParams(new LinearLayout.LayoutParams(0,dp(52),1));
        TextView foot=text("设置自动保存，下次开合生效。",12,MUTED);foot.setGravity(Gravity.CENTER);foot.setPadding(0,dp(15),0,0);page.addView(foot);
        button(page,"无线连接开源许可",this::showNotices,false);
        button(page,"更多配对方式",this::alternativePairing,false);
        scroll.requestApplyInsets();refreshStatus();
    }
    /** Desktop entry requires both preconditions: default home and the accessibility service. */
    private void openDesktop(){
        android.app.role.RoleManager roles=getSystemService(android.app.role.RoleManager.class);
        if(roles==null||!roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)){guideDefaultHome();return;}
        if(ProjectionService.instance==null){
            new AlertDialog.Builder(this).setTitle("需要开启无障碍")
                .setMessage("玻璃桌面需要「玻璃投影」无障碍服务：用于双屏桌面、状态栏和手势。")
                .setPositiveButton("去开启",(d,w)->AccessibilitySettings.open(this))
                .setNegativeButton("取消",null).show();
            return;
        }
        startActivity(new Intent(this,DuoHomeActivity.class));
    }
    private void guideDefaultHome(){
        new AlertDialog.Builder(this).setTitle("需要设为默认桌面")
            .setMessage("玻璃桌面以固定双屏方式运行，需要先将「玻璃投影」设为默认桌面。")
            .setPositiveButton("去设置",(d,w)->{
                try{
                    android.app.role.RoleManager roles=getSystemService(android.app.role.RoleManager.class);
                    if(roles!=null&&roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)&&!roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME))
                        startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME),4201);
                    else startActivityForResult(new Intent(Settings.ACTION_HOME_SETTINGS),4201);
                }catch(RuntimeException e){try{startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));}catch(RuntimeException ignored){}}
            })
            .setNegativeButton("取消",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==4201)openDesktop();
    }
    private void refreshStatus(){
        if(state==null)return;
        boolean legacy=!FixedDualSession.enabled(this);
        if(blacklist!=null)blacklist.setText("应用黑名单 · 已选 "+AnimationSettings.blacklistedApps.size()+" 个");
        boolean enabled=ProjectionService.instance!=null;
        boolean ready=enabled&&MobileHelper.ready()&&SystemClock.uptimeMillis()-ProjectionService.helperAt<3000;
        state.setText(ready?(legacy?"●  动画已就绪":"●  双屏桌面已就绪"):MobileHelper.ready()?enabled?"○  正在启动双屏桌面":"○  等待开启无障碍":"○  等待连接助手");
        String scope=legacy?AnimationSettings.globalEnabled?"已全局启用。":"在桌面或亮屏锁屏界面，展开或合拢手机即可体验。":"设为默认桌面后，两块屏幕常驻玻璃桌面，开合时画面柔和过渡。";
        hint.setText(ready?scope+(legacy?"半折悬停 "+AnimationSettings.holdSeconds+" 秒后恢复正常画面。":""):MobileHelper.ready()?"助手已连接，请完成第 2 步：开启无障碍。":"请先完成第 1 步：连接手机端助手。");
        if(mobileStatus!=null)mobileStatus.setText(MobileHelper.message);
        service.setText(enabled?"管理无障碍服务":MobileHelper.ready()?"开启无障碍":"先连接助手，再开启无障碍");
        service.setAlpha(enabled||MobileHelper.ready()?1:.5f);
        if(gestures!=null){boolean navigation=GestureNavigation.enabled(this);gestures.setText(switchingGestures?GestureNavigation.status:navigation?"恢复三键导航":"启用全面屏手势");gestures.setEnabled(!switchingGestures);gestures.setAlpha(!switchingGestures&&((navigation&&MobileHelper.ready())||(enabled&&MobileHelper.ready()))?1:.55f);}
    }
    private void toggleGestures(){
        if(switchingGestures)return;
        boolean next=!GestureNavigation.enabled(this);switchingGestures=true;refreshStatus();
        GestureNavigation.request(this,next,message->{switchingGestures=false;refreshStatus();Toast.makeText(this,message,Toast.LENGTH_LONG).show();});
    }
    private void pairWireless(){
        if(MobileHelper.wirelessReady()){Toast.makeText(this,"已配对，无需重复配对",Toast.LENGTH_LONG).show();return;}
        if(!WirelessAdb.paired(this)){startPairing();return;}
        if(WirelessAdb.isConnecting()){
            handler.postDelayed(()->{if(!isFinishing()&&!isDestroyed()&&hasWindowFocus())pairWireless();},500);return;
        }
        Toast.makeText(this,"正在检查已有配对并连接",Toast.LENGTH_LONG).show();
        WirelessAdb.connect(this,success->{refreshStatus();if(isFinishing()||isDestroyed())return;
            if(success){Toast.makeText(this,"已配对，无需重复配对",Toast.LENGTH_LONG).show();return;}
            if(!WirelessAdb.paired(this)){startPairing();return;}
            new AlertDialog.Builder(this).setTitle("暂未连接")
            .setMessage("本机已保存配对记录，请确认无线调试已开启。只有系统移除了玻璃投影的配对记录时，才需要重新配对。")
            .setPositiveButton("打开无线调试",(d,w)->startPairing()).setNeutralButton("重新配对",(d,w)->startPairing()).setNegativeButton("稍后",null).show();});
    }
    private void startPairing(){
        if(!DeveloperOptionsGuide.enabled(this)){DeveloperOptionsGuide.show(this);return;}
        WirelessPairingActivity.launch(this);
    }
    private void alternativePairing(){
        new AlertDialog.Builder(this).setTitle("更多配对方式")
            .setMessage("也可以在通知栏输入系统显示的 6 位配对码。请保持系统配对码窗口打开。")
            .setPositiveButton("通知配对",(d,w)->startNotificationPairing())
            .setNegativeButton("取消",null).show();
    }
    private void startNotificationPairing(){
        if(!DeveloperOptionsGuide.enabled(this)){DeveloperOptionsGuide.show(this);return;}
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},72);return;}
        android.app.NotificationManager manager=getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel channel=manager.getNotificationChannel(WirelessPairingService.CHANNEL);
        if(!manager.areNotificationsEnabled()||channel!=null&&channel.getImportance()==android.app.NotificationManager.IMPORTANCE_NONE){
            new AlertDialog.Builder(this).setTitle("开启配对通知").setMessage("通知配对需要显示通知并在其中输入配对码。请开启玻璃投影的配对通知。")
                .setPositiveButton("打开通知设置",(d,w)->{
                    waitingNotifications=true;
                    Intent settings=new Intent(channel==null?Settings.ACTION_APP_NOTIFICATION_SETTINGS:Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());
                    if(channel!=null)settings.putExtra(Settings.EXTRA_CHANNEL_ID,WirelessPairingService.CHANNEL);
                    try{startActivity(settings);}catch(android.content.ActivityNotFoundException e){waitingNotifications=false;Toast.makeText(this,"请在应用信息中开启通知",Toast.LENGTH_LONG).show();}
                }).setNegativeButton("取消",null).show();return;
        }
        launchPairing();
    }
    private void launchPairing(){startForegroundService(new Intent(this,WirelessPairingService.class));Toast.makeText(this,"打开系统配对码窗口后，下拉通知栏输入配对码",Toast.LENGTH_LONG).show();openWirelessSettings();}
    private void openWirelessSettings(){
        WirelessSettings.open(this);
    }
    private void showNotices(){
        try{
            String[] files=getAssets().list("notices");if(files==null)return;
            new AlertDialog.Builder(this).setTitle("开源组件与许可").setItems(files,(dialog,index)->{
                try(java.io.InputStream in=getAssets().open("notices/"+files[index])){
                    TextView content=text(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),12,TEXT);content.setPadding(dp(18),dp(12),dp(18),dp(12));content.setTextIsSelectable(true);
                    ScrollView view=new ScrollView(this);view.setBackgroundColor(BG);view.addView(content);
                    new AlertDialog.Builder(this).setTitle(files[index]).setView(view).setPositiveButton("关闭",null).show();
                }catch(java.io.IOException ignored){}
            }).setNegativeButton("关闭",null).show();
        }catch(java.io.IOException ignored){}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==72){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)startNotificationPairing();else Toast.makeText(this,"通知配对需要通知权限，请在应用信息中允许通知后重试",Toast.LENGTH_LONG).show();}
    }
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");return t;}
    private GradientDrawable background(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private LinearLayout card(LinearLayout page){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(20),dp(20),dp(20),dp(20));c.setBackground(background(CARD,24));page.addView(c,new LinearLayout.LayoutParams(-1,-2));return c;}
    private void section(LinearLayout page,String title,String description){space(page,26);TextView h=text(title,19,TEXT);h.setTypeface(null,Typeface.BOLD);page.addView(h);TextView sub=text(description,13,MUTED);sub.setPadding(0,dp(6),0,dp(14));page.addView(sub);}
    private void space(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private Button button(LinearLayout parent,String title,Runnable action,boolean filled){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(filled?BG:ACCENT);
        // Tinting the entire platform background hides its ripple, especially with transparent tint.
        StateListDrawable surface=new StateListDrawable();
        surface.addState(new int[]{android.R.attr.state_pressed},background(filled?0xff79c7b5:0x33a4e6d6,14));
        surface.addState(new int[]{android.R.attr.state_focused},background(filled?0xff8bd4c3:0x22a4e6d6,14));
        surface.addState(new int[]{},background(filled?ACCENT:Color.TRANSPARENT,14));
        b.setBackgroundTintList(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(filled?0x3310191c:0x55a4e6d6),surface,background(Color.WHITE,14)));
        b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));return b;
    }
    private SeekBar slider(LinearLayout card,String title,String description,int min,int max,int step,int initial,String unit,IntConsumer save){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);card.addView(row);
        TextView label=text(title,16,TEXT);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView value=text(initial+unit,25,ACCENT);value.setTypeface(null,Typeface.BOLD);row.addView(value);
        TextView desc=text(description,12,MUTED);desc.setPadding(0,dp(7),0,dp(8));card.addView(desc);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);card.addView(controls);
        SeekBar bar=new SeekBar(this);bar.setMax((max-min)/step);bar.setProgress((initial-min)/step);bar.setContentDescription(title);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));bar.setThumbTintList(ColorStateList.valueOf(ACCENT));bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff41575b));
        Button minus=button(controls,"−",()->{bar.setProgress(Math.max(0,bar.getProgress()-1));save.accept(min+bar.getProgress()*step);},false);
        minus.setContentDescription("减小"+title);minus.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));
        controls.addView(bar,new LinearLayout.LayoutParams(0,dp(48),1));
        Button plus=button(controls,"+",()->{bar.setProgress(Math.min(bar.getMax(),bar.getProgress()+1));save.accept(min+bar.getProgress()*step);},false);
        plus.setContentDescription("增加"+title);plus.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean user){int n=min+progress*step;value.setText(n+unit);b.setStateDescription(n+unit);if(user)save.accept(n);}
            public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}
        });bar.setStateDescription(initial+unit);return bar;
    }
    @Override public void onStart(){super.onStart();updates.observe(updateBadge);updates.check(false);}
    @Override public void onStop(){updates.unobserve(updateBadge);super.onStop();}
    @Override public void onResume(){super.onResume();if(waitingNotifications){waitingNotifications=false;if(getSystemService(android.app.NotificationManager.class).areNotificationsEnabled())startNotificationPairing();}if(MobileHelper.prefersWireless())MobileHelper.prepare(this);handler.removeCallbacks(tick);handler.post(tick);}
    @Override public void onSaveInstanceState(Bundle out){out.putBoolean("waitingNotifications",waitingNotifications);super.onSaveInstanceState(out);}
    @Override public void onPause(){handler.removeCallbacks(tick);super.onPause();}
}
