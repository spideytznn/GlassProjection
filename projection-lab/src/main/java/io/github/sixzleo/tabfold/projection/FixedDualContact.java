package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.hardware.*;
import android.os.*;

/** Shell-owned sampling avoids the vendor's background-app continuous-sensor gate. */
final class FixedDualContact implements SensorEventListener,AutoCloseable {
    private SensorManager sensors;
    private HandlerThread thread;
    private boolean available;
    private volatile Bundle latest;
    FixedDualContact(Context context){
        if(!"lhasa".equals(Build.DEVICE))return;
        try{
            Object activityThread=Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
            context=(Context)activityThread.getClass().getMethod("getSystemContext").invoke(activityThread);
            sensors=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
            for(Sensor s:sensors.getSensorList(Sensor.TYPE_ALL)){
                if(!"xiaomi.sensor.dighall".equals(s.getStringType())
                        ||!"ak0991x Digital Hall Sensor Non-wakeup".equals(s.getName()))continue;
                thread=new HandlerThread("Glass-contact-sensor");thread.start();
                available=sensors.registerListener(this,s,20000,new Handler(thread.getLooper()));
                break;
            }
            System.out.println("DIRECT_CONTACT available="+available+" uid="+android.os.Process.myUid());
        }catch(Exception e){System.out.println("DIRECT_CONTACT unavailable "+e);}
        if(!available)close();
    }
    public void onSensorChanged(SensorEvent event){
        Bundle data=new Bundle();data.putBoolean("directContactAvailable",true);
        data.putLong("directContactAt",event.timestamp);
        data.putFloatArray("directContactValues",event.values.clone());latest=data;
    }
    public void onAccuracyChanged(Sensor sensor,int accuracy){}
    Bundle sample(){
        Bundle current=latest;
        if(current!=null)return new Bundle(current);
        Bundle data=new Bundle();data.putBoolean("directContactAvailable",available);return data;
    }
    public void close(){
        if(sensors!=null)sensors.unregisterListener(this);
        if(thread!=null){thread.quitSafely();thread=null;}
    }
}
