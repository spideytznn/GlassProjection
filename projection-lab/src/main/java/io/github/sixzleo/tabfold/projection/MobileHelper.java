package io.github.sixzleo.tabfold.projection;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import rikka.shizuku.Shizuku;
import java.util.concurrent.*;

/** Lifecycle belongs to the accessibility service, never to its settings Activity. */
final class MobileHelper {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static Context context;
    private static volatile IHelperHost host;
    private static volatile boolean active,prepared,wirelessHost;
    private static boolean initialized,binding;
    private static long bindingAt;
    static volatile String message="请先连接手机端助手";
    private static Shizuku.UserServiceArgs arguments;
    private static final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){binding=false;if(prefersWireless())return;host=IHelperHost.Stub.asInterface(binder);wirelessHost=false;message="手机端助手已连接";schedule();}
        public void onServiceDisconnected(ComponentName name){binding=false;if(!wirelessHost){host=null;message="助手连接中断，等待恢复";}}
    };
    static void init(Context c){
        if(initialized)return;initialized=true;context=c.getApplicationContext();
        arguments=new Shizuku.UserServiceArgs(new ComponentName(context,MobileHelperHost.class)).daemon(true).processNameSuffix("glass_helpers").tag("glass_helpers").version(30);
        Shizuku.addBinderReceivedListenerSticky(()->{if(!prefersWireless()){message="Shizuku 已启动";schedule();}});
        Shizuku.addBinderDeadListener(()->{if(!wirelessHost&&!prefersWireless()){host=null;binding=false;message="Shizuku 已停止，请在手机上重新启动";}});
        Shizuku.addRequestPermissionResultListener((code,result)->{if(code==312){message=result==PackageManager.PERMISSION_GRANTED?"已授权，正在连接":"未授予 Shizuku 权限";schedule();}});
    }
    static void start(Context c){init(c);prepared=true;active=true;schedule();}
    static boolean ready(){IHelperHost h=host;return h!=null&&h.asBinder().isBinderAlive();}
    static boolean wirelessReady(){return wirelessHost&&ready();}
    static boolean prefersWireless(){return context!=null&&context.getSharedPreferences("wireless",0).getBoolean("preferred",false);}
    static void prepare(Context c){init(c);prepared=true;schedule();}
    static void selectWireless(Context c){
        init(c);context.getSharedPreferences("wireless",0).edit().putBoolean("preferred",true).apply();
        prepared=true;
        IHelperHost old=host;host=null;
        try{if(old!=null)old.stopHelpers();}catch(Exception ignored){}
        main.post(()->{try{if(arguments!=null&&Shizuku.pingBinder())Shizuku.unbindUserService(arguments,connection,true);}catch(Exception ignored){}binding=false;schedule();});
    }
    static void acceptWireless(Context c,IBinder binder){
        init(c);
        main.post(()->{
            if(!prefersWireless())return;
            if(host!=null&&host.asBinder().equals(binder))return;
            host=IHelperHost.Stub.asInterface(binder);wirelessHost=true;prepared=true;
            try{binder.linkToDeath(()->main.post(()->{if(host!=null&&host.asBinder().equals(binder)){host=null;message="助手已断开，等待自动重连";schedule();}}),0);}catch(RemoteException ignored){host=null;}
            message="本机助手已连接";schedule();
        });
    }
    static boolean available(){return active&&host!=null&&host.asBinder().isBinderAlive();}
    static void observeTouch(IBinder connection,java.util.function.Consumer<Boolean> done){
        final IHelperHost current=host;
        worker.execute(()->{boolean okay=false;try{if(active&&current!=null)okay=current.observeTouch(connection,true);}catch(Exception ignored){}
            final boolean result=okay;main.post(()->done.accept(result));});
    }
    static void configureGestureNavigation(boolean enabled,java.util.function.Consumer<String> done){
        final IHelperHost current=host;
        worker.execute(()->{
            String result="ERROR 手机端助手未连接";
            try{if(current!=null&&current.asBinder().isBinderAlive())result=current.configureGestureNavigation(enabled);}
            catch(Exception e){result="ERROR "+e.getMessage();}
            final String value=result;main.post(()->done.accept(value));
        });
    }
    private static final IBinder fixedLifetime=new Binder();
    static void fixedDualState(int state,java.util.function.Consumer<String> done){
        final IHelperHost current=host;
        worker.execute(()->{String value;try{value=current==null?"ERROR helper unavailable":current.fixedDualState(state,fixedLifetime);}catch(Exception e){value="ERROR "+e;}
            final String result=value;main.post(()->done.accept(result));});
    }
    static void dualContact(java.util.function.Consumer<Bundle> done){IHelperHost current=host;
        worker.execute(()->{Bundle result=null;try{if(current!=null)result=current.dualContact();}catch(Exception ignored){}
            Bundle value=result;main.post(()->done.accept(value));});}
    static void createDualContent(android.view.Surface surface,int w,int h,int density,boolean inner,java.util.function.IntConsumer done){
        IHelperHost current=host;
        worker.execute(()->{int id=-1;try{if(current!=null)id=current.createDualContent(surface,w,h,density,inner);}catch(Exception e){android.util.Log.e("DuoFixed","Create",e);}
            final int result=id;main.post(()->done.accept(result));});
    }
    static void dualTouch(int id,android.view.MotionEvent event){
        IHelperHost current=host;android.view.MotionEvent copy=android.view.MotionEvent.obtain(event);
        worker.execute(()->{try{if(current!=null)current.dualTouch(id,copy);}catch(Exception ignored){}finally{copy.recycle();}});
    }
    static void dualKey(int id,int key){IHelperHost current=host;worker.execute(()->{try{if(current!=null)current.dualKey(id,key);}catch(Exception ignored){}});}
    private static void schedule(){main.removeCallbacks(tick);main.post(tick);}
    private static final Runnable tick=new Runnable(){public void run(){
        if(!active&&!prepared)return;
        try{
            if(ready()){
                final IHelperHost current=host;
                if(active)worker.execute(()->{if(!active||current!=host)return;try{int state=current.ensureRunning();message=state==4?"Duo 固定双屏运行中":state==3?"手机端助手运行中":"助手启动未完成";}catch(Exception e){if(current==host)host=null;binding=false;message="连接中断，正在重连";}});
                else message="助手已连接，下一步开启无障碍";
            }
            else if(prefersWireless()){WirelessAdb.reconnect(context);}
            else if(!Shizuku.pingBinder())message="请先连接本机助手，或启动备用 Shizuku";
            else if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)message="请授权 Shizuku 启动助手";
            else if(host==null){
                if(!binding||SystemClock.uptimeMillis()-bindingAt>20000){
                    // A failed UserService launch can leave a record with no process.
                    // Remove that timed-out record before requesting another launch.
                    if(binding)Shizuku.unbindUserService(arguments,connection,true);
                    binding=true;bindingAt=SystemClock.uptimeMillis();Shizuku.bindUserService(arguments,connection);message="正在启动手机端助手";
                }
            }
        }catch(RuntimeException e){binding=false;message="Shizuku 连接失败，请检查授权";}
        main.postDelayed(this,3000);
    }};
    static void stop(){
        active=false;prepared=false;main.removeCallbacks(tick);final IHelperHost current=host;final boolean wasWireless=wirelessHost;
        worker.execute(()->{if(active)return;try{if(current!=null)current.stopHelpers();}catch(Exception ignored){}
            if(wasWireless)try{if(current!=null)current.destroy();}catch(Exception ignored){}
            main.post(()->{if(active)return;try{if(arguments!=null&&Shizuku.pingBinder())Shizuku.unbindUserService(arguments,connection,true);}catch(RuntimeException ignored){}host=null;binding=false;});
        });
    }
    static void authorize(Context c){
        init(c);prepared=true;
        if(wirelessReady()){message="本机助手已连接，无需 Shizuku";return;}
        context.getSharedPreferences("wireless",0).edit().putBoolean("preferred",false).apply();
        try{
            if(Shizuku.pingBinder()){
                if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)Shizuku.requestPermission(312);
                else {message="Shizuku 已授权";schedule();}
            }else{
                Intent launch=c.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if(launch!=null)c.startActivity(launch);else message="请先安装 Shizuku";
            }
        }catch(RuntimeException e){message="请在 Shizuku 中检查本应用授权";}
    }
}
