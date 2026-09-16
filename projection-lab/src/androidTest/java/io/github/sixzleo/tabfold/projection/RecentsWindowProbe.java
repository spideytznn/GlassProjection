package io.github.sixzleo.tabfold.projection;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Reports window metadata only. Does not read app content or change navigation preferences. */
final class RecentsWindowProbe {
    static String run(Instrumentation test){
        UiAutomation ui=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        StringBuilder result=new StringBuilder();
        ui.setOnAccessibilityEventListener(event->{if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            synchronized(result){result.append("WINDOW ").append(event.getWindowId()).append(' ').append(event.getPackageName()).append(' ').append(event.getClassName()).append('\n');}});
        try{
            result.append("RECENTS accepted=").append(ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)).append('\n');
            SystemClock.sleep(900);
            for(AccessibilityWindowInfo window:ui.getWindows()){
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                AccessibilityNodeInfo root=window.getRoot();
                try{synchronized(result){result.append("ACTIVE ").append(window.isActive()).append(" id=").append(window.getId());
                    if(root!=null)result.append(" package=").append(root.getPackageName()).append(" class=").append(root.getClassName());result.append('\n');}}
                finally{if(root!=null)root.recycle();}
            }
            ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(600);
        }finally{ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);SystemClock.sleep(300);ui.setOnAccessibilityEventListener(null);}
        synchronized(result){return result.toString();}
    }
}
