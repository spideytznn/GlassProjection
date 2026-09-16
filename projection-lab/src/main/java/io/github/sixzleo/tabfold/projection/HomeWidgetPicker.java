package io.github.sixzleo.tabfold.projection;

import android.app.Activity;
import android.appwidget.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Browses standard installed providers. Binding and configuration stay with HomeWidgets. */
final class HomeWidgetPicker {
    static final class Icon {
        final ApplicationInfo application;
        final UserHandle profile;
        Bitmap bitmap;
        boolean requested;
        Icon(ApplicationInfo application,UserHandle profile){this.application=application;this.profile=profile;}
    }
    static final class Entry {
        final AppWidgetProviderInfo provider;
        final String title,application,search;
        final Icon icon;
        Entry(AppWidgetProviderInfo provider,String title,String application,Icon icon){
            this.provider=provider;this.title=title;this.application=application;this.icon=icon;
            search=(title+" "+application+" "+provider.provider.getPackageName()).toLowerCase(Locale.ROOT);
        }
    }
    final HomeSheet sheet;
    private final Activity activity;
    private final Consumer<AppWidgetProviderInfo> selected;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Entry> entries=new ArrayList<>(),visible=new ArrayList<>();
    private final EditText query;
    private final TextView count,empty;
    private final ListView list;
    private final Button clear;
    private final BaseAdapter adapter;
    private boolean loading,failed,closed;

