package io.github.sixzleo.tabfold.projection;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;

/** Stable physical output surface. Its producer is a separate task display, never a primary mirror. */
final class FixedDualOutput implements TextureView.SurfaceTextureListener,AutoCloseable {
    final boolean inner;
    final int physicalId,width,height,density;
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
    private FixedDualGpu gpu;
    FixedDualOutput(ProjectionService service,Display display,boolean inner,FixedDualSession owner)throws Exception{
        this.inner=inner;this.owner=owner;physicalId=display.getDisplayId();
        Point size=new Point();display.getRealSize(size);physicalWidth=size.x;physicalHeight=size.y;
        int turn=ProjectionMath.turn(inner,display.getRotation());
        width=turn%2==0?size.x:size.y;height=turn%2==0?size.y:size.x;
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();display.getRealMetrics(metrics);density=metrics.densityDpi;
        Context context=service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
        manager=context.getSystemService(WindowManager.class);root=new FrameLayout(context);root.setBackgroundColor(Color.BLACK);
        texture=new TextureView(context);texture.setOpaque(true);texture.setSurfaceTextureListener(this);
        root.addView(texture,new FrameLayout.LayoutParams(width,height));
        texture.setPivotX(0);texture.setPivotY(0);
        Matrix placement=new Matrix();
        if(turn==1){texture.setRotation(-90);texture.setTranslationY(physicalHeight);placement.setRotate(-90);placement.postTranslate(0,physicalHeight);}
        else if(turn==2){texture.setRotation(180);texture.setTranslationX(physicalWidth);texture.setTranslationY(physicalHeight);placement.setRotate(180);placement.postTranslate(physicalWidth,physicalHeight);}
        else if(turn==3){texture.setRotation(90);texture.setTranslationX(physicalWidth);placement.setRotate(90);placement.postTranslate(physicalWidth,0);}
        placement.invert(inverse);
        feedback=new FixedDualGestureFeedback(context,density,key->MobileHelper.dualKey(contentId,key));
        root.addView(feedback,new FrameLayout.LayoutParams(width,height));
        feedback.setPivotX(0);feedback.setPivotY(0);feedback.setRotation(texture.getRotation());feedback.setTranslationX(texture.getTranslationX());feedback.setTranslationY(texture.getTranslationY());
        black=new View(context);black.setBackgroundColor(Color.BLACK);root.addView(black,new FrameLayout.LayoutParams(-1,-1));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.setOnTouchListener((v,physicalEvent)->{
            MotionEvent event=MotionEvent.obtain(physicalEvent);event.transform(inverse);
            try{
            if(blocked||contentId<0)return true;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)owner.touched(contentId);
            if(feedback.touch(event,DuoHomeActivity.barePanel(contentId)))return true;
            MobileHelper.dualTouch(contentId,event);return true;
            }finally{event.recycle();}
        });
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.OPAQUE);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("Duo "+(inner?"inner":"cover")+" independent output");manager.addView(root,p);
    }
    void frame(float angle,boolean block){
        if(closed)return;
        if(block&&!blocked&&contentId>=0){long now=SystemClock.uptimeMillis();MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);MobileHelper.dualTouch(contentId,cancel);cancel.recycle();}
        blocked=block||contentId<0;if(blocked)feedback.cancel();black.setVisibility(blocked?View.VISIBLE:View.GONE);

    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture source,int w,int h){
        source.setDefaultBufferSize(width,height);surface=new Surface(source);
        gpu=new FixedDualGpu(surface,width,height,inner,input->MobileHelper.createDualContent(input,width,height,density,inner,id->{
            if(closed)return;if(id<0){owner.fail("无法创建"+(inner?"内屏":"外屏")+"桌面");return;}contentId=id;owner.contentReady();
        }),owner::fail);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){if(!closed)owner.fail("输出画面已断开");return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s){}
    @Override public void close(){if(closed)return;closed=true;if(gpu!=null)gpu.close();try{manager.removeViewImmediate(root);}catch(IllegalArgumentException ignored){}if(surface!=null)surface.release();}
}
