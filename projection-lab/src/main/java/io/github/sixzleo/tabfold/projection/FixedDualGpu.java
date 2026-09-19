package io.github.sixzleo.tabfold.projection;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.*;
import android.view.Surface;
import java.nio.*;

/** Original live fold shader and Gaussian pipeline fed by an independent task surface. */
final class FixedDualGpu implements AutoCloseable {
    static volatile String innerStats="idle",coverStats="idle";
    private final HandlerThread thread=new HandlerThread("Duo-live-fold");
    private final Handler worker,main=new Handler(Looper.getMainLooper());
    private final int width,height;private volatile int contentHeight;private final boolean inner;
    private final java.util.function.Consumer<String> failed;
    private volatile boolean closed;
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;private EGLContext context=EGL14.EGL_NO_CONTEXT;private EGLSurface window=EGL14.EGL_NO_SURFACE;
    private SurfaceTexture source;private Surface input;private FixedDualGpuPyramid pyramid;private int external,program;
    private boolean dirty,hasFrame,pyramidDirty;private float lastTilt=Float.NaN,lastCrop,lastOpacity,lastStrength;
    private volatile float bandClear,bandTarget;private float lastBandClear=-1;
    private volatile float bandMix=1,bandMixTarget=1;private float lastBandMix=-1;
    private long sourceFrames,pyramidUpdates,presentedFrames,statsAt;
    private final float[] matrix=new float[16];
    private final ProjectionAngleMotion motion=new ProjectionAngleMotion();
    private final ProjectionEntrance entrance=new ProjectionEntrance();
    private long started;
    private final Surface output;
    private final java.util.function.BooleanSupplier directAllowed;
    private final java.util.function.BiConsumer<Boolean,Surface> swap;
    private EGLConfig config;
    private boolean direct;
    private int identityFrames;
    private long lastPresentMs;
    FixedDualGpu(Surface output,int width,int height,int contentHeight,boolean inner,java.util.function.BooleanSupplier directAllowed,java.util.function.Consumer<Surface> ready,java.util.function.Consumer<String> failed,java.util.function.BiConsumer<Boolean,Surface> swap){
        this.width=width;this.height=height;this.contentHeight=contentHeight;this.inner=inner;this.failed=failed;this.output=output;this.directAllowed=directAllowed;this.swap=swap;thread.start();worker=new Handler(thread.getLooper());
        worker.post(()->{try{init(output);main.post(()->{if(!closed)ready.accept(input);});worker.post(draw);}catch(Exception e){error(e);}});
    }
    private void init(Surface output){
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
        if(!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("eglInitialize");
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        if(!EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0))throw new IllegalStateException("eglChooseConfig");
        config=configs[0];
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        window=EGL14.eglCreateWindowSurface(display,configs[0],output,new int[]{EGL14.EGL_NONE},0);
        if(!EGL14.eglMakeCurrent(display,window,window,context))throw new IllegalStateException("eglMakeCurrent");
        program=FixedDualGpuPyramid.program(FixedDualGpuProgram.FOLD_FRAG);
        int[] names=new int[1];GLES20.glGenTextures(1,names,0);external=names[0];GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,external);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        source=new SurfaceTexture(external);source.setDefaultBufferSize(width,contentHeight);source.setOnFrameAvailableListener(s->dirty=true,worker);input=new Surface(source);
        input.setFrameRate(120,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        pyramid=new FixedDualGpuPyramid(Math.max(width,contentHeight)/2,vertices);
        GLES20.glUseProgram(program);uniform("canvasSize",0);uniform("spillFraction",ProjectionMath.INNER_SPILL_FRACTION);uniform("hingeDistanceFraction",ProjectionMath.OUTER_HINGE_DISTANCE_FRACTION);
        uniform("inner",inner?1:0);uniform("turn",0);uniform("screenDarkness",0);
        // The canvas keeps full panel height while the task buffer ends above the gesture strip;
        // the shader clamps sampling at that fraction and extends the bottom edge into the strip.
        uniform("contentFraction",contentHeight>0&&contentHeight<height?(float)contentHeight/(float)height:1f);
        // While a fold effect animates, the band folds into the paper: no mirror, no dim.
        uniform("bandMix",1);
        // Desktop mode clears the gesture strip so the physical wallpaper shows through.
        uniform("bandClear",0);
        // The original compositor's source-backed path includes the clear receiving half.
        // screen is the source buffer size: the pyramid letterbox inverse and sigma scale must
        // match the buffer the pyramid was built from, not the taller physical canvas.
        uniform("screenFadeActive",1);GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"screen"),width,contentHeight);started=SystemClock.uptimeMillis();
    }
    private void uniform(String name,float value){GLES20.glUniform1f(GLES20.glGetUniformLocation(program,name),value);}
    /** Desktop mode asks the shader to punch the gesture strip through to the wallpaper;
     *  the value eases per frame so home/app switches crossfade instead of snapping. */
    void setBandClear(boolean on){bandTarget=on?1f:0f;}
    /** Collapses or restores the reserved strip: the task buffer grows to full canvas height
     *  so immersive apps render edge to edge; the gesture gate itself keeps working. */
    void resizeContent(int newHeight){
        if(newHeight<=0||newHeight>height)return;
        worker.post(()->{if(closed)return;contentHeight=newHeight;source.setDefaultBufferSize(width,contentHeight);
            GLES20.glUseProgram(program);uniform("contentFraction",contentHeight<height?(float)contentHeight/height:1f);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"screen"),width,contentHeight);pyramidDirty=true;});
    }
    private final Runnable draw=new Runnable(){public void run(){
        if(closed)return;
        try{
            long now=SystemClock.uptimeMillis();FoldPose pose=ProjectionService.foldPose.expireDirectContact(SystemClock.elapsedRealtimeNanos());
            boolean blocked=ProjectionAngleMotion.hardBlocked(pose.blocksProjection(),pose.fullyOpened(),inner);
            float eased=motion.update(now,pose.angle(),inner,pose.blocksProjection(),pose.fullyOpened(),false);
            float endpoint=ProjectionMath.endpointOpacity(eased,inner,AnimationSettings.startAngle,blocked);
            boolean visible=ProjectionMath.endpointOpacity(inner?eased:pose.angle(),inner,AnimationSettings.startAngle,blocked)>0;
            float entry=entrance.update(now,visible,false,started,inner?180:80);
            float amount=ProjectionMath.onsetMotion(endpoint,entry);
            float tilt=ProjectionMath.effectTilt(eased,inner,AnimationSettings.startAngle,blocked)*amount;
            float crop=ProjectionMath.cropFraction(eased,inner,AnimationSettings.stretchPercent)*amount;
            if(bandClear!=bandTarget){bandClear+=(bandTarget-bandClear)*.22f;if(Math.abs(bandTarget-bandClear)<.02f)bandClear=bandTarget;}
            bandMixTarget=(tilt==0f&&crop==0f)?1f:0f;
            if(bandMix!=bandMixTarget){bandMix+=(bandMixTarget-bandMix)*.3f;if(Math.abs(bandMixTarget-bandMix)<.02f)bandMix=bandMixTarget;}
            if(direct){
                // Only a developing fold effect needs the shader; watch cheaply while bypassed.
                if(tilt!=0f||crop!=0f)leaveDirect();
                worker.postDelayed(this,50);return;
            }
            boolean changed=dirty;dirty=false;
            if(changed){GLES20.glActiveTexture(GLES20.GL_TEXTURE7);source.updateTexImage();source.getTransformMatrix(matrix);hasFrame=true;pyramidDirty=true;sourceFrames++;}
            if(hasFrame){
                float opacity=ProjectionMath.onsetOpacity(endpoint),strength=AnimationSettings.blurPercent/100f;
                // The shader samples only nativeSource when opacity is below .001,
                // tilt is zero, or blurStrength is zero. Keep the old pyramid dirty:
                // a later fold must refresh it even when the source did not change.
                boolean needsPyramid=opacity>=.001f&&tilt!=0f&&strength>0f;
                if(needsPyramid&&pyramidDirty){pyramid.update(external,matrix,width,contentHeight,0);pyramidDirty=false;pyramidUpdates++;}
                if(changed||!Float.isFinite(lastTilt)||Math.abs(tilt-lastTilt)>.002f||Math.abs(crop-lastCrop)>.00001f||opacity!=lastOpacity||strength!=lastStrength||bandClear!=lastBandClear||bandMix!=lastBandMix){
                    // Pace presents to at most ~120/s: back-to-back swaps inside one vsync
                    // alias against the TextureView latch (one frame is dropped) and the
                    // jitter makes HyperOS's adaptive policy hunt between 60/72/90 mid-swipe.
                    boolean due=now-lastPresentMs>=8;
                    if(due){
                        pyramid.bind(program,width,height);
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,external);
                        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"nativeSource"),7);
                        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"nativeTex"),1,false,matrix,0);
                        uniform("tilt",(float)Math.toRadians(tilt));uniform("crop",crop);uniform("opacity",opacity);uniform("blurStrength",strength);
                        uniform("bandClear",bandClear);lastBandClear=bandClear;
                        uniform("bandMix",bandMix);lastBandMix=bandMix;
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                        if(!EGL14.eglSwapBuffers(display,window))throw new IllegalStateException("Present failed");
                        presentedFrames++;lastPresentMs=now;
                        lastTilt=tilt;lastCrop=crop;lastOpacity=opacity;lastStrength=strength;
                    }
                }
                if(now-statsAt>=1000){statsAt=now;String stats="source="+sourceFrames+" pyramid="+pyramidUpdates+" presented="+presentedFrames+" tilt="+tilt+" needsBlur="+needsPyramid;if(inner)innerStats=stats;else coverStats=stats;}
            }
            // Steady identity means the shader is a plain copy; hand the TextureView straight
            // to the virtual display until a fold effect develops again. The supplier gate
            // keeps the swap from firing before the task display exists: dualSurface(-1,..)
            // is a silent no-op and a folded-stable desktop would then stay black forever
            // (2026-09-18 outage). The swap itself is synchronous, so once it returns the
            // display is re-targeted; leaveDirect re-attaches EGL when a fold starts.
            if(tilt==0f&&crop==0f){if(directAllowed.getAsBoolean()&&++identityFrames>=64)goDirect();}
            else identityFrames=0;
            // Free-run on the worker thread: touching the UI thread's choreographer from
            // here floods main with traversals next to the desktop's own rendering and
            // starves the whole pipeline (measured 12fps page swipes, 2026-09-19).
            worker.postDelayed(this,4);
        }catch(Exception e){error(e);}
    }};
    /** Bypasses the shader: release the TextureView's producer to the virtual display and idle. */
    private void goDirect(){
        direct=true;identityFrames=0;
        if(window!=EGL14.EGL_NO_SURFACE){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);EGL14.eglDestroySurface(display,window);window=EGL14.EGL_NO_SURFACE;}
        swap.accept(true,output);
        String stats="direct presented="+presentedFrames;if(inner)innerStats=stats;else coverStats=stats;
    }
    private void leaveDirect(){
        direct=false;
        swap.accept(false,input);
        // The virtual display must release the TextureView's queue before EGL re-attaches;
        // one retry guards the residual race on slow binder round trips.
        for(int attempt=0;attempt<2;attempt++){
            window=EGL14.eglCreateWindowSurface(display,config,output,new int[]{EGL14.EGL_NONE},0);
            if(window!=EGL14.EGL_NO_SURFACE&&EGL14.eglMakeCurrent(display,window,window,context))break;
            window=EGL14.EGL_NO_SURFACE;
            if(attempt==1)throw new IllegalStateException("eglMakeCurrent");
            SystemClock.sleep(30);
        }
        hasFrame=false;pyramidDirty=true;lastTilt=Float.NaN;statsAt=0;
    }
    private void error(Exception e){android.util.Log.e("DuoFixed","Original fold renderer",e);main.post(()->failed.accept(e.toString()));close();}
    @Override public void close(){if(closed)return;closed=true;worker.removeCallbacksAndMessages(null);worker.post(()->{
        if(input!=null)input.release();if(source!=null)source.release();if(pyramid!=null)pyramid.close();
        if(program!=0)GLES20.glDeleteProgram(program);if(external!=0)GLES20.glDeleteTextures(1,new int[]{external},0);
        if(display!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);}
        thread.quitSafely();
    });}
}
