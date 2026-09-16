import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Shell-only, read-only hierarchy dump for a specific logical display. */
public final class DualUiDump {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        if(args.length!=1)throw new IllegalArgumentException("logical display ID required");
        int display=Integer.parseInt(args[0]);
        HandlerThread thread=new HandlerThread("duo-ui-audit");thread.start();
        UiAutomation automation=null;
        boolean connected=false,failed=false;
        try {
            System.out.println("Connecting read-only UI automation");
            Class<?> connection=Class.forName("android.app.IUiAutomationConnection");
            Object instance=Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation=(UiAutomation)UiAutomation.class.getConstructor(Looper.class,connection).newInstance(thread.getLooper(),instance);
            UiAutomation.class.getMethod("connect",int.class).invoke(automation,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            connected=true;
            AccessibilityServiceInfo info=automation.getServiceInfo();
            info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            automation.waitForIdle(300,3000);
            SparseArray<List<AccessibilityWindowInfo>> displays=automation.getWindowsOnAllDisplays();
            for(int i=0;i<displays.size();i++)System.out.println("Available display="+displays.keyAt(i)+" windows="+displays.valueAt(i).size());
            List<AccessibilityWindowInfo> windows=displays.get(display);
            if(windows==null)throw new IllegalStateException("No accessibility windows on display "+display);
            for(AccessibilityWindowInfo window:windows){
                System.out.println("WINDOW display="+display+" type="+window.getType()+" title="+window.getTitle());
                AccessibilityNodeInfo root=window.getRoot();
                if(root!=null)dump(root,0);
            }
        } catch(Throwable failure){failed=true;failure.printStackTrace(System.out);
        } finally {
            try{if(connected)UiAutomation.class.getMethod("disconnect").invoke(automation);}
            finally{thread.quitSafely();}
        }
        if(failed)System.exit(1);
    }
    private static void dump(AccessibilityNodeInfo node,int depth){
        try {
            android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);
            if(node.getText()!=null||node.getContentDescription()!=null||node.isClickable())
                System.out.println(" ".repeat(Math.min(depth,32))+node.getClassName()+" text="+node.getText()+" description="+node.getContentDescription()+" clickable="+node.isClickable()+" bounds="+bounds);
            for(int i=0;i<node.getChildCount();i++){
                AccessibilityNodeInfo child=node.getChild(i);if(child!=null)dump(child,depth+1);
            }
        } finally {node.recycle();}
    }
}
