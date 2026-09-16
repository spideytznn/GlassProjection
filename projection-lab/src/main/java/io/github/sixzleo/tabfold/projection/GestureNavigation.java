package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import java.util.function.Consumer;

final class GestureNavigation {
    private static final String PREFS="gesture_navigation",KEY="enabled";
    static volatile String status="三键导航";
    static boolean enabled(Context context){return context.getSharedPreferences(PREFS,0).getBoolean(KEY,false);}
    static void save(Context context,boolean enabled){context.getSharedPreferences(PREFS,0).edit().putBoolean(KEY,enabled).apply();}
    static void request(Context context,boolean enabled,Consumer<String> done){
        Context app=context.getApplicationContext();
        if(enabled&&ProjectionService.instance==null){done.accept("请先开启玻璃投影无障碍服务");return;}
        if(!MobileHelper.ready()){done.accept("手机端助手未连接，无法切换系统导航栏");return;}
        status=enabled?"正在隐藏三键导航":"正在恢复三键导航";
        MobileHelper.configureGestureNavigation(enabled,result->{
            boolean okay=result.startsWith("OK ");
            if(okay){save(app,enabled);status=enabled?"全面屏手势已启用":"三键导航已恢复";ProjectionService.refreshGestureNavigation();}
            else status="切换失败";
            done.accept(okay?status:result.replaceFirst("^ERROR\\s*",""));
        });
    }
    static void serviceStopping(Context context){
        if(!enabled(context))return;
        String services=android.provider.Settings.Secure.getString(context.getContentResolver(),android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String own=context.getPackageName()+"/"+ProjectionService.class.getName();
        if(services==null||!services.contains(own)){
            save(context,false);status="正在恢复三键导航";
            MobileHelper.configureGestureNavigation(false,result->{status=result.startsWith("OK ")?"三键导航已恢复":"三键恢复失败，请重新连接助手";});
        }
    }
}
