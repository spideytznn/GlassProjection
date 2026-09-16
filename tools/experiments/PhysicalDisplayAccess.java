package io.github.sixzleo.tabfold.probe;

import android.os.IBinder;
import android.view.SurfaceControl;
import java.lang.reflect.Method;

/** Android 14+ moved physical-token access into the server library.
 * Uses the same public-source loading approach documented by scrcpy's DisplayControl wrapper.
 * Loading a class in this process does not inject code into system_server.
 */
final class PhysicalDisplayAccess {
    private final Class<?> control;
    PhysicalDisplayAccess()throws Exception {
        Class<?> factory=Class.forName("com.android.internal.os.ClassLoaderFactory");
        ClassLoader loader=(ClassLoader)factory.getDeclaredMethod("createClassLoader",String.class,String.class,String.class,ClassLoader.class,int.class,boolean.class,String.class)
            .invoke(null,System.getenv("SYSTEMSERVERCLASSPATH"),null,null,ClassLoader.getSystemClassLoader(),0,true,null);
        control=loader.loadClass("com.android.server.display.DisplayControl");
        Method library=Runtime.class.getDeclaredMethod("loadLibrary0",Class.class,String.class);library.setAccessible(true);
        library.invoke(Runtime.getRuntime(),control,"android_servers");
    }
    IBinder token(long id)throws Exception{return (IBinder)control.getMethod("getPhysicalDisplayToken",long.class).invoke(null,id);}
    void on(IBinder token)throws Exception{SurfaceControl.class.getMethod("setDisplayPowerMode",IBinder.class,int.class).invoke(null,token,2);}
}
