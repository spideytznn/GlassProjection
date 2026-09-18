package io.github.sixzleo.tabfold.projection;
import android.view.Surface;
import android.os.Bundle;
import android.view.MotionEvent;
interface IHelperHost {
    int ensureRunning() = 1;
    void stopHelpers() = 2;
    String status() = 3;
    boolean observeTouch(IBinder connection, boolean enabled) = 4;
    String configureGestureNavigation(boolean enabled) = 5;
    String gestureNavigationStatus() = 6;
    String fixedDualState(int state, IBinder lifetime) = 7;
    int createDualContent(in Surface surface, int width, int height, int density, boolean inner) = 8;
    void dualTouch(int displayId, in MotionEvent event) = 9;
    void dualKey(int displayId, int keyCode) = 10;
    Bundle dualContact() = 11;
    String svc(String command) = 12;
    void dualSurface(int displayId, in Surface surface) = 13;
    void resizeDualContent(int displayId, int width, int height) = 14;
    void destroy() = 16777114;
}
