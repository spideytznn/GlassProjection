package io.github.sixzleo.tabfold.projection;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;

/** Stable physical output surface. Its producer is a separate task display, never a primary mirror. */
final class FixedDualOutput implements TextureView.SurfaceTextureListener,AutoCloseable {
    final boolean inner;
    final int physicalId,width,height,contentHeight,density,bandTop;
    private final int physicalWidth,physicalHeight;
    private final Matrix inverse=new Matrix();
    int contentId=-1;
    private final WindowManager manager;
    private final FrameLayout root;
    private final TextureView texture;
    private final View black;
    private final FixedDualSession owner;
    private Surface surface;
    private boolean closed,blocked=true;
    private final FixedDualGestureFeedback feedback;
    private final FrameLayout forwarder;
    private boolean shadeBand;
    private float shadeBandX,shadeBandY;
    private boolean bandReserved=true;
    private FixedDualGpu gpu;
    private final Handler main=new Handler(Looper.getMainLooper());
    /** Pill contrast sampling via PixelCopy: the system copies the strip under the pill into
     *  a tiny bitmap. A direct GL readback of the window surface corrupted the heap and
     *  crashed the GC within seconds (2026-09-18) and must not come back. */
    private static final int PROBE_W=128;
    private Bitmap probeBitmap;private boolean probeInFlight;
    private final Runnable pillProbe=new Runnable(){public void run(){
        if(closed||surface==null||contentId<0||!texture.isAvailable())return;
        if(probeBitmap==null)probeBitmap=Bitmap.createBitmap(PROBE_W,2,Bitmap.Config.ARGB_8888);
        if(probeInFlight){main.postDelayed(this,400);return;}
        probeInFlight=true;
        int left=Math.max(0,width/2-PROBE_W/2),top=height-Math.round(FixedDualGestureFeedback.PILL_BOTTOM_DP*density/160f)-1;
        try{
            PixelCopy.request(surface,new Rect(left,top,left+PROBE_W,top+2),probeBitmap,(result)->{
                probeInFlight=false;
                if(result!=PixelCopy.SUCCESS)return;
                float sum=0;int used=0;
                for(int y=0;y<2;y++)for(int x=0;x<PROBE_W;x++){
                    int c=probeBitmap.getPixel(x,y);
                    if((c>>>24)<40)continue;
                    sum+=(0.2126f*((c>>16)&0xff)+0.7152f*((c>>8)&0xff)+0.0722f*(c&0xff))/255f;used++;
                }
                if(used>0)feedback.setPillLuminance(sum/used);
            },main);
        }catch(RuntimeException e){probeInFlight=false;}
        main.postDelayed(this,400);
    }};
    FixedDualOutput(ProjectionService service,Display display,boolean inner,int densityOverride,FixedDualSession owner)throws Exception{

        this.inner=inner;this.owner=owner;physicalId=display.getDisplayId();
        Point size=new Point();display.getRealSize(size);physicalWidth=size.x;physicalHeight=size.y;
        int turn=ProjectionMath.turn(inner,display.getRotation());
        width=turn%2==0?size.x:size.y;height=turn%2==0?size.y:size.x;
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();display.getRealMetrics(metrics);
        // Panels can share a nominal densityDpi while their real PPI differs; callers may
        // pass a compensated value so one dp has the same physical size on both screens.
        density=densityOverride>0?densityOverride:metrics.densityDpi;
        // Full-height task display: the app's own bottom fills the gesture strip, no band is
        // reserved out of the content surface and the strip shader paths stay inert at
        // fraction 1. The gesture gate still owns the strip's touches; bandTop marks that
        // touch zone only.
        contentHeight=height;
        bandTop=height-Math.round(FixedDualGestureFeedback.GESTURE_BAND_DP*density/160f);
        Context context=service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
        manager=context.getSystemService(WindowManager.class);
        root=new FrameLayout(context);root.setBackgroundColor(Color.TRANSPARENT);
        texture=new TextureView(context);texture.setOpaque(false);texture.setSurfaceTextureListener(this);
        texture.setPivotX(0);texture.setPivotY(0);

        root.addView(texture,new FrameLayout.LayoutParams(width,height));
        texture.setPivotX(0);texture.setPivotY(0);
        Matrix placement=new Matrix();
        if(turn==1){texture.setRotation(-90);texture.setTranslationY(physicalHeight);placement.setRotate(-90);placement.postTranslate(0,physicalHeight);}
        else if(turn==2){texture.setRotation(180);texture.setTranslationX(physicalWidth);texture.setTranslationY(physicalHeight);placement.setRotate(180);placement.postTranslate(physicalWidth,physicalHeight);}
        else if(turn==3){texture.setRotation(90);texture.setTranslationX(physicalWidth);placement.setRotate(90);placement.postTranslate(physicalWidth,0);}
        placement.invert(inverse);
        black=new View(context);black.setBackgroundColor(Color.BLACK);root.addView(black,new FrameLayout.LayoutParams(-1,-1));
        android.util.Log.i("DuoRate","ios-timing snap build 20260920p");
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.setOnTouchListener((v,physicalEvent)->deliver(physicalEvent));
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // App vsync follows the physical panel; anchor it at 120 or HyperOS adaptive idles the panel to 60.
        p.preferredRefreshRate=120;
        p.setTitle("Duo "+(inner?"inner":"cover")+" independent output");manager.addView(root,p);
        feedback=new FixedDualGestureFeedback(context,density,key->MobileHelper.dualKey(contentId,key));
        // The pill lives in the forwarder's own window: on this composer the TextureView
        // layer outranks sibling views inside the output window, hiding anything over it.
        forwarder=new FrameLayout(context);forwarder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        forwarder.addView(feedback,new FrameLayout.LayoutParams(width,height));
        feedback.setPivotX(0);feedback.setPivotY(0);feedback.setRotation(texture.getRotation());feedback.setTranslationX(texture.getTranslationX());feedback.setTranslationY(texture.getTranslationY());
        forwarder.setOnTouchListener((v,physicalEvent)->deliver(physicalEvent));
        // Full-screen forwarder: the home-drawn status bar owns top-edge pulls on
        // the content display; the system shade is dormant in fixed dual.
        WindowManager.LayoutParams f=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSPARENT);
        f.gravity=Gravity.TOP|Gravity.LEFT;f.setFitInsetsTypes(0);
        f.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        f.preferredRefreshRate=120;
        f.setTitle("Duo "+(inner?"inner":"cover")+" touch forward");manager.addView(forwarder,f);
        // Mirror windows stack above an existing bar (same overlay type, add-order z):
        // re-apply bars so they stay visible on top of the output surface.
        root.post(DuoHomeActivity::refreshShadeBars);
        root.post(()->android.util.Log.i("DuoBand","fb attached="+feedback.isAttachedToWindow()+" "+feedback.getWidth()+"x"+feedback.getHeight()+" vis="+feedback.getVisibility()+" turn="+turn+" band="+(height-contentHeight)));
    }
    /** Shared physical-frame routing for both overlay listeners: while a sheet-sized
     *  shade owns this output, its sheet spans the reserved gesture band (band touches
     *  dismiss the sheet, the pill stands down); otherwise the dual gesture recognizer
     *  feeds first and everything else forwards into the content display. */
    private boolean deliver(MotionEvent physical){
        MotionEvent event=MotionEvent.obtain(physical);event.transform(inverse);
        try{
            if(blocked||contentId<0)return true;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)owner.touched(contentId);
            if(shadeBandTouch(event))return true;
            if(feedback.touch(event,DuoHomeActivity.barePanel(contentId)))return true;
            MobileHelper.dualTouch(contentId,event);return true;
        }finally{event.recycle();}
    }
    /** Close-handle semantics extended across the band: any drag past the slop or a
     *  plain tap dismisses the sheet instead of waking the gesture pill. */
    private boolean shadeBandTouch(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            shadeBand=HomeControlPanel.bottomCovered(contentId)&&event.getY()>bandTop;
            shadeBandX=event.getX();shadeBandY=event.getY();return shadeBand;
        }
        if(!shadeBand)return false;
        float slop=10f*density/160f;
        if(action==MotionEvent.ACTION_MOVE){
            if(Math.abs(event.getX()-shadeBandX)>slop||Math.abs(event.getY()-shadeBandY)>slop){
                shadeBand=false;HomeControlPanel.closeIfOpen(contentId);
            }
        }else if(action==MotionEvent.ACTION_UP){
            shadeBand=false;HomeControlPanel.closeIfOpen(contentId);
        }else if(action==MotionEvent.ACTION_CANCEL)shadeBand=false;
        return true;
    }
    void frame(float angle,boolean block){
        if(closed)return;
        boolean shade=contentId>=0&&HomeControlPanel.bottomCovered(contentId);
        boolean clear=contentId>=0&&DuoHomeActivity.barePanel(contentId);
        // Desktop mode and any fullscreen shade punch the strip through to the wallpaper:
        // the sheet must read as the top layer, with nothing mirroring its animation.
        if(gpu!=null)gpu.setBandClear(clear||shade);
        boolean nowBlocked=block||contentId<0;
        if(block&&!blocked&&contentId>=0){long now=SystemClock.uptimeMillis();MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);MobileHelper.dualTouch(contentId,cancel);cancel.recycle();}
        // Cancel only on the transition; a per-frame cancel invalidates the overlay at 120Hz.
        if(nowBlocked&&!blocked)feedback.cancel();
        blocked=nowBlocked;black.setVisibility(blocked?View.VISIBLE:View.GONE);
        // The pill stands down while a fullscreen shade owns the band or the panel is blocked.
        feedback.setPillVisible(bandReserved&&!shade&&!nowBlocked);
    }
    /** Collapses the reserved strip for immersive apps: pill stands down, the task display and
     *  shader input grow to full height; the gesture gate keeps owning the band's touches. */
    void setBandReserved(boolean reserve){
        if(gpu==null||contentId<0)return;
        bandReserved=reserve;
        feedback.setPillVisible(reserve);
        gpu.resizeContent(reserve?contentHeight:height);
        MobileHelper.resizeDualContent(contentId,width,reserve?contentHeight:height);
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture source,int w,int h){
        source.setDefaultBufferSize(width,height);surface=new Surface(source);
        // HyperOS ramps the panel only after frames flow; an ALWAYS vote asks for 120 from the
        // first frame of a gesture instead of after the ~350ms ramp.
        surface.setFrameRate(120,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,Surface.CHANGE_FRAME_RATE_ALWAYS);
        gpu=new FixedDualGpu(surface,width,height,contentHeight,inner,()->contentId>=0,input->MobileHelper.createDualContent(input,width,contentHeight,density,inner,id->{
            if(closed)return;if(id<0){owner.fail("无法创建"+(inner?"内屏":"外屏")+"桌面");return;}contentId=id;owner.contentReady();
        }),owner::fail,(direct,target)->MobileHelper.dualSurface(contentId,target));
        // Luminance sampling for pill contrast runs via PixelCopy from the view, never
        // through a GL readback of this surface.
        main.removeCallbacks(pillProbe);main.postDelayed(pillProbe,400);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){if(!closed)owner.fail("输出画面已断开");return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s){}
    @Override public void close(){if(closed)return;closed=true;main.removeCallbacks(pillProbe);if(gpu!=null)gpu.close();
        try{manager.removeViewImmediate(forwarder);}catch(IllegalArgumentException ignored){}
        try{manager.removeViewImmediate(root);}catch(IllegalArgumentException ignored){}if(surface!=null)surface.release();}
}