    HomeWidgetPicker(Activity activity,Consumer<AppWidgetProviderInfo> selected){
        this.activity=activity;this.selected=selected;sheet=new HomeSheet(activity,600,620);sheet.header("添加现有小组件");
        LinearLayout field=new LinearLayout(activity);field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(HomeStyle.surface(field,HomeStyle.FIELD,HomeStyle.FIELD_RADIUS));
        query=new EditText(activity);query.setTag("home-widget-query");query.setSingleLine(true);query.setHint("搜索小组件或应用名称");
        query.setTextColor(0xfff4f7fb);query.setHintTextColor(0xffc3cedb);query.setTextSize(16);query.setPadding(dp(14),0,dp(4),0);query.setBackgroundColor(Color.TRANSPARENT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);field.addView(query,new LinearLayout.LayoutParams(0,dp(52),1));
        clear=new Button(activity);clear.setText("×");clear.setTextColor(0xfff4f7fb);clear.setMinWidth(0);clear.setMinimumWidth(0);clear.setPadding(0,0,0,0);clear.setBackgroundColor(Color.TRANSPARENT);
        clear.setContentDescription("清空搜索");clear.setOnClickListener(v->query.setText(""));field.addView(clear,new LinearLayout.LayoutParams(dp(48),dp(48)));
        sheet.content.addView(field,new LinearLayout.LayoutParams(-1,dp(52)));
        count=text("",12,0xffc3cedb);count.setPadding(dp(4),dp(10),0,dp(6));sheet.content.addView(count,new LinearLayout.LayoutParams(-1,dp(36)));
        FrameLayout results=new FrameLayout(activity);sheet.content.addView(results,new LinearLayout.LayoutParams(-1,0,1));
        list=new ListView(activity);list.setTag("home-widget-results");list.setDivider(null);results.addView(list,new FrameLayout.LayoutParams(-1,-1));
        empty=text("",15,0xffc3cedb);empty.setTag("home-widget-empty");empty.setGravity(Gravity.CENTER);results.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
        adapter=new BaseAdapter(){
            public int getCount(){return visible.size();}public Entry getItem(int position){return visible.get(position);}public long getItemId(int position){return position;}
            public View getView(int position,View recycled,ViewGroup parent){
                Row row=recycled instanceof Row?(Row)recycled:new Row();Entry entry=getItem(position);
                row.icon.setImageBitmap(entry.icon.bitmap);requestIcon(entry.icon);row.title.setText(entry.title);
                row.application.setText(entry.application+(entry.provider.getProfile().equals(android.os.Process.myUserHandle())?"":" · 工作资料"));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent,row,position,id)->{
            if(closed||position<0||position>=visible.size())return;
            AppWidgetProviderInfo provider=visible.get(position).provider;dismiss();this.selected.accept(provider);
        });
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int n,int after){}public void afterTextChanged(Editable text){}
            public void onTextChanged(CharSequence s,int start,int before,int n){filter();}});
        query.setOnEditorActionListener((v,action,event)->{
            if(action!=EditorInfo.IME_ACTION_SEARCH)return false;
            WindowInsetsController controller=query.getWindowInsetsController();if(controller!=null)controller.hide(WindowInsets.Type.ime());return true;
        });
        empty.setOnClickListener(v->{if(failed&&!closed)load();});
        sheet.setOnDismissListener(dialog->{closed=true;loader.shutdownNow();main.removeCallbacksAndMessages(null);list.setAdapter(null);entries.clear();visible.clear();});
    }
    void show(){sheet.show();load();}
    void dismiss(){sheet.dismiss();}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private void load(){
        if(closed||loading)return;loading=true;failed=false;filter();Context context=activity.getApplicationContext();
        loader.execute(()->{
            try{List<Entry> found=catalog(context);main.post(()->{if(closed)return;entries.clear();entries.addAll(found);loading=false;filter();});}
            catch(RuntimeException unavailable){main.post(()->{if(closed)return;loading=false;failed=true;filter();});}
        });
    }
    private void filter(){
        String term=query.getText().toString().trim().toLowerCase(Locale.ROOT);visible.clear();
        for(Entry entry:entries)if(entry.search.contains(term))visible.add(entry);
        adapter.notifyDataSetChanged();clear.setEnabled(query.length()>0);
        count.setText(loading?"正在读取小组件…":String.format(Locale.CHINA,"%d 个小组件",visible.size()));
        empty.setText(loading?"正在读取手机已有的小组件…":failed?"暂时无法读取小组件\n点击重试":entries.isEmpty()?"没有可添加的小组件": "没有找到小组件\n试试应用名称或其他关键词");
        empty.setClickable(failed);empty.setFocusable(failed);
    }
    private void requestIcon(Icon icon){
        if(closed||icon.requested||icon.bitmap!=null)return;icon.requested=true;Context context=activity.getApplicationContext();
        loader.execute(()->{
            if(Thread.currentThread().isInterrupted())return;Bitmap bitmap=loadIcon(context,icon);
            main.post(()->{if(closed)return;icon.bitmap=bitmap;adapter.notifyDataSetChanged();});
        });
    }
    static Bitmap loadIcon(Context context,Icon source){
        PackageManager packages=context.getPackageManager();
        int pixels=Math.min(128,Math.max(64,Math.round(44*context.getResources().getDisplayMetrics().density)));
        Bitmap bitmap=Bitmap.createBitmap(pixels,pixels,Bitmap.Config.ARGB_8888);
        try{
            Drawable drawable=source.application==null?packages.getDefaultActivityIcon():source.application.loadIcon(packages);
            drawable=packages.getUserBadgedIcon(drawable,source.profile);drawable.setBounds(0,0,pixels,pixels);drawable.draw(new Canvas(bitmap));
        }catch(RuntimeException unavailable){Drawable fallback=packages.getDefaultActivityIcon();fallback.setBounds(0,0,pixels,pixels);fallback.draw(new Canvas(bitmap));}
        return bitmap;
    }
    static List<Entry> catalog(Context context){
        long started=SystemClock.uptimeMillis(),providerMs=0,applicationMs=0,labelMs=0;
        AppWidgetManager manager=AppWidgetManager.getInstance(context);PackageManager packages=context.getPackageManager();
        List<Entry> entries=new ArrayList<>();Map<String,Icon> icons=new HashMap<>();Map<String,ApplicationInfo> applications=new HashMap<>();Map<String,String> applicationNames=new HashMap<>();
        LauncherApps launcher=context.getSystemService(LauncherApps.class);
        UserManager users=context.getSystemService(UserManager.class);
        for(UserHandle profile:users.getUserProfiles()){
            long serial=users.getSerialNumberForUser(profile);if(serial<0)continue;
            List<AppWidgetProviderInfo> providers;
            long phase=SystemClock.uptimeMillis();
            try{providers=manager.getInstalledProvidersForProfile(profile);}catch(SecurityException unavailable){continue;}
            providerMs+=SystemClock.uptimeMillis()-phase;
            // The launcher directory supplies most ApplicationInfo records in one IPC;
            // request individual records only for providers without a launcher activity.
            phase=SystemClock.uptimeMillis();
            try{for(LauncherActivityInfo launch:launcher.getActivityList(null,profile))
                applications.putIfAbsent(launch.getComponentName().getPackageName()+"@"+serial,launch.getApplicationInfo());}
            catch(SecurityException unavailable){}
            applicationMs+=SystemClock.uptimeMillis()-phase;
            for(AppWidgetProviderInfo info:providers){
                if(Thread.currentThread().isInterrupted())return Collections.emptyList();
                if(info.provider==null)continue;
                String iconKey=info.provider.getPackageName()+"@"+serial;
                phase=SystemClock.uptimeMillis();
                if(!applications.containsKey(iconKey)){
                    ApplicationInfo found=null;
                    try{found=launcher.getApplicationInfo(info.provider.getPackageName(),0,profile);}catch(PackageManager.NameNotFoundException|RuntimeException unavailable){}
                    applications.put(iconKey,found);
                }
                ApplicationInfo applicationInfo=applications.get(iconKey);
                applicationMs+=SystemClock.uptimeMillis()-phase;phase=SystemClock.uptimeMillis();
                if(!applicationNames.containsKey(iconKey)){
                    String name=info.provider.getPackageName();
                    try{if(applicationInfo!=null)name=applicationInfo.loadLabel(packages).toString();}catch(RuntimeException unavailable){}
                    applicationNames.put(iconKey,name);
                }
                String application=applicationNames.get(iconKey),title=application;
                try{String name=info.loadLabel(packages);if(name!=null&&!name.isEmpty())title=name;}catch(RuntimeException unavailable){}
                labelMs+=SystemClock.uptimeMillis()-phase;
                Icon icon=icons.get(iconKey);if(icon==null){icon=new Icon(applicationInfo,profile);icons.put(iconKey,icon);}
                entries.add(new Entry(info,title,application,icon));
            }
        }
        Collator order=Collator.getInstance(Locale.CHINA);
        entries.sort((a,b)->{int app=order.compare(a.application,b.application);if(app!=0)return app;int name=order.compare(a.title,b.title);
            return name!=0?name:a.provider.provider.flattenToString().compareTo(b.provider.provider.flattenToString());});
        android.util.Log.d("GlassWidgetCatalog","metadata="+(SystemClock.uptimeMillis()-started)+"ms providers="+providerMs+" applications="+applicationMs+" labels="+labelMs);
        return entries;
    }
    private final class Row extends LinearLayout {
        final ImageView icon;final TextView title,application;
        Row(){
            super(activity);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(8),dp(10),dp(8),dp(10));setMinimumHeight(dp(76));
            icon=new ImageView(activity);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));
            GradientDrawable placeholder=new GradientDrawable();placeholder.setColor(0x22566f85);placeholder.setCornerRadius(dp(12));icon.setBackground(placeholder);
            LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(14),0,0,0);
            title=text("",16,0xfff4f7fb);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);
            application=text("",12,0xffc3cedb);application.setSingleLine(true);application.setEllipsize(TextUtils.TruncateAt.END);labels.addView(application);
            addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        }
    }
}
