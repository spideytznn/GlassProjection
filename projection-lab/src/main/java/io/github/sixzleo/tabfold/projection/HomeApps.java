package io.github.sixzleo.tabfold.projection;

import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import java.text.Collator;
import java.util.*;

final class HomeApps {
    static final class App {
        final String key,label,search;
        final ComponentName component;
        final UserHandle user;
        final Bitmap icon;
        App(String key,String label,ComponentName component,UserHandle user,Bitmap icon){
            this.key=key;this.label=label;this.component=component;this.user=user;this.icon=icon;
            search=(label+" "+component.getPackageName()).toLowerCase(Locale.ROOT);
        }
    }
    /** Rasterized icons survive reloads: package-change callbacks reuse entries whose label did not change. */
    private static final Map<String,App> catalog=new HashMap<>();
    static List<App> load(Context context){
        LauncherApps launcher=context.getSystemService(LauncherApps.class);
        UserManager users=context.getSystemService(UserManager.class);
        List<App> result=new ArrayList<>();
        for(UserHandle user:users.getUserProfiles()){
            List<LauncherActivityInfo> entries;
            try{entries=launcher.getActivityList(null,user);}catch(SecurityException ignored){continue;}
            long serial=users.getSerialNumberForUser(user);if(serial<0)continue;
            for(LauncherActivityInfo info:entries){
                ComponentName component=info.getComponentName();
                if(component.getPackageName().equals(context.getPackageName()))continue;
                String key=component.flattenToString()+"@"+serial;
                String label=info.getLabel().toString();
                App cached=catalog.get(key);
                if(cached!=null&&cached.label.equals(label)){result.add(cached);continue;}
                int pixels=Math.min(192,Math.max(96,Math.round(56*context.getResources().getDisplayMetrics().density)));
                Bitmap bitmap=Bitmap.createBitmap(pixels,pixels,Bitmap.Config.ARGB_8888);
                try{Drawable icon=info.getBadgedIcon(context.getResources().getDisplayMetrics().densityDpi);icon.setBounds(0,0,pixels,pixels);icon.draw(new Canvas(bitmap));}
                catch(RuntimeException ignored){Drawable icon=context.getPackageManager().getDefaultActivityIcon();icon.setBounds(0,0,pixels,pixels);icon.draw(new Canvas(bitmap));}
                App app=new App(key,label,component,user,bitmap);
                result.add(app);catalog.put(key,app);
            }
        }
        Collator collator=Collator.getInstance(Locale.CHINA);
        result.sort((a,b)->{int order=collator.compare(a.label,b.label);return order==0?a.key.compareTo(b.key):order;});
        return result;
    }
}
