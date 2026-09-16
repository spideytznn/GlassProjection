package io.github.sixzleo.tabfold.projection;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Desktop notification feed behind the home status bar and control panel. */
public final class DuoNotifications extends NotificationListenerService {
    public static final class Item {
        public final String key,packageName,label,title,text;
        public final long when;
        final StatusBarNotification sbn;
        Item(String key,String packageName,String label,String title,String text,long when,StatusBarNotification sbn){
            this.key=key;this.packageName=packageName;this.label=label;this.title=title;this.text=text;this.when=when;this.sbn=sbn;
        }
        boolean open(Context context){
            android.app.PendingIntent content=sbn.getNotification().contentIntent;
            if(content==null)return false;
            try{content.send(context,0,null);return true;}catch(Exception ignored){return false;}
        }
    }
    private static volatile List<Item> items=Collections.emptyList();
    private static volatile boolean connected;
    private static DuoNotifications instance;
    private static final List<Runnable> observers=new CopyOnWriteArrayList<>();
    private static final Handler main=new Handler(Looper.getMainLooper());
    static List<Item> snapshot(){return items;}
    static boolean available(){return connected&&instance!=null;}
    /**
     * After a package replace HyperOS keeps the grant but often leaves the listener
     * unbound (ServiceRecord app=null). Re-granting through the shell helper forces
     * an immediate rebind; onListenerConnected then refreshes observers by itself.
     */
    static void ensureBound(){
        if(connected)return;
        if(!MobileHelper.ready())return;
        String cn="io.github.sixzleo.tabfold.projection/io.github.sixzleo.tabfold.projection.DuoNotifications";
        MobileHelper.svc("cmd notification disallow_listener "+cn,out->{
            if(!out.startsWith("ERROR"))
                MobileHelper.svc("cmd notification allow_listener "+cn,ignored->{});
        });
    }
    static void observe(Runnable change){observers.add(change);notifyChange();}
    static void forget(Runnable change){observers.remove(change);}
    static void cancel(String key){
        DuoNotifications current=instance;
        if(current!=null)try{current.cancelNotification(key);}catch(RuntimeException ignored){}
    }
    static void cancelAll(){
        DuoNotifications current=instance;
        if(current==null)return;
        for(Item item:items)try{current.cancelNotification(item.key);}catch(RuntimeException ignored){}
    }
    @Override public void onListenerConnected(){instance=this;connected=true;rebuild();}
    @Override public void onNotificationPosted(StatusBarNotification sbn){rebuild();}
    @Override public void onNotificationRemoved(StatusBarNotification sbn){rebuild();}
    @Override public void onDestroy(){instance=null;connected=false;items=Collections.emptyList();notifyChange();super.onDestroy();}
    private void rebuild(){
        StatusBarNotification[] active;
        try{active=getActiveNotifications();}catch(RuntimeException unavailable){return;}
        List<Item> latest=new ArrayList<>();
        if(active!=null){
            PackageManager packages=getApplicationContext().getPackageManager();
            for(StatusBarNotification sbn:active){
                if(sbn==null||"io.github.sixzleo.tabfold.projection".equals(sbn.getPackageName()))continue;
                if((sbn.getNotification().flags&Notification.FLAG_GROUP_SUMMARY)!=0)continue;
                String title=string(sbn,Notification.EXTRA_TITLE),text=string(sbn,Notification.EXTRA_TEXT),label=sbn.getPackageName();
                try{label=packages.getApplicationLabel(packages.getApplicationInfo(sbn.getPackageName(),0)).toString();}catch(Exception ignored){}
                latest.add(new Item(sbn.getKey(),sbn.getPackageName(),label,title,text,sbn.getNotification().when,sbn));
            }
        }
        latest.sort((a,b)->Long.compare(b.when,a.when));
        if(latest.size()>30)latest=latest.subList(0,30);
        items=Collections.unmodifiableList(latest);
        notifyChange();
    }
    private static String string(StatusBarNotification sbn,String key){
        CharSequence value=sbn.getNotification().extras.getCharSequence(key);
        return value==null?"":value.toString();
    }
    private static void notifyChange(){
        for(Runnable change:observers.toArray(new Runnable[0]))main.post(change);
    }
    static boolean canRead(Context context){return connected;}
}
