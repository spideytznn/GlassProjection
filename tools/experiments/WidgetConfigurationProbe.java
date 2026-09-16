import android.content.Context;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;

/** Read-only shell catalog: identify installed providers with native configuration. */
public final class WidgetConfigurationProbe {
    public static void main(String[] args)throws Exception {
        android.os.Looper.prepare();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object thread=activityThread.getMethod("systemMain").invoke(null);
        Context system=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
        Context context=system.createPackageContext("com.android.shell",0);
        for(AppWidgetProviderInfo info:AppWidgetManager.getInstance(context).getInstalledProviders()){
            if(info.configure!=null)System.out.println(info.provider.flattenToShortString()+" label="+info.loadLabel(context.getPackageManager())+" configure="+info.configure.flattenToShortString());
        }
        System.exit(0); // systemMain owns background threads; this probe has no ongoing work.
    }
}
