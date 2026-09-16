package io.github.sixzleo.tabfold.projection;

import android.app.*;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.SystemClock;
import android.view.*;
import android.accessibilityservice.AccessibilityService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opt-in device test: leaves compatible gestures enabled, as authorized by the user. */
final class HomeNavigationSmoke {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String run(HomeSmoke test,boolean clearTasks)throws Exception{
        UiAutomation ui=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        check(test.getTargetContext().getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_HOME),"Duo must already be the user-authorized default HOME");
        // Instrumentation restarts the target process; rebind only this already-enabled service.
        String current=android.provider.Settings.Secure.getString(test.getTargetContext().getContentResolver(),"enabled_accessibility_services");
        String own=test.getTargetContext().getPackageName()+"/"+ProjectionService.class.getName();
        check(current!=null&&java.util.Arrays.asList(current.split(":")).contains(own),"Accessibility service must already be enabled by the user");
        String others=java.util.Arrays.stream(current.split(":")).filter(value->!value.equals(own)).collect(java.util.stream.Collectors.joining(":"));
        if(others.isEmpty())shell(ui,"settings delete secure enabled_accessibility_services");
        else shell(ui,"settings put secure enabled_accessibility_services "+others);
        SystemClock.sleep(250);
        shell(ui,"settings put secure enabled_accessibility_services "+current);
        for(int i=0;i<100&&ProjectionService.instance==null;i++)SystemClock.sleep(100);
        if(!MobileHelper.ready()){
            test.runOnMainSync(()->MobileHelper.selectWireless(test.getTargetContext()));
            android.os.ParcelFileDescriptor launch=ui.executeShellCommand("env CLASSPATH="+test.getTargetContext().getApplicationInfo().sourceDir+" setsid /system/bin/app_process /system/bin io.github.sixzleo.tabfold.projection.StandaloneHelper");
            for(int i=0;i<150&&!MobileHelper.ready();i++)SystemClock.sleep(100);
            launch.close();
        }
        CountDownLatch enabled=new CountDownLatch(1);String[] response={"timeout"};
        test.runOnMainSync(()->GestureNavigation.request(test.getTargetContext(),true,value->{response[0]=value;enabled.countDown();}));
        check(enabled.await(15,TimeUnit.SECONDS)&&GestureNavigation.enabled(test.getTargetContext()),"Enable compatible gestures: "+response[0]);
        Instrumentation.ActivityMonitor monitor=test.addMonitor(DuoHomeActivity.class.getName(),null,false);
        test.getTargetContext().startActivity(new Intent(test.getTargetContext(),DuoHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity activity=test.waitForMonitorWithTimeout(monitor,10000);test.removeMonitor(monitor);
        check(activity!=null,"HOME activity available");SystemClock.sleep(1500);
        int[] size=new int[2];View[] root={null};
        test.runOnMainSync(()->{android.graphics.Rect bounds=activity.getWindowManager().getCurrentWindowMetrics().getBounds();size[0]=bounds.width();size[1]=bounds.height();root[0]=activity.getWindow().getDecorView().findViewWithTag("home-root");check(DuoHomeActivity.foreground,"HOME focused");});
        check(root[0]!=null,"HOME root rendered");StringBuilder report=new StringBuilder(response[0]).append('\n');
        for(int route=0;route<(clearTasks?4:3);route++){
            boolean left=route==0;final int currentRoute=route;
            swipe(ui,size[0]/2f,size[1]-8,size[0]/2f,size[1]-350,180,500);
            boolean[] recents={false};
            for(int i=0;i<30;i++){test.runOnMainSync(()->recents[0]=ProjectionService.recentsVisible()&&!DuoHomeActivity.foreground);if(recents[0])break;SystemClock.sleep(100);}
            check(recents[0],"physical bottom swipe opens recognized native Recents");
            int[] intermediate={0};
            ViewTreeObserver.OnPreDrawListener listener=()->{float a=root[0].getAlpha();if(DuoHomeActivity.foreground&&a>0&&a<.995f&&root[0].getScaleX()<1)intermediate[0]++;return true;};
            test.runOnMainSync(()->root[0].getViewTreeObserver().addOnPreDrawListener(listener));
            if(route==3){
                // Device-specific coordinates from the current native UI hierarchy.
                check(size[0]==1168&&size[1]==1712,"clear test requires inspected outer-screen geometry");
                SystemClock.sleep(700);long down=SystemClock.uptimeMillis();event(ui,down,MotionEvent.ACTION_DOWN,584,1547);event(ui,down,MotionEvent.ACTION_UP,584,1547);
            }else if(route==2){long down=SystemClock.uptimeMillis();event(ui,down,MotionEvent.ACTION_DOWN,size[0]/2f,size[1]*.12f);event(ui,down,MotionEvent.ACTION_UP,size[0]/2f,size[1]*.12f);}
            else swipe(ui,left?5:size[0]-5,size[1]/2f,left?350:size[0]-350,size[1]/2f,220,0);
            SystemClock.sleep(route==3?3500:1300);
            test.runOnMainSync(()->{root[0].getViewTreeObserver().removeOnPreDrawListener(listener);check(DuoHomeActivity.foreground&&activity.hasWindowFocus(),"route "+currentRoute+" returns focused HOME; foreground="+DuoHomeActivity.foreground+", focus="+activity.hasWindowFocus());check(root[0].getAlpha()==1f&&root[0].getScaleX()==1f,"entrance reaches final state");});
            check(intermediate[0]>1,"HOME entrance has multiple intermediate rendered frames");
            report.append(route==3?"CLEAR":route==2?"BLANK":left?"LEFT":"RIGHT").append(": native Recents -> HOME; transition frames=").append(intermediate[0]).append('\n');
            swipe(ui,left?5:size[0]-5,size[1]/2f,left?350:size[0]-350,size[1]/2f,220,0);
            SystemClock.sleep(300);test.runOnMainSync(()->check(DuoHomeActivity.foreground,"desktop edge swipe remains HOME"));
        }
        ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        return "PASS: "+report;
    }
    static void swipe(UiAutomation ui,float x,float y,float endX,float endY,int duration,int hold){
        long down=SystemClock.uptimeMillis();event(ui,down,MotionEvent.ACTION_DOWN,x,y);
        for(int i=1;i<=12;i++){SystemClock.sleep(duration/12);event(ui,down,MotionEvent.ACTION_MOVE,x+(endX-x)*i/12f,y+(endY-y)*i/12f);}
        SystemClock.sleep(hold);event(ui,down,MotionEvent.ACTION_UP,endX,endY);
    }
    static void event(UiAutomation ui,long down,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{check(ui.injectInputEvent(event,true),"touch injection accepted");}finally{event.recycle();}
    }
    static void shell(UiAutomation ui,String command)throws Exception{
        try(java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))){input.readAllBytes();}
    }
}
